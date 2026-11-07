package io.workflowexecutor.run;

import static io.workflowexecutor.TestDefinitions.MAPPER;
import static io.workflowexecutor.TestDefinitions.PROPERTIES;
import static io.workflowexecutor.TestDefinitions.TASK_QUEUE;
import static io.workflowexecutor.TestDefinitions.VALIDATOR;
import static io.workflowexecutor.TestDefinitions.definition;
import static io.workflowexecutor.TestDefinitions.delay;
import static io.workflowexecutor.TestDefinitions.flakyTask;
import static io.workflowexecutor.TestDefinitions.permanentlyFailingTask;
import static io.workflowexecutor.TestDefinitions.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.workflowexecutor.definition.InvalidDefinitionException;
import io.workflowexecutor.definition.WorkflowDefinitionRequest;
import io.workflowexecutor.execution.DagWorkflow;
import io.workflowexecutor.execution.DagWorkflowImpl;
import io.workflowexecutor.execution.SimulatedTaskActivities;
import io.workflowexecutor.execution.StepStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The service against Temporal's in-memory test server: idempotency, reading runs back and cancellation. */
class WorkflowRunServiceTest {

    private static final long FIVE_MINUTES_MS = Duration.ofMinutes(5).toMillis();
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    private TestWorkflowEnvironment testEnvironment;
    private WorkflowRunService service;

    @BeforeEach
    void startTestServer() {
        testEnvironment = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnvironment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DagWorkflowImpl.class);
        worker.registerActivitiesImplementations(new SimulatedTaskActivities());
        testEnvironment.start();
        service = new WorkflowRunService(testEnvironment.getWorkflowClient(), VALIDATOR, MAPPER, PROPERTIES);
    }

    @AfterEach
    void stopTestServer() {
        testEnvironment.close();
    }

    @Test
    void submitStartsARunAndReturnsItsStepsAsPending() {
        WorkflowDefinitionRequest request =
                new WorkflowDefinitionRequest("nightly-export", List.of(delay("wait", FIVE_MINUTES_MS), task("export", "wait")));

        SubmittedRun submitted = service.submit(request, null);

        assertThat(submitted.replayed()).isFalse();
        WorkflowRunView run = submitted.run();
        assertThat(run.name()).isEqualTo("nightly-export");
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.createdAt()).isNotNull();
        assertThat(run.closedAt()).isNull();
        assertThat(run.steps())
                .extracting(StepView::id, StepView::status)
                .containsExactly(tuple("wait", StepStatus.PENDING), tuple("export", StepStatus.PENDING));
    }

    @Test
    void getReturnsAFinishedRunWithTheOutcomeOfEveryStep() {
        String runId = service.submit(definition(task("validate"), flakyTask("charge", 1, 3, "validate")), null)
                .run()
                .id();

        WorkflowRunView run = awaitFinished(runId);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.closedAt()).isNotNull();
        assertThat(run.steps())
                .extracting(StepView::id, StepView::status, StepView::attempts)
                .containsExactly(tuple("validate", StepStatus.SUCCEEDED, 1), tuple("charge", StepStatus.SUCCEEDED, 2));
    }

    @Test
    void getReportsAFailedRun() {
        String runId = service.submit(definition(permanentlyFailingTask("charge"), task("ship", "charge")), null)
                .run()
                .id();

        WorkflowRunView run = awaitFinished(runId);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps()).extracting(StepView::status).containsExactly(StepStatus.FAILED, StepStatus.SKIPPED);
    }

    @Test
    void submitWithoutAnIdempotencyKeyStartsANewRunEveryTime() {
        WorkflowDefinitionRequest request = definition(task("a"));

        String first = service.submit(request, null).run().id();
        String second = service.submit(request, null).run().id();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void repeatingASubmissionWithTheSameKeyReturnsTheOriginalRun() {
        WorkflowDefinitionRequest request = definition(task("a"));

        SubmittedRun first = service.submit(request, "order-42");
        SubmittedRun second = service.submit(request, "order-42");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.run().id()).isEqualTo(first.run().id());
    }

    @Test
    void reusingAKeyForADifferentDefinitionIsRejected() {
        service.submit(definition(task("a")), "order-43");

        assertThatThrownBy(() -> service.submit(definition(task("b")), "order-43"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void concurrentSubmissionsWithTheSameKeyStartExactlyOneRun() throws Exception {
        int parallelRequests = 8;
        WorkflowDefinitionRequest request = definition(task("a"));
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<SubmittedRun>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(parallelRequests)) {
            for (int index = 0; index < parallelRequests; index++) {
                futures.add(executor.submit(() -> {
                    startTogether.await();
                    return service.submit(request, "order-44");
                }));
            }
            startTogether.countDown();
        }

        List<SubmittedRun> results = new ArrayList<>();
        for (Future<SubmittedRun> future : futures) {
            results.add(future.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS));
        }
        assertThat(results).extracting(result -> result.run().id()).containsOnly(results.getFirst().run().id());
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
    }

    @Test
    void rejectsAnIdempotencyKeyWithSpaces() {
        assertThatThrownBy(() -> service.submit(definition(task("a")), "order 45"))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void rejectsAnInvalidDefinitionBeforeStartingAnything() {
        assertThatThrownBy(() -> service.submit(definition(task("a", "ghost")), "order-46"))
                .isInstanceOf(InvalidDefinitionException.class);

        assertThat(service.submit(definition(task("a")), "order-46").replayed()).isFalse();
    }

    @Test
    void getRejectsUnknownAndMalformedRunIds() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID().toString())).isInstanceOf(RunNotFoundException.class);
        assertThatThrownBy(() -> service.get("not-a-run-id")).isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void cancellingARunningRunCancelsItsSteps() {
        String runId = service.submit(definition(delay("wait", FIVE_MINUTES_MS), task("export", "wait")), null)
                .run()
                .id();
        await().atMost(WAIT_LIMIT).until(() -> service.get(runId).steps().getFirst().status() == StepStatus.RUNNING);

        CancellationResult result = service.cancel(runId);

        assertThat(result.alreadyCancelled()).isFalse();
        WorkflowRunView run = awaitFinished(runId);
        assertThat(run.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.steps()).extracting(StepView::status).containsOnly(StepStatus.CANCELLED);
        assertThat(service.cancel(runId).alreadyCancelled()).isTrue();
    }

    /** Temporal can end a run without its workflow code finishing; the steps still open then stopped with it. */
    @Test
    void showsTheOpenStepsOfATimedOutRunAsStoppedWithIt() {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(UUID.randomUUID().toString())
                .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                .build();
        DagWorkflow workflow = testEnvironment.getWorkflowClient().newWorkflowStub(DagWorkflow.class, options);
        WorkflowClient.start(workflow::execute, MAPPER.toDefinition(definition(delay("wait", FIVE_MINUTES_MS), task("export", "wait"))));
        String runId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();

        testEnvironment.sleep(Duration.ofMinutes(2));

        WorkflowRunView run = awaitFinished(runId);
        assertThat(run.status()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(run.steps())
                .extracting(StepView::status, StepView::statusReason, StepView::finishedAt)
                .containsOnly(tuple(
                        StepStatus.CANCELLED, "The run ended as TIMED_OUT before the step finished", run.closedAt()));
    }

    @Test
    void showsATerminatedRunWithoutStepDetail() {
        String runId = service.submit(definition(delay("wait", FIVE_MINUTES_MS), task("export", "wait")), null)
                .run()
                .id();
        await().atMost(WAIT_LIMIT).until(() -> service.get(runId).steps().getFirst().status() == StepStatus.RUNNING);

        testEnvironment.getWorkflowClient().newUntypedWorkflowStub(runId).terminate("operator decision");

        WorkflowRunView run = awaitFinished(runId);
        assertThat(run.status()).isEqualTo(RunStatus.TERMINATED);
        assertThat(run.closedAt()).isNotNull();
        assertThat(run.steps()).isNull();
    }

    @Test
    void cancellingAFinishedRunIsRejected() {
        String runId = service.submit(definition(task("a")), null).run().id();
        awaitFinished(runId);

        assertThatThrownBy(() -> service.cancel(runId))
                .isInstanceOf(RunNotCancellableException.class)
                .hasMessageContaining("SUCCEEDED");
    }

    private WorkflowRunView awaitFinished(String runId) {
        return await().atMost(WAIT_LIMIT).until(() -> service.get(runId), run -> run.status().isFinished());
    }
}
