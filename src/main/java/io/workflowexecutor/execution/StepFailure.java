package io.workflowexecutor.execution;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why a step failed.
 *
 * @param type failure type reported by the task, or {@code Timeout}
 * @param retryState why Temporal stopped retrying, for example {@code MAXIMUM_ATTEMPTS_REACHED}
 */
@Schema(description = "Why a step failed")
public record StepFailure(
        @Schema(example = "SimulatedTransientFailure") String type,
        @Schema(example = "Simulated transient failure on attempt 3") String message,
        @Schema(example = "MAXIMUM_ATTEMPTS_REACHED") String retryState) {}
