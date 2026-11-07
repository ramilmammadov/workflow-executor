package io.workflowexecutor.run;

public class RunNotCancellableException extends RuntimeException {

    public RunNotCancellableException(RunStatus status) {
        super("The run has already finished as " + status + " and can no longer be cancelled");
    }
}
