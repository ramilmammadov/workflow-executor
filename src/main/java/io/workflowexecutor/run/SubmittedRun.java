package io.workflowexecutor.run;

/**
 * Result of a submission.
 *
 * @param replayed true when an earlier request with the same Idempotency-Key already created this run
 */
public record SubmittedRun(WorkflowRunView run, boolean replayed) {}
