package io.workflowexecutor.api;

import java.net.URI;
import org.springframework.http.HttpStatus;

/** Every error the API returns, as an RFC 9457 problem type. Part of the API contract. */
enum ProblemType {
    INVALID_DEFINITION(HttpStatus.BAD_REQUEST, "invalid-definition", "Invalid workflow definition"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request body"),
    REQUEST_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "request-too-large", "Request body too large"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Invalid Idempotency-Key"),
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "run-not-found", "Workflow run not found"),
    RUN_NOT_CANCELLABLE(HttpStatus.CONFLICT, "run-not-cancellable", "Workflow run cannot be cancelled"),
    IDEMPOTENCY_KEY_REUSED(
            HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", "Idempotency-Key reused with a different request"),
    ENGINE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "engine-unavailable", "Workflow engine unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error");

    private static final String URN_PREFIX = "urn:workflow-executor:problem:";

    private final HttpStatus status;
    private final URI uri;
    private final String title;

    ProblemType(HttpStatus status, String slug, String title) {
        this.status = status;
        this.uri = URI.create(URN_PREFIX + slug);
        this.title = title;
    }

    HttpStatus status() {
        return status;
    }

    URI uri() {
        return uri;
    }

    String title() {
        return title;
    }
}
