package io.workflowexecutor.execution;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.workflowexecutor.definition.DependencyGraph;
import io.workflowexecutor.definition.RetrySpec;
import io.workflowexecutor.definition.StepDefinition;
import io.workflowexecutor.definition.StepType;
import io.workflowexecutor.definition.TaskSpec;
import io.workflowexecutor.definition.WorkflowDefinition;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Executes a definition as a dependency graph. Every step whose dependencies have all succeeded starts
 * at once, so independent branches run in parallel. A step whose dependency did not succeed is skipped,
 * and branches that do not depend on the failure keep running.
 *
 * <p>This is Temporal workflow code. After a worker crash Temporal replays it from the run's history,
 * so it must make the same decisions every time: time comes from {@link Workflow#currentTimeMillis()},
 * waiting goes through Temporal promises and timers, and collections are iterated in a fixed order.
 */
public class DagWorkflowImpl implements DagWorkflow {

    private static final Logger log = Workflow.getLogger(DagWorkflowImpl.class);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(5);
    private static final String CANCELLED_BEFORE_START = "Run was cancelled before the step started";

    private final Map<String, StepRun> steps = new LinkedHashMap<>();

    @Override
    public void execute(WorkflowDefinition definition) {
        definition.steps().forEach(step -> steps.put(step.id(), new StepRun(step)));
        List<StepRun> executionOrder = DependencyGraph.executionOrder(definition.dependencyGraph()).stream()
                .map(steps::get)
                .toList();
        try {
            runUntilNoStepIsInFlight(executionOrder);
        } catch (CanceledFailure cancellation) {
            cancelUnfinishedSteps();
            throw cancellation;
        }
        failIfAnyStepFailed();
    }

    @Override
    public RunProgress progress() {
        return new RunProgress(steps.values().stream().map(StepRun::toProgress).toList());
    }

    /**
     * Once a cancellation is requested, no further step starts, but the loop keeps waiting until every step in
     * flight has stopped: a timer stops at once, a task at its next heartbeat, and a task that finishes before it
     * notices keeps its real outcome. Only then does the run end. Temporal records a run as cancelled once a
     * cancellation was requested, whatever the workflow code does afterwards.
     */
    private void runUntilNoStepIsInFlight(List<StepRun> executionOrder) {
        Map<StepRun, Promise<StepOutcome>> inFlight = new LinkedHashMap<>();
        startReadyStepsUnlessCancelled(executionOrder, inFlight);
        while (!inFlight.isEmpty()) {
            Promise.anyOf(inFlight.values()).get();
            recordFinishedSteps(inFlight);
            startReadyStepsUnlessCancelled(executionOrder, inFlight);
        }
        CancellationScope.throwCanceled();
    }

    private void startReadyStepsUnlessCancelled(
            List<StepRun> executionOrder, Map<StepRun, Promise<StepOutcome>> inFlight) {
        if (!CancellationScope.current().isCancelRequested()) {
            startReadySteps(executionOrder, inFlight);
        }
    }

    /** Walks steps in dependency order, so a skip propagates to every dependent in a single pass. */
    private void startReadySteps(List<StepRun> executionOrder, Map<StepRun, Promise<StepOutcome>> inFlight) {
        for (StepRun step : executionOrder) {
            if (step.status() != StepStatus.PENDING) {
                continue;
            }
            Optional<StepRun> blocker = firstDependencyThatDidNotSucceed(step);
            if (blocker.isPresent()) {
                skip(step, blocker.get());
            } else if (allDependenciesSucceeded(step)) {
                inFlight.put(step, start(step));
            }
        }
    }

    private Optional<StepRun> firstDependencyThatDidNotSucceed(StepRun step) {
        return step.definition().dependsOn().stream()
                .map(steps::get)
                .filter(StepRun::finishedWithoutSuccess)
                .findFirst();
    }

    private boolean allDependenciesSucceeded(StepRun step) {
        return step.definition().dependsOn().stream()
                .map(steps::get)
                .allMatch(dependency -> dependency.status() == StepStatus.SUCCEEDED);
    }

    private void skip(StepRun step, StepRun blocker) {
        String reason = "Dependency '%s' finished as %s".formatted(blocker.id(), blocker.status());
        step.markSkipped(Workflow.currentTimeMillis(), reason);
        log.info("Step {} skipped: {}", step.id(), reason);
    }

    private Promise<StepOutcome> start(StepRun step) {
        step.markRunning(Workflow.currentTimeMillis());
        log.info("Step {} started", step.id());
        if (step.definition().type() == StepType.DELAY) {
            return startDelay(step.definition());
        }
        return startTask(step.definition());
    }

    /** A durable timer: it survives worker restarts and holds no worker thread while it waits. */
    private Promise<StepOutcome> startDelay(StepDefinition step) {
        return Workflow.newTimer(Duration.ofMillis(step.delayMs())).handle((ignored, failure) -> {
            if (failure != null) {
                return StepFailures.timerOutcome(failure);
            }
            return StepOutcome.succeeded(null);
        });
    }

    private Promise<StepOutcome> startTask(StepDefinition step) {
        TaskSpec task = step.task();
        TaskActivities activities = Workflow.newActivityStub(TaskActivities.class, activityOptions(step.id(), task));
        String idempotencyKey = Workflow.getInfo().getWorkflowId() + ":" + step.id();
        TaskInvocation invocation = new TaskInvocation(step.id(), idempotencyKey, task.simulation());
        return Async.function(activities::runTask, invocation).handle((outcome, failure) -> {
            if (failure != null) {
                return StepFailures.taskOutcome(failure, task.retry().maxAttempts());
            }
            return StepOutcome.succeeded(outcome.attempt());
        });
    }

    private static ActivityOptions activityOptions(String stepId, TaskSpec task) {
        RetrySpec retry = task.retry();
        RetryOptions retryOptions = RetryOptions.newBuilder()
                .setMaximumAttempts(retry.maxAttempts())
                .setInitialInterval(Duration.ofMillis(retry.initialIntervalMs()))
                .setBackoffCoefficient(retry.backoffCoefficient())
                .setMaximumInterval(Duration.ofMillis(retry.maxIntervalMs()))
                .build();
        // A cancelled task is reported CANCELLED only once it has stopped; one that finishes first keeps its outcome.
        return ActivityOptions.newBuilder()
                .setSummary(stepId)
                .setStartToCloseTimeout(Duration.ofMillis(task.timeoutMs()))
                .setHeartbeatTimeout(HEARTBEAT_TIMEOUT)
                .setRetryOptions(retryOptions)
                .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                .build();
    }

    private void recordFinishedSteps(Map<StepRun, Promise<StepOutcome>> inFlight) {
        long nowMs = Workflow.currentTimeMillis();
        Iterator<Map.Entry<StepRun, Promise<StepOutcome>>> entries = inFlight.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<StepRun, Promise<StepOutcome>> entry = entries.next();
            if (!entry.getValue().isCompleted()) {
                continue;
            }
            StepOutcome outcome = entry.getValue().get();
            outcome.applyTo(entry.getKey(), nowMs);
            log.info("Step {} finished as {}", entry.getKey().id(), outcome.status());
            entries.remove();
        }
    }

    private void cancelUnfinishedSteps() {
        long nowMs = Workflow.currentTimeMillis();
        for (StepRun step : steps.values()) {
            if (step.status() == StepStatus.RUNNING) {
                step.markCancelled(nowMs, StepOutcome.CANCELLED_WHILE_RUNNING);
            } else if (step.status() == StepStatus.PENDING) {
                step.markCancelled(nowMs, CANCELLED_BEFORE_START);
            }
        }
        log.info("Run cancelled");
    }

    private void failIfAnyStepFailed() {
        List<String> failedSteps = steps.values().stream()
                .filter(step -> step.status() == StepStatus.FAILED)
                .map(StepRun::id)
                .toList();
        if (failedSteps.isEmpty()) {
            log.info("Run succeeded");
            return;
        }
        log.info("Run failed, failed steps: {}", failedSteps);
        throw ApplicationFailure.newNonRetryableFailure("Failed steps: " + String.join(", ", failedSteps), STEPS_FAILED);
    }
}
