package io.workflowexecutor.execution;

/** Result of a successful task activity: the attempt that succeeded, which is also the attempt count. */
public record TaskOutcome(int attempt) {}
