package io.workflowexecutor.run;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "A workflow run and the state of each of its steps")
public record WorkflowRunView(
        @Schema(description = "Run id, also used as the Temporal workflow id", example = "3f2c8f5e-6a3b-3c1d-9e7f-2b4a6c8d0e1f")
                String id,
        @Schema(example = "order-fulfilment") String name,
        RunStatus status,
        Instant createdAt,
        @Schema(description = "Set once the run has finished") Instant closedAt,
        @Schema(description = "Every step in definition order. Absent for a TERMINATED run, and in the answer to a "
                        + "write (a replay or a cancellation) when no worker could report step state.")
                List<StepView> steps) {}
