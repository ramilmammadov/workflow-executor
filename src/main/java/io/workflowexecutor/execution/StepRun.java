package io.workflowexecutor.execution;

import io.workflowexecutor.definition.StepDefinition;

/** Mutable state of one step. Lives inside the workflow and is only touched by workflow code. */
final class StepRun {

    private final StepDefinition definition;
    private StepStatus status = StepStatus.PENDING;
    private Integer attempts;
    private Long startedAtMs;
    private Long finishedAtMs;
    private StepFailure failure;
    private String statusReason;

    StepRun(StepDefinition definition) {
        this.definition = definition;
    }

    String id() {
        return definition.id();
    }

    StepDefinition definition() {
        return definition;
    }

    StepStatus status() {
        return status;
    }

    boolean finishedWithoutSuccess() {
        return status.isFinished() && status != StepStatus.SUCCEEDED;
    }

    void markRunning(long nowMs) {
        status = StepStatus.RUNNING;
        startedAtMs = nowMs;
    }

    void markSucceeded(long nowMs, Integer attemptCount) {
        finish(StepStatus.SUCCEEDED, nowMs);
        attempts = attemptCount;
    }

    void markFailed(long nowMs, Integer attemptCount, StepFailure stepFailure) {
        finish(StepStatus.FAILED, nowMs);
        attempts = attemptCount;
        failure = stepFailure;
    }

    void markSkipped(long nowMs, String reason) {
        finish(StepStatus.SKIPPED, nowMs);
        statusReason = reason;
    }

    void markCancelled(long nowMs, String reason) {
        finish(StepStatus.CANCELLED, nowMs);
        statusReason = reason;
    }

    StepProgress toProgress() {
        return new StepProgress(
                id(),
                definition.type(),
                definition.dependsOn(),
                status,
                attempts,
                startedAtMs,
                finishedAtMs,
                failure,
                statusReason);
    }

    private void finish(StepStatus finalStatus, long nowMs) {
        status = finalStatus;
        finishedAtMs = nowMs;
    }
}
