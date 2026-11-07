package io.workflowexecutor.definition;

/** Machine-readable reason a definition was rejected. Part of the API contract. */
public enum ViolationCode {
    INVALID_FIELD,
    UNKNOWN_FIELD,
    INVALID_VALUE,
    UNSUPPORTED_STEP_TYPE,
    MISSING_FIELD,
    FIELD_NOT_ALLOWED,
    DUPLICATE_STEP_ID,
    UNKNOWN_DEPENDENCY,
    SELF_DEPENDENCY,
    DUPLICATE_DEPENDENCY,
    DEPENDENCY_CYCLE,
    EXCEEDS_EXECUTION_TIMEOUT
}
