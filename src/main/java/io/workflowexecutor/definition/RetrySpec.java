package io.workflowexecutor.definition;

public record RetrySpec(int maxAttempts, long initialIntervalMs, double backoffCoefficient, long maxIntervalMs) {}
