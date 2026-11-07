package io.workflowexecutor.definition;

/** How the simulated task behaves on each attempt. */
public record Simulation(long workMs, int failFirstAttempts, boolean failPermanently) {

    public static final Simulation NONE = new Simulation(0, 0, false);
}
