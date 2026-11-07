package io.workflowexecutor.execution;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.workflowexecutor.definition.WorkflowDefinition;

/** Runs the steps of a definition in dependency order. One Temporal workflow execution per run. */
@WorkflowInterface
public interface DagWorkflow {

    /** Workflow type name. Fixed explicitly because it is recorded in the history of every run. */
    String TYPE = "DagWorkflow";

    /** Failure type of a run that finished with at least one failed step. */
    String STEPS_FAILED = "StepsFailed";

    /**
     * Completes normally when every step succeeded, fails with {@link #STEPS_FAILED} when a step failed,
     * and ends as cancelled when the run is cancelled.
     */
    @WorkflowMethod(name = TYPE)
    void execute(WorkflowDefinition definition);

    /** Read-only view of every step. Works while the run is in progress and after it has finished. */
    @QueryMethod(name = "progress")
    RunProgress progress();
}
