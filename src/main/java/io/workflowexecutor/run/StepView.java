package io.workflowexecutor.run;

import io.swagger.v3.oas.annotations.media.Schema;
import io.workflowexecutor.definition.StepDefinition;
import io.workflowexecutor.definition.StepType;
import io.workflowexecutor.execution.StepFailure;
import io.workflowexecutor.execution.StepProgress;
import io.workflowexecutor.execution.StepStatus;
import java.time.Instant;
import java.util.List;

@Schema(description = "State of one step")
public record StepView(
        String id,
        StepType type,
        List<String> dependsOn,
        StepStatus status,
        @Schema(description = "Attempts made by a task step; set once the step has finished", example = "3")
                Integer attempts,
        Instant startedAt,
        Instant finishedAt,
        @Schema(description = "Set when the step failed") StepFailure failure,
        @Schema(description = "Why the step was skipped or cancelled", example = "Dependency 'charge' finished as FAILED")
                String statusReason) {

    static StepView pending(StepDefinition step) {
        return new StepView(step.id(), step.type(), step.dependsOn(), StepStatus.PENDING, null, null, null, null, null);
    }

    /** A step that was still open when its run ended stopped with the run. */
    StepView stoppedWithRun(RunStatus runStatus, Instant runClosedAt) {
        if (status.isFinished()) {
            return this;
        }
        String reason = "The run ended as %s before the step finished".formatted(runStatus);
        return new StepView(id, type, dependsOn, StepStatus.CANCELLED, attempts, startedAt, runClosedAt, failure, reason);
    }

    static StepView from(StepProgress step) {
        return new StepView(
                step.id(),
                step.type(),
                step.dependsOn(),
                step.status(),
                step.attempts(),
                toInstant(step.startedAtMs()),
                toInstant(step.finishedAtMs()),
                step.failure(),
                step.statusReason());
    }

    private static Instant toInstant(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return Instant.ofEpochMilli(epochMillis);
    }
}
