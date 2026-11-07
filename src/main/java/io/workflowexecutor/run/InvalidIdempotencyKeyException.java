package io.workflowexecutor.run;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be 1 to 255 printable ASCII characters without spaces");
    }
}
