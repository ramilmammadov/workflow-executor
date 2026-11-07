package io.workflowexecutor.run;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;

/** Status of a run, derived from the status of its Temporal workflow execution. */
public enum RunStatus {
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TERMINATED,
    TIMED_OUT;

    public boolean isFinished() {
        return this != RUNNING && this != PAUSED;
    }

    static RunStatus from(WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING -> RUNNING;
            case WORKFLOW_EXECUTION_STATUS_PAUSED -> PAUSED;
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> SUCCEEDED;
            case WORKFLOW_EXECUTION_STATUS_FAILED -> FAILED;
            case WORKFLOW_EXECUTION_STATUS_CANCELED -> CANCELLED;
            case WORKFLOW_EXECUTION_STATUS_TERMINATED -> TERMINATED;
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> TIMED_OUT;
            case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW, WORKFLOW_EXECUTION_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalStateException("Unexpected workflow execution status: " + status);
        };
    }
}
