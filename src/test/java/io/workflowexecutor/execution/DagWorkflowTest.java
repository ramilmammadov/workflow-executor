package io.workflowexecutor.execution;

import static io.workflowexecutor.TestDefinitions.MAPPER;
import static io.workflowexecutor.TestDefinitions.TASK_QUEUE;
import static io.workflowexecutor.TestDefinitions.definition;
import static io.workflowexecutor.TestDefinitions.delay;
import static io.workflowexecutor.TestDefinitions.flakyTask;
import static io.workflowexecutor.TestDefinitions.permanentlyFailingTask;
import static io.workflowexecutor.TestDefinitions.slowTask;
import static io.workflowexecutor.TestDefinitions.task;
import static io.workflowexecutor.TestDefinitions.workingTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.PendingActivityState;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.workflowexecutor.definition.WorkflowDefinitionRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the workflow and the simulated activities on Temporal's in-memory test server, with time skipping. */
class DagWorkflowTest {

    private static final long ONE_HOUR_MS = Duration.ofHours(1).toMillis();
    private static final long TEN_MINUTES_MS = Duration.ofMinutes(10).toMillis();

    private TestWorkflowEnvironment testEnvironment;
    private WorkflowClient client;

    @BeforeEach
    void startTestServer() {
        testEnvironment = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnvironment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DagWorkflowImpl.class);
        worker.registerActivitiesImplementations(new SimulatedTaskActivities());
        testEnvironment.start();
        client = testEnvironment.getWorkflowClient();
    }

    @AfterEach
    void stopTestServer() {
        testEnvironment.close();
    }

    /** charge works longer than reserve, so notify must wait for the later of its two dependencies. */
    @Test
    void runsEachStepAfterItsDependenciesAndStartsIndependentStepsTogether() {
        RunProgress progress = runToSuccess(definition(
                task("validate"),
                workingTask("charge", 600, "validate"),
                workingTask("reserve", 100, "validate"),
                task("notify", "charge", "reserve")));

        assertThat(progress.steps()).extracting(StepProgress::status).containsOnly(StepStatus.SUCCEEDED);
        StepProgress validate = step(progress, "validate");
        StepProgress charge = step(progress, "charge");
        StepProgress reserve = step(progress, "reserve");
        StepProgress notify = step(progress, "notify");
        assertThat(charge.startedAtMs()).isEqualTo(reserve.startedAtMs()).isGreaterThanOrEqualTo(validate.finishedAtMs());
        assertThat(charge.finishedAtMs()).isGreaterThan(reserve.finishedAtMs());
        assertThat(notify.startedAtMs()).isGreaterThanOrEqualTo(charge.finishedAtMs());
    }

    @Test
    void retriesATransientFailureUntilAnAttemptSucceeds() {
        StepProgress charge = step(runToSuccess(definition(flakyTask("charge", 2, 3))), "charge");

        assertThat(charge.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(charge.attempts()).isEqualTo(3);
        assertThat(charge.failure()).isNull();
    }

    @Test
    void failsAStepWhoseAttemptsRunOutAndSkipsEverythingThatDependsOnIt() {
        RunProgress progress = runToFailure(
                definition(flakyTask("charge", 5, 2), task("ship", "charge"), task("invoice", "ship"), task("audit")));

        StepProgress charge = step(progress, "charge");
        assertThat(charge.status()).isEqualTo(StepStatus.FAILED);
        assertThat(charge.attempts()).isEqualTo(2);
        assertThat(charge.failure())
                .isEqualTo(new StepFailure(
                        SimulatedTaskActivities.TRANSIENT_FAILURE,
                        "Simulated transient failure on attempt 2",
                        "MAXIMUM_ATTEMPTS_REACHED"));
        assertThat(step(progress, "ship"))
                .extracting(StepProgress::status, StepProgress::statusReason)
                .containsExactly(StepStatus.SKIPPED, "Dependency 'charge' finished as FAILED");
        assertThat(step(progress, "invoice"))
                .extracting(StepProgress::status, StepProgress::statusReason)
                .containsExactly(StepStatus.SKIPPED, "Dependency 'ship' finished as SKIPPED");
        assertThat(step(progress, "audit").status()).isEqualTo(StepStatus.SUCCEEDED);
    }

    @Test
    void doesNotRetryANonRetryableFailure() {
        StepProgress charge = step(runToFailure(definition(permanentlyFailingTask("charge"))), "charge");

        assertThat(charge.attempts()).isEqualTo(1);
        assertThat(charge.failure())
                .isEqualTo(new StepFailure(
                        SimulatedTaskActivities.PERMANENT_FAILURE, "Simulated permanent failure", "NON_RETRYABLE_FAILURE"));
    }

    @Test
    void failsAnAttemptThatRunsLongerThanItsTimeout() {
        StepProgress export = step(runToFailure(definition(slowTask("export", 2_000, 200, 2))), "export");

        assertThat(export.attempts()).isEqualTo(2);
        assertThat(export.failure())
                .isEqualTo(new StepFailure(
                        StepFailures.TIMEOUT, "Attempt exceeded its START_TO_CLOSE timeout", "MAXIMUM_ATTEMPTS_REACHED"));
    }

    @Test
    void waitsForADelayStepOnADurableTimer() {
        RunProgress progress = runToSuccess(definition(delay("cool-down", TEN_MINUTES_MS), task("notify", "cool-down")));

        StepProgress coolDown = step(progress, "cool-down");
        assertThat(coolDown.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(coolDown.attempts()).isNull();
        assertThat(coolDown.finishedAtMs() - coolDown.startedAtMs()).isGreaterThanOrEqualTo(TEN_MINUTES_MS);
        assertThat(step(progress, "notify").startedAtMs()).isGreaterThanOrEqualTo(coolDown.finishedAtMs());
    }

    @Test
    void cancellationStopsTheRunningStepAndNeverStartsThePendingOnes() {
        DagWorkflow workflow = start(definition(delay("wait", ONE_HOUR_MS), task("notify", "wait")));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> step(workflow.progress(), "wait").status() == StepStatus.RUNNING);

        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        stub.cancel();

        WorkflowFailedException failure = catchThrowableOfType(WorkflowFailedException.class, () -> stub.getResult(Void.class));
        assertThat(failure).hasCauseInstanceOf(CanceledFailure.class);
        RunProgress progress = workflow.progress();
        assertThat(step(progress, "wait"))
                .extracting(StepProgress::status, StepProgress::statusReason)
                .containsExactly(StepStatus.CANCELLED, "Run was cancelled while the step was running");
        assertThat(step(progress, "notify"))
                .extracting(StepProgress::status, StepProgress::statusReason)
                .containsExactly(StepStatus.CANCELLED, "Run was cancelled before the step started");
    }

    @Test
    void cancellationStopsARunningTaskBeforeItFinishes() {
        DagWorkflow workflow = start(definition(slowTask("export", 20_000, 60_000, 1)));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> step(workflow.progress(), "export").status() == StepStatus.RUNNING);
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        long cancelledAtNanos = System.nanoTime();

        stub.cancel();

        catchThrowableOfType(WorkflowFailedException.class, () -> stub.getResult(Void.class));
        assertThat(Duration.ofNanos(System.nanoTime() - cancelledAtNanos)).isLessThan(Duration.ofSeconds(15));
        assertThat(step(workflow.progress(), "export"))
                .extracting(StepProgress::status, StepProgress::statusReason)
                .containsExactly(StepStatus.CANCELLED, "Run was cancelled while the step was running");
    }

    /** The delay stops at once and the task at its next heartbeat; the run ends only after both have stopped. */
    @Test
    void cancellationEndsTheRunOnlyAfterEveryRunningStepHasStopped() {
        DagWorkflow workflow = start(definition(delay("wait", ONE_HOUR_MS), slowTask("export", 20_000, 60_000, 1)));
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        await().atMost(Duration.ofSeconds(10)).until(() -> taskIsExecutingOnAWorker(stub));

        stub.cancel();

        catchThrowableOfType(WorkflowFailedException.class, () -> stub.getResult(Void.class));
        assertThat(workflow.progress().steps())
                .extracting(StepProgress::status, StepProgress::statusReason)
                .containsOnly(tuple(StepStatus.CANCELLED, "Run was cancelled while the step was running"));
        List<EventType> events = testEnvironment.getWorkflowExecutionHistory(stub.getExecution()).getEvents().stream()
                .map(HistoryEvent::getEventType)
                .toList();
        assertThat(events.indexOf(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED))
                .isNotNegative()
                .isLessThan(events.indexOf(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED));
    }

    @Test
    void cancellationBeforeTheRunBeginsLeavesEveryStepUnstarted() {
        try (TestWorkflowEnvironment notYetPolling = TestWorkflowEnvironment.newInstance()) {
            Worker worker = notYetPolling.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(DagWorkflowImpl.class);
            worker.registerActivitiesImplementations(new SimulatedTaskActivities());
            DagWorkflow workflow = newWorkflow(notYetPolling.getWorkflowClient());
            WorkflowClient.start(workflow::execute, MAPPER.toDefinition(definition(task("validate"), task("ship", "validate"))));
            WorkflowStub stub = WorkflowStub.fromTyped(workflow);
            stub.cancel();

            notYetPolling.start();

            catchThrowableOfType(WorkflowFailedException.class, () -> stub.getResult(Void.class));
            assertThat(workflow.progress().steps())
                    .extracting(StepProgress::status, StepProgress::statusReason, StepProgress::startedAtMs)
                    .containsOnly(tuple(StepStatus.CANCELLED, "Run was cancelled before the step started", null));
        }
    }

    /** Replaying a recorded history fails if the workflow code made a different decision the second time. */
    @Test
    void replaysARecordedHistoryWithoutNonDeterminism() {
        DagWorkflow workflow = start(definition(
                task("a"),
                flakyTask("b", 1, 3, "a"),
                task("c", "a"),
                delay("d", 1_000, "b", "c"),
                permanentlyFailingTask("e"),
                task("f", "e")));
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        catchThrowableOfType(WorkflowFailedException.class, () -> stub.getResult(Void.class));

        WorkflowExecutionHistory history = testEnvironment.getWorkflowExecutionHistory(stub.getExecution());

        assertThatCode(() -> WorkflowReplayer.replayWorkflowExecution(history, DagWorkflowImpl.class))
                .doesNotThrowAnyException();
    }

    private DagWorkflow start(WorkflowDefinitionRequest request) {
        DagWorkflow workflow = newWorkflow(client);
        WorkflowClient.start(workflow::execute, MAPPER.toDefinition(request));
        return workflow;
    }

    private static DagWorkflow newWorkflow(WorkflowClient workflowClient) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(UUID.randomUUID().toString())
                .build();
        return workflowClient.newWorkflowStub(DagWorkflow.class, options);
    }

    private RunProgress runToSuccess(WorkflowDefinitionRequest request) {
        DagWorkflow workflow = start(request);
        WorkflowStub.fromTyped(workflow).getResult(Void.class);
        return workflow.progress();
    }

    private RunProgress runToFailure(WorkflowDefinitionRequest request) {
        DagWorkflow workflow = start(request);
        WorkflowFailedException failure =
                catchThrowableOfType(WorkflowFailedException.class, () -> WorkflowStub.fromTyped(workflow).getResult(Void.class));
        assertThat(failure.getCause())
                .isInstanceOfSatisfying(
                        ApplicationFailure.class, cause -> assertThat(cause.getType()).isEqualTo(DagWorkflow.STEPS_FAILED));
        return workflow.progress();
    }

    /** Scheduled is not enough: Temporal cancels a task that no worker has picked up yet without asking a worker. */
    private static boolean taskIsExecutingOnAWorker(WorkflowStub stub) {
        return stub.describe().getRawDescription().getPendingActivitiesList().stream()
                .anyMatch(activity -> activity.getState() == PendingActivityState.PENDING_ACTIVITY_STATE_STARTED);
    }

    private static StepProgress step(RunProgress progress, String stepId) {
        return progress.steps().stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst()
                .orElseThrow();
    }
}
