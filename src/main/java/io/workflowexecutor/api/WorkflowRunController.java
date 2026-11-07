package io.workflowexecutor.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.workflowexecutor.definition.WorkflowDefinitionRequest;
import io.workflowexecutor.run.CancellationResult;
import io.workflowexecutor.run.SubmittedRun;
import io.workflowexecutor.run.WorkflowRunService;
import io.workflowexecutor.run.WorkflowRunView;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** HTTP contract only: binds and validates the request, delegates, and picks the status code. */
@RestController
@RequestMapping(path = "/api/v1/workflow-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Workflow runs", description = "Submit a workflow definition, follow its run, cancel it")
class WorkflowRunController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    private static final String RUN_ID_DESCRIPTION = "Run id returned on submission";

    private final WorkflowRunService service;

    WorkflowRunController(WorkflowRunService service) {
        this.service = service;
    }

    @Operation(
            summary = "Submit a workflow definition and start a run",
            description = "Validates the definition and starts it. The run continues in the background; "
                    + "follow it at the Location URL.")
    @ApiResponse(responseCode = "202", description = "Run started")
    @ApiResponse(
            responseCode = "200",
            description = "Replay: an earlier request with the same Idempotency-Key and body created this run")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid definition, malformed body or invalid Idempotency-Key",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "413",
            description = "The request body is over the size limit",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The Idempotency-Key was already used with a different body",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporal is unreachable; retry later with the same Idempotency-Key",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowRunView> submit(
            @Parameter(description = "Any client-chosen string, up to 255 printable ASCII characters. "
                            + "Repeating a request with the same key and body returns the original run.")
                    @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false)
                    String idempotencyKey,
            @Valid @RequestBody WorkflowDefinitionRequest request) {
        SubmittedRun submitted = service.submit(request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{runId}")
                .buildAndExpand(submitted.run().id())
                .toUri();
        if (submitted.replayed()) {
            return ResponseEntity.ok()
                    .location(location)
                    .header(ApiHeaders.IDEMPOTENT_REPLAYED, Boolean.TRUE.toString())
                    .body(submitted.run());
        }
        return ResponseEntity.accepted().location(location).body(submitted.run());
    }

    @Operation(summary = "Get a run and the state of every step")
    @ApiResponse(responseCode = "200", description = "Current state of the run")
    @ApiResponse(
            responseCode = "404",
            description = "No run with this id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporal, or a worker to answer the progress query, is unreachable",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{runId}")
    WorkflowRunView get(@Parameter(description = RUN_ID_DESCRIPTION) @PathVariable String runId) {
        return service.get(runId);
    }

    @Operation(
            summary = "Cancel a run",
            description = "Cancellation is asynchronous: running steps are asked to stop and steps that have not "
                    + "started never start. Poll the run to see it reach CANCELLED.")
    @ApiResponse(responseCode = "202", description = "Cancellation requested")
    @ApiResponse(responseCode = "200", description = "The run was already cancelled")
    @ApiResponse(
            responseCode = "404",
            description = "No run with this id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The run already finished",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Temporal is unreachable",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{runId}/cancel")
    ResponseEntity<WorkflowRunView> cancel(@Parameter(description = RUN_ID_DESCRIPTION) @PathVariable String runId) {
        CancellationResult result = service.cancel(runId);
        if (result.alreadyCancelled()) {
            return ResponseEntity.ok(result.run());
        }
        return ResponseEntity.accepted().body(result.run());
    }
}
