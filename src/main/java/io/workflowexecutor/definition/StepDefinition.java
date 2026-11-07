package io.workflowexecutor.definition;

import java.util.List;

/**
 * One step with defaults resolved. {@code task} is set only for {@link StepType#TASK} steps and
 * {@code delayMs} is meaningful only for {@link StepType#DELAY} steps.
 */
public record StepDefinition(String id, StepType type, List<String> dependsOn, TaskSpec task, long delayMs) {}
