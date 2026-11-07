package io.workflowexecutor.run;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException() {
        super("No workflow run exists with this id");
    }
}
