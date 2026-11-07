package io.workflowexecutor.definition;

public record TaskSpec(long timeoutMs, RetrySpec retry, Simulation simulation) {}
