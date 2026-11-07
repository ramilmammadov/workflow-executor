package io.workflowexecutor.definition;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A workflow definition as clients send it. Field-level rules live here; graph rules live in {@link DefinitionValidator}. */
@Schema(description = "Steps to execute and the dependencies between them")
public record WorkflowDefinitionRequest(
        @Schema(description = "Optional human-readable name", example = "order-fulfilment")
                @Size(max = DefinitionLimits.MAX_NAME_LENGTH)
                String name,
        @NotNull @Size(min = 1, max = DefinitionLimits.MAX_STEPS) List<@NotNull @Valid StepRequest> steps) {}
