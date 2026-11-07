package io.workflowexecutor.run;

/**
 * Result of a cancellation request.
 *
 * @param alreadyCancelled true when the run had already been cancelled before this request
 */
public record CancellationResult(WorkflowRunView run, boolean alreadyCancelled) {}
