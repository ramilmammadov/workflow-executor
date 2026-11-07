package io.workflowexecutor.execution;

import io.workflowexecutor.definition.Simulation;

/**
 * Input of one task activity.
 *
 * @param idempotencyKey stable across retries of the same step ({@code <runId>:<stepId>}). Temporal runs
 *     activities at least once, so a real task would use this key to make its side effects idempotent.
 */
public record TaskInvocation(String stepId, String idempotencyKey, Simulation simulation) {}
