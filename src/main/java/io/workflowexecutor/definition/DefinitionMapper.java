package io.workflowexecutor.definition;

import java.util.List;
import java.util.Objects;

/**
 * Turns a validated request into the workflow input. Every default is resolved here, at submission,
 * so a run keeps the settings it started with even if the server configuration changes later.
 */
public class DefinitionMapper {

    private final StepDefaults defaults;

    public DefinitionMapper(StepDefaults defaults) {
        this.defaults = defaults;
    }

    public WorkflowDefinition toDefinition(WorkflowDefinitionRequest request) {
        List<StepDefinition> steps = request.steps().stream().map(this::toStep).toList();
        return new WorkflowDefinition(request.name(), steps);
    }

    private StepDefinition toStep(StepRequest step) {
        StepType type = StepType.fromWireName(step.type())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported step type: " + step.type()));
        List<String> dependsOn = List.copyOf(Objects.requireNonNullElse(step.dependsOn(), List.of()));
        if (type == StepType.DELAY) {
            return new StepDefinition(step.id(), type, dependsOn, null, step.delayMs());
        }
        return new StepDefinition(step.id(), type, dependsOn, toTaskSpec(step), 0);
    }

    private TaskSpec toTaskSpec(StepRequest step) {
        int maxAttempts = step.retry() == null ? defaults.maxAttempts() : step.retry().maxAttempts();
        long timeoutMs = Objects.requireNonNullElse(step.timeoutMs(), defaults.attemptTimeout().toMillis());
        RetrySpec retry = new RetrySpec(
                maxAttempts,
                defaults.initialRetryInterval().toMillis(),
                defaults.backoffCoefficient(),
                defaults.maxRetryInterval().toMillis());
        return new TaskSpec(timeoutMs, retry, toSimulation(step.simulate()));
    }

    private static Simulation toSimulation(SimulationRequest simulate) {
        if (simulate == null) {
            return Simulation.NONE;
        }
        return new Simulation(
                Objects.requireNonNullElse(simulate.workMs(), 0L),
                Objects.requireNonNullElse(simulate.failFirstAttempts(), 0),
                Boolean.TRUE.equals(simulate.failPermanently()));
    }
}
