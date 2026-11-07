package io.workflowexecutor.definition;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One reason the request was rejected")
public record DefinitionViolation(
        @Schema(description = "Path of the offending field", example = "steps[1].dependsOn[0]") String field,
        ViolationCode code,
        @Schema(example = "Step 'process' depends on unknown step 'validte'") String message) {}
