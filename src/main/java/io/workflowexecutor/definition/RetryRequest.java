package io.workflowexecutor.definition;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Retry policy of a task step")
public record RetryRequest(
        @Schema(description = "Total attempts including the first one; 1 disables retries", example = "3")
                @NotNull
                @Min(1)
                @Max(DefinitionLimits.MAX_ATTEMPTS)
                Integer maxAttempts) {}
