package io.workflowexecutor;

/** MDC keys shared by the HTTP layer, the workflow and the activities. */
public final class LogKeys {

    public static final String REQUEST_ID = "requestId";

    /**
     * The Temporal SDK already puts the workflow id under this key on worker threads. The API uses the
     * same key, so one log field carries the run id from the HTTP request to the activity that ran it.
     */
    public static final String RUN_ID = "WorkflowId";

    public static final String STEP_ID = "stepId";

    private LogKeys() {}
}
