package io.workflowexecutor.definition;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** The kinds of step the executor knows how to run. */
public enum StepType {
    /** Runs as a Temporal activity: retried, timed out and heartbeated by Temporal. */
    TASK("task"),
    /** Waits for a fixed time using a durable Temporal timer; no worker thread is held while waiting. */
    DELAY("delay");

    private final String wireName;

    StepType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public static Optional<StepType> fromWireName(String name) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(name)).findFirst();
    }

    public static String supportedWireNames() {
        return Arrays.stream(values()).map(StepType::wireName).collect(Collectors.joining(", "));
    }
}
