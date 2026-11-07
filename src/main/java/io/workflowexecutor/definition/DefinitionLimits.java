package io.workflowexecutor.definition;

/**
 * Limits that are part of the API contract. They are constants rather than configuration so that a
 * definition accepted by one environment is accepted by every other.
 */
public final class DefinitionLimits {

    public static final long MAX_REQUEST_BYTES = 1_000_000;
    public static final int MAX_STEPS = 100;
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_TYPE_LENGTH = 32;
    public static final String STEP_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$";
    public static final String STEP_ID_RULE =
            "must be 1 to 64 letters, digits, '.', '_' or '-', starting with a letter or digit";
    public static final int MAX_ATTEMPTS = 10;
    public static final long MIN_TIMEOUT_MS = 100;
    public static final long MAX_TIMEOUT_MS = 300_000;
    public static final long MIN_DELAY_MS = 1;
    public static final long MAX_DELAY_MS = 3_600_000;
    public static final long MAX_SIMULATED_WORK_MS = 60_000;
    public static final int MAX_SIMULATED_FAILURES = 10;
    public static final int MAX_REPORTED_VIOLATIONS = 100;

    private DefinitionLimits() {}
}
