package io.workflowexecutor.execution;

import java.util.List;

/** Result of the workflow's progress query: every step, in definition order. */
public record RunProgress(List<StepProgress> steps) {}
