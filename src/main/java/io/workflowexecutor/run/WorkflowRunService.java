package io.workflowexecutor.run;

import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.workflowexecutor.LogKeys;
import io.workflowexecutor.config.ExecutorProperties;
import io.workflowexecutor.config.TemporalProperties;
import io.workflowexecutor.definition.DefinitionMapper;
import io.workflowexecutor.definition.DefinitionValidator;
import io.workflowexecutor.definition.WorkflowDefinition;
import io.workflowexecutor.definition.WorkflowDefinitionRequest;
import io.workflowexecutor.execution.DagWorkflow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Starts, reads and cancels workflow runs. Temporal is the only store: a run is a workflow execution,
 * its id is the workflow id, and its state is read back from Temporal on every request.
 *
 * <p>A write that has taken effect is never reported as failed because a read after it failed. Those
 * responses fall back to what is already known instead.
 */
@Service
public class WorkflowRunService {

    static final String FINGERPRINT_MEMO = "requestFingerprint";
    static final String NAME_MEMO = "name";

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);
    private static final String CANCEL_REASON = "Cancelled through the API";

    private final WorkflowClient client;
    private final DefinitionValidator validator;
    private final DefinitionMapper mapper;
    private final TemporalProperties temporal;

    public WorkflowRunService(
            WorkflowClient client,
            DefinitionValidator validator,
            DefinitionMapper mapper,
            ExecutorProperties properties) {
        this.client = client;
        this.validator = validator;
        this.mapper = mapper;
        this.temporal = properties.temporal();
    }

    /**
     * Validates the definition and starts a run. When the request carries an Idempotency-Key that already
     * created a run, the existing run is returned instead of starting a second one.
     */
    public SubmittedRun submit(WorkflowDefinitionRequest request, String idempotencyKey) {
        String runId = idempotencyKey == null ? RunIds.random() : RunIds.fromIdempotencyKey(idempotencyKey);
        validator.validate(request);
        WorkflowDefinition definition = mapper.toDefinition(request);
        String fingerprint = RequestFingerprints.of(request);
        try (MDC.MDCCloseable runIdContext = MDC.putCloseable(LogKeys.RUN_ID, runId)) {
            return startOrReplay(runId, definition, fingerprint);
        }
    }

    public WorkflowRunView get(String runId) {
        requireWellFormed(runId);
        try (MDC.MDCCloseable runIdContext = MDC.putCloseable(LogKeys.RUN_ID, runId)) {
            return currentView(describe(runId));
        }
    }

    /** Requests cancellation. Temporal delivers it to the workflow, which cancels the steps still running. */
    public CancellationResult cancel(String runId) {
        requireWellFormed(runId);
        try (MDC.MDCCloseable runIdContext = MDC.putCloseable(LogKeys.RUN_ID, runId)) {
            WorkflowExecutionDescription description = describe(runId);
            if (RunStatus.from(description.getStatus()) == RunStatus.CANCELLED) {
                return new CancellationResult(bestEffortView(description), true);
            }
            requireCancellable(description);
            requestCancellation(runId);
            return new CancellationResult(viewAfterCancellation(runId, description), false);
        }
    }

    private SubmittedRun startOrReplay(String runId, WorkflowDefinition definition, String fingerprint) {
        DagWorkflow workflow = client.newWorkflowStub(DagWorkflow.class, workflowOptions(runId, definition, fingerprint));
        try {
            WorkflowClient.start(workflow::execute, definition);
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            return new SubmittedRun(existingRun(runId, fingerprint), true);
        }
        log.info("Run started with {} steps", definition.steps().size());
        List<StepView> steps = definition.steps().stream().map(StepView::pending).toList();
        return new SubmittedRun(
                new WorkflowRunView(runId, definition.name(), RunStatus.RUNNING, creationTimeOf(runId), null, steps),
                false);
    }

    /**
     * REJECT_DUPLICATE keeps a workflow id unusable after its run has closed, for as long as Temporal
     * retains the run. That retention period is how long an Idempotency-Key is remembered.
     */
    private WorkflowOptions workflowOptions(String runId, WorkflowDefinition definition, String fingerprint) {
        Map<String, Object> memo = new HashMap<>();
        memo.put(FINGERPRINT_MEMO, fingerprint);
        WorkflowOptions.Builder options = WorkflowOptions.newBuilder()
                .setWorkflowId(runId)
                .setTaskQueue(temporal.taskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setWorkflowExecutionTimeout(temporal.executionTimeout());
        if (definition.name() != null) {
            memo.put(NAME_MEMO, definition.name());
            options.setStaticSummary(definition.name());
        }
        return options.setMemo(memo).build();
    }

    private WorkflowRunView existingRun(String runId, String fingerprint) {
        WorkflowExecutionDescription description = describe(runId);
        String storedFingerprint = description.getMemo(FINGERPRINT_MEMO, String.class, String.class);
        if (!fingerprint.equals(storedFingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        log.info("Idempotent replay: returning the run created by the first request");
        return bestEffortView(description);
    }

    /** The run has already started; if reading its creation time fails, the time is left out of the answer. */
    private Instant creationTimeOf(String runId) {
        try {
            return toMillis(describe(runId).getStartTime());
        } catch (WorkflowException | StatusRuntimeException readFailed) {
            log.warn("Run started, but reading its creation time failed: {}", readFailed.getMessage());
            return null;
        }
    }

    private void requestCancellation(String runId) {
        try {
            client.newUntypedWorkflowStub(runId).cancel(CANCEL_REASON);
        } catch (WorkflowNotFoundException removedMeanwhile) {
            throw new RunNotFoundException();
        }
        log.info("Cancellation requested");
    }

    /**
     * Temporal accepts, and ignores, a cancellation request for a run that has just finished, so the status is
     * read again. The cancellation is already recorded, so if that read fails the answer uses the earlier one.
     */
    private WorkflowRunView viewAfterCancellation(String runId, WorkflowExecutionDescription before) {
        WorkflowExecutionDescription after;
        try {
            after = describe(runId);
        } catch (WorkflowException | StatusRuntimeException readFailed) {
            log.warn("Cancellation requested, but reading the run back failed: {}", readFailed.getMessage());
            return view(before, null);
        }
        requireCancellable(after);
        return bestEffortView(after);
    }

    private static void requireCancellable(WorkflowExecutionDescription description) {
        RunStatus status = RunStatus.from(description.getStatus());
        if (status.isFinished() && status != RunStatus.CANCELLED) {
            throw new RunNotCancellableException(status);
        }
    }

    private WorkflowExecutionDescription describe(String runId) {
        WorkflowExecutionDescription description;
        try {
            description = client.newUntypedWorkflowStub(runId).describe();
        } catch (WorkflowNotFoundException notFound) {
            throw new RunNotFoundException();
        }
        if (!DagWorkflow.TYPE.equals(description.getWorkflowType())) {
            throw new RunNotFoundException();
        }
        return description;
    }

    /**
     * Steps come from the workflow's progress query, which a worker answers from the run's history. Temporal's
     * Java SDK cannot rebuild the state of a run that an operator terminated, so such a run has no step detail.
     */
    private WorkflowRunView currentView(WorkflowExecutionDescription description) {
        if (RunStatus.from(description.getStatus()) == RunStatus.TERMINATED) {
            return view(description, null);
        }
        String runId = description.getExecution().getWorkflowId();
        List<StepView> steps = client.newWorkflowStub(DagWorkflow.class, runId).progress().steps().stream()
                .map(StepView::from)
                .toList();
        return view(description, steps);
    }

    /** For answers to a write that has taken effect: when no worker answers the query, steps are left out. */
    private WorkflowRunView bestEffortView(WorkflowExecutionDescription description) {
        try {
            return currentView(description);
        } catch (WorkflowException | StatusRuntimeException queryFailed) {
            log.warn("Progress query failed, answering without step detail: {}", queryFailed.getMessage());
            return view(description, null);
        }
    }

    /**
     * A run can end without its workflow code finishing, for example when it times out or is terminated. The
     * steps that were still open then stopped with the run, and are shown that way.
     */
    private static WorkflowRunView view(WorkflowExecutionDescription description, List<StepView> steps) {
        RunStatus status = RunStatus.from(description.getStatus());
        Instant closedAt = toMillis(description.getCloseTime());
        List<StepView> shownSteps = steps;
        if (steps != null && status.isFinished()) {
            shownSteps = steps.stream().map(step -> step.stoppedWithRun(status, closedAt)).toList();
        }
        return new WorkflowRunView(
                description.getExecution().getWorkflowId(),
                description.getMemo(NAME_MEMO, String.class, String.class),
                status,
                toMillis(description.getStartTime()),
                closedAt,
                shownSteps);
    }

    /** Step times have millisecond precision, so run times are given the same precision. */
    private static Instant toMillis(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireWellFormed(String runId) {
        if (!RunIds.isWellFormed(runId)) {
            throw new RunNotFoundException();
        }
    }
}
