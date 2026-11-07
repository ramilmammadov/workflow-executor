package io.workflowexecutor.execution;

public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean isFinished() {
        return this != PENDING && this != RUNNING;
    }
}
