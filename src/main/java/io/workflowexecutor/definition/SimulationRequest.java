package io.workflowexecutor.definition;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "Makes the simulated task slow or failing, to exercise timeouts and retries")
public record SimulationRequest(
        @Schema(description = "Simulated work per attempt in milliseconds", example = "500")
                @Min(0)
                @Max(DefinitionLimits.MAX_SIMULATED_WORK_MS)
                Long workMs,
        @Schema(description = "The first N attempts fail with a retryable error", example = "2")
                @Min(0)
                @Max(DefinitionLimits.MAX_SIMULATED_FAILURES)
                Integer failFirstAttempts,
        @Schema(description = "Every attempt fails with a non-retryable error", example = "false")
                Boolean failPermanently) {}
