package io.workflowexecutor.api;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowException;
import io.workflowexecutor.LogKeys;
import io.workflowexecutor.definition.DefinitionLimits;
import io.workflowexecutor.definition.DefinitionViolation;
import io.workflowexecutor.definition.InvalidDefinitionException;
import io.workflowexecutor.definition.ViolationCode;
import io.workflowexecutor.run.IdempotencyKeyReusedException;
import io.workflowexecutor.run.InvalidIdempotencyKeyException;
import io.workflowexecutor.run.RunNotCancellableException;
import io.workflowexecutor.run.RunNotFoundException;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every failure into an RFC 9457 problem response with a stable {@code type}, the request id, and,
 * for invalid input, the list of offending fields. Each failure is logged once, here.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERRORS_PROPERTY = "errors";
    private static final String REQUEST_ID_PROPERTY = "requestId";
    private static final String RETRY_AFTER_SECONDS = "5";
    /** FAILED_PRECONDITION is what Temporal answers to a query when no worker polls the task queue. */
    private static final Set<Status.Code> UNAVAILABLE_CODES =
            EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.FAILED_PRECONDITION);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<DefinitionViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new DefinitionViolation(error.getField(), ViolationCode.INVALID_FIELD, error.getDefaultMessage()))
                .sorted(Comparator.comparing(DefinitionViolation::field))
                .toList();
        return problem(ProblemType.INVALID_DEFINITION, "The workflow definition has invalid fields", violations);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof StreamConstraintsException) {
            String detail = "The request body exceeds the size limits of this API (at most %d bytes)"
                    .formatted(DefinitionLimits.MAX_REQUEST_BYTES);
            return problem(ProblemType.REQUEST_TOO_LARGE, detail, List.of());
        }
        List<DefinitionViolation> violations = bindingViolation(cause).stream().toList();
        String detail = "The request body is not valid JSON or does not match the expected structure";
        return problem(ProblemType.MALFORMED_REQUEST, detail, violations);
    }

    /**
     * Framework errors (unknown path, wrong method or media type) keep Spring's problem body plus the request
     * id. Spring builds that body inside the super call, so the request id is added afterwards.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty(REQUEST_ID_PROPERTY, MDC.get(LogKeys.REQUEST_ID));
        }
        if (status.is5xxServerError()) {
            log.error("Request failed inside the web framework with status {}", status.value(), exception);
        } else {
            log.info("Request rejected by the web framework with status {}: {}", status.value(), exception.getMessage());
        }
        return response;
    }

    @ExceptionHandler(InvalidDefinitionException.class)
    ResponseEntity<Object> handleInvalidDefinition(InvalidDefinitionException exception) {
        String detail = "The workflow definition breaks one or more rules";
        return problem(ProblemType.INVALID_DEFINITION, detail, exception.violations());
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<Object> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException exception) {
        return problem(ProblemType.INVALID_IDEMPOTENCY_KEY, exception.getMessage(), List.of());
    }

    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<Object> handleRunNotFound(RunNotFoundException exception) {
        return problem(ProblemType.RUN_NOT_FOUND, exception.getMessage(), List.of());
    }

    @ExceptionHandler(RunNotCancellableException.class)
    ResponseEntity<Object> handleRunNotCancellable(RunNotCancellableException exception) {
        return problem(ProblemType.RUN_NOT_CANCELLABLE, exception.getMessage(), List.of());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<Object> handleIdempotencyKeyReused(IdempotencyKeyReusedException exception) {
        return problem(ProblemType.IDEMPOTENCY_KEY_REUSED, exception.getMessage(), List.of());
    }

    /** Temporal unreachable, or no worker answered a query in time: 503 with Retry-After. */
    @ExceptionHandler({WorkflowException.class, StatusRuntimeException.class})
    ResponseEntity<Object> handleTemporalFailure(RuntimeException exception) {
        Optional<Status> grpcStatus = grpcStatusOf(exception);
        if (grpcStatus.isEmpty() || !UNAVAILABLE_CODES.contains(grpcStatus.get().getCode())) {
            return handleUnexpected(exception);
        }
        log.warn("Workflow engine unavailable, answering 503: {}", describe(grpcStatus.get()));
        String detail = "The workflow engine is unavailable or did not answer in time. "
                + "Retry later; a retry with the same Idempotency-Key is safe";
        ResponseEntity<Object> response = problem(ProblemType.ENGINE_UNAVAILABLE, detail, List.of());
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(response.getBody());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception) {
        log.error("Unexpected error while handling the request", exception);
        String detail = "An unexpected error occurred. Quote the requestId when reporting it";
        return problem(ProblemType.INTERNAL_ERROR, detail, List.of());
    }

    /** At most {@link DefinitionLimits#MAX_REPORTED_VIOLATIONS} violations are listed; the detail says when more exist. */
    private ResponseEntity<Object> problem(ProblemType type, String detail, List<DefinitionViolation> violations) {
        int listed = Math.min(violations.size(), DefinitionLimits.MAX_REPORTED_VIOLATIONS);
        String fullDetail = detail;
        if (listed < violations.size()) {
            fullDetail = "%s (the first %d of %d violations are listed)".formatted(detail, listed, violations.size());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), fullDetail);
        problem.setType(type.uri());
        problem.setTitle(type.title());
        if (!violations.isEmpty()) {
            problem.setProperty(ERRORS_PROPERTY, violations.subList(0, listed));
        }
        problem.setProperty(REQUEST_ID_PROPERTY, MDC.get(LogKeys.REQUEST_ID));
        if (type.status().is4xxClientError()) {
            log.info("Request rejected: {} ({} field errors)", type.title(), violations.size());
        }
        return ResponseEntity.status(type.status()).body(problem);
    }

    private static Optional<DefinitionViolation> bindingViolation(Throwable cause) {
        if (cause instanceof UnrecognizedPropertyException unknownField) {
            String message = "Unknown field '%s'".formatted(unknownField.getPropertyName());
            return Optional.of(new DefinitionViolation(fieldPath(unknownField), ViolationCode.UNKNOWN_FIELD, message));
        }
        if (cause instanceof MismatchedInputException wrongType && wrongType.getTargetType() != null) {
            String message = "Expected a JSON " + jsonTypeName(wrongType.getTargetType());
            return Optional.of(new DefinitionViolation(fieldPath(wrongType), ViolationCode.INVALID_VALUE, message));
        }
        return Optional.empty();
    }

    private static String fieldPath(DatabindException exception) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : exception.getPath()) {
            if (reference.getPropertyName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /** Names the expected JSON type without exposing Java class names. */
    private static String jsonTypeName(Class<?> type) {
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return "number";
        }
        if (type == String.class) {
            return "string";
        }
        if (Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        return "object";
    }

    private static String describe(Status status) {
        if (status.getDescription() == null) {
            return status.getCode().name();
        }
        return status.getCode() + " (" + status.getDescription() + ")";
    }

    /** Temporal client exceptions wrap the gRPC error that says what went wrong; find it in the cause chain. */
    private static Optional<Status> grpcStatusOf(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof StatusRuntimeException grpcError) {
                return Optional.of(grpcError.getStatus());
            }
        }
        return Optional.empty();
    }
}
