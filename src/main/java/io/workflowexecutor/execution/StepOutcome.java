package io.workflowexecutor.execution;

/** How a started step ended. Built from the step's activity or timer promise, which never throws past it. */
record StepOutcome(StepStatus status, Integer attempts, StepFailure failure) {

    static final String CANCELLED_WHILE_RUNNING = "Run was cancelled while the step was running";

    static StepOutcome succeeded(Integer attempts) {
        return new StepOutcome(StepStatus.SUCCEEDED, attempts, null);
    }

    static StepOutcome failed(Integer attempts, StepFailure failure) {
        return new StepOutcome(StepStatus.FAILED, attempts, failure);
    }

    static StepOutcome cancelled() {
        return new StepOutcome(StepStatus.CANCELLED, null, null);
    }

    void applyTo(StepRun step, long nowMs) {
        switch (status) {
            case SUCCEEDED -> step.markSucceeded(nowMs, attempts);
            case FAILED -> step.markFailed(nowMs, attempts, failure);
            case CANCELLED -> step.markCancelled(nowMs, CANCELLED_WHILE_RUNNING);
            default -> throw new IllegalStateException("Not a final step status: " + status);
        }
    }
}
