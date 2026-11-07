package io.workflowexecutor.definition;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "One step of a workflow")
public record StepRequest(
        @Schema(description = "Unique within the workflow", example = "process")
                @NotNull
                @Pattern(regexp = DefinitionLimits.STEP_ID_PATTERN, message = DefinitionLimits.STEP_ID_RULE)
                String id,
        @Schema(description = "Step kind", allowableValues = {"task", "delay"}, example = "task")
                @NotNull
                @Size(max = DefinitionLimits.MAX_TYPE_LENGTH)
                String type,
        @Schema(description = "Ids of steps that must succeed before this one starts", example = "[\"validate\"]")
                @Size(max = DefinitionLimits.MAX_STEPS)
                List<@NotNull @Pattern(regexp = DefinitionLimits.STEP_ID_PATTERN, message = DefinitionLimits.STEP_ID_RULE)
                        String> dependsOn,
        @Schema(description = "task only: retry policy; defaults come from server configuration") @Valid
                RetryRequest retry,
        @Schema(description = "task only: timeout of a single attempt in milliseconds", example = "10000")
                @Min(DefinitionLimits.MIN_TIMEOUT_MS)
                @Max(DefinitionLimits.MAX_TIMEOUT_MS)
                Long timeoutMs,
        @Schema(description = "delay only: how long to wait in milliseconds", example = "2000")
                @Min(DefinitionLimits.MIN_DELAY_MS)
                @Max(DefinitionLimits.MAX_DELAY_MS)
                Long delayMs,
        @Schema(description = "task only: knobs for the simulated task executor") @Valid
                SimulationRequest simulate) {}
