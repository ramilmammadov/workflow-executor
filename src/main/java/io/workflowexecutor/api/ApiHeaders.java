package io.workflowexecutor.api;

/** HTTP headers that are part of the API contract. */
public final class ApiHeaders {

    /** Client-chosen key that makes a submission safe to retry. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** Set to {@code true} on a response that returns the run created by an earlier, identical request. */
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    /** Correlation id: taken from the request when valid, generated otherwise, always echoed back. */
    public static final String REQUEST_ID = "X-Request-Id";

    private ApiHeaders() {}
}
