package io.workflowexecutor.execution;

import io.workflowexecutor.definition.StepType;
import java.util.List;

/** Snapshot of one step, as returned by the workflow's progress query. Times are epoch milliseconds. */
public record StepProgress(
        String id,
        StepType type,
        List<String> dependsOn,
        StepStatus status,
        Integer attempts,
        Long startedAtMs,
        Long finishedAtMs,
        StepFailure failure,
        String statusReason) {}
