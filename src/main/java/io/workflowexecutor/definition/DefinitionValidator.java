package io.workflowexecutor.definition;

import static io.workflowexecutor.definition.ViolationCode.DEPENDENCY_CYCLE;
import static io.workflowexecutor.definition.ViolationCode.DUPLICATE_DEPENDENCY;
import static io.workflowexecutor.definition.ViolationCode.DUPLICATE_STEP_ID;
import static io.workflowexecutor.definition.ViolationCode.EXCEEDS_EXECUTION_TIMEOUT;
import static io.workflowexecutor.definition.ViolationCode.FIELD_NOT_ALLOWED;
import static io.workflowexecutor.definition.ViolationCode.MISSING_FIELD;
import static io.workflowexecutor.definition.ViolationCode.SELF_DEPENDENCY;
import static io.workflowexecutor.definition.ViolationCode.UNKNOWN_DEPENDENCY;
import static io.workflowexecutor.definition.ViolationCode.UNSUPPORTED_STEP_TYPE;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks the rules that need the whole definition: step types, unique ids, dependency references, cycles,
 * and that the definition's own delays fit within the execution timeout. Field-level rules (required fields,
 * sizes, ranges) are declared on the request records and have already passed when this runs. All violations
 * are collected, so a client can fix them in one go.
 */
public class DefinitionValidator {

    private final Duration executionTimeout;

    public DefinitionValidator(Duration executionTimeout) {
        this.executionTimeout = executionTimeout;
    }

    public void validate(WorkflowDefinitionRequest request) {
        List<StepRequest> steps = request.steps();
        List<DefinitionViolation> violations = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            checkTypeSpecificFields(steps.get(index), stepPath(index), violations);
        }
        boolean idsAreUnique = checkUniqueIds(steps, violations);
        checkDependencyReferences(steps, violations);
        if (idsAreUnique) {
            checkNoCycle(steps, violations);
        }
        if (violations.isEmpty()) {
            checkDelaysFitExecutionTimeout(steps, violations);
        }
        if (!violations.isEmpty()) {
            throw new InvalidDefinitionException(violations);
        }
    }

    private void checkTypeSpecificFields(StepRequest step, String path, List<DefinitionViolation> violations) {
        Optional<StepType> type = StepType.fromWireName(step.type());
        if (type.isEmpty()) {
            String message = "Unsupported step type '%s'; supported types are: %s"
                    .formatted(step.type(), StepType.supportedWireNames());
            violations.add(new DefinitionViolation(path + ".type", UNSUPPORTED_STEP_TYPE, message));
            return;
        }
        if (type.get() == StepType.TASK) {
            rejectIfPresent(step.delayMs(), path + ".delayMs", StepType.TASK, violations);
            return;
        }
        if (step.delayMs() == null) {
            violations.add(new DefinitionViolation(path + ".delayMs", MISSING_FIELD, "delayMs is required for delay steps"));
        }
        rejectIfPresent(step.retry(), path + ".retry", StepType.DELAY, violations);
        rejectIfPresent(step.timeoutMs(), path + ".timeoutMs", StepType.DELAY, violations);
        rejectIfPresent(step.simulate(), path + ".simulate", StepType.DELAY, violations);
    }

    private void rejectIfPresent(Object value, String field, StepType type, List<DefinitionViolation> violations) {
        if (value != null) {
            String fieldName = field.substring(field.lastIndexOf('.') + 1);
            String message = "%s is not allowed for %s steps".formatted(fieldName, type.wireName());
            violations.add(new DefinitionViolation(field, FIELD_NOT_ALLOWED, message));
        }
    }

    /** Returns whether every id is unique; the cycle check needs that to build the graph. */
    private boolean checkUniqueIds(List<StepRequest> steps, List<DefinitionViolation> violations) {
        Set<String> seen = new HashSet<>();
        boolean unique = true;
        for (int index = 0; index < steps.size(); index++) {
            String id = steps.get(index).id();
            if (!seen.add(id)) {
                String message = "Step id '%s' is used more than once".formatted(id);
                violations.add(new DefinitionViolation(stepPath(index) + ".id", DUPLICATE_STEP_ID, message));
                unique = false;
            }
        }
        return unique;
    }

    private void checkDependencyReferences(List<StepRequest> steps, List<DefinitionViolation> violations) {
        Set<String> stepIds = steps.stream().map(StepRequest::id).collect(Collectors.toSet());
        for (int index = 0; index < steps.size(); index++) {
            checkDependenciesOf(steps.get(index), stepPath(index), stepIds, violations);
        }
    }

    private void checkDependenciesOf(
            StepRequest step, String path, Set<String> stepIds, List<DefinitionViolation> violations) {
        List<String> dependsOn = dependsOnOf(step);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < dependsOn.size(); index++) {
            String dependency = dependsOn.get(index);
            String field = "%s.dependsOn[%d]".formatted(path, index);
            if (dependency.equals(step.id())) {
                String message = "Step '%s' cannot depend on itself".formatted(step.id());
                violations.add(new DefinitionViolation(field, SELF_DEPENDENCY, message));
            } else if (!stepIds.contains(dependency)) {
                String message = "Step '%s' depends on unknown step '%s'".formatted(step.id(), dependency);
                violations.add(new DefinitionViolation(field, UNKNOWN_DEPENDENCY, message));
            } else if (!seen.add(dependency)) {
                String message = "Step '%s' lists dependency '%s' more than once".formatted(step.id(), dependency);
                violations.add(new DefinitionViolation(field, DUPLICATE_DEPENDENCY, message));
            }
        }
    }

    /** Unknown dependencies lead nowhere and self-dependencies are already reported, so neither forms a cycle here. */
    private void checkNoCycle(List<StepRequest> steps, List<DefinitionViolation> violations) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        steps.forEach(step -> graph.put(step.id(), dependsOnOf(step).stream()
                .filter(dependency -> !dependency.equals(step.id()))
                .toList()));
        DependencyGraph.findCycle(graph).ifPresent(cycle -> {
            String message = "Steps depend on each other in a cycle: %s (each step depends on the next)"
                    .formatted(String.join(" -> ", cycle));
            violations.add(new DefinitionViolation("steps", DEPENDENCY_CYCLE, message));
        });
    }

    /**
     * Delays are the part of a run's duration that the definition fixes. If the longest chain of them does
     * not fit within the execution timeout, the run is certain to time out, so the definition is rejected.
     */
    private void checkDelaysFitExecutionTimeout(List<StepRequest> steps, List<DefinitionViolation> violations) {
        long longestChainMs = longestDelayChainMs(steps);
        long timeoutMs = executionTimeout.toMillis();
        if (longestChainMs >= timeoutMs) {
            String message = ("Delays along the longest dependency chain add up to %d ms; "
                            + "a run must finish within the execution timeout of %d ms")
                    .formatted(longestChainMs, timeoutMs);
            violations.add(new DefinitionViolation("steps", EXCEEDS_EXECUTION_TIMEOUT, message));
        }
    }

    /** Longest path through the graph, where each delay step weighs its delay and each task weighs nothing. */
    private static long longestDelayChainMs(List<StepRequest> steps) {
        Map<String, StepRequest> stepsById = new LinkedHashMap<>();
        steps.forEach(step -> stepsById.put(step.id(), step));
        Map<String, List<String>> graph = new LinkedHashMap<>();
        steps.forEach(step -> graph.put(step.id(), dependsOnOf(step)));
        Map<String, Long> chainEndingAt = new HashMap<>();
        for (String stepId : DependencyGraph.executionOrder(graph)) {
            StepRequest step = stepsById.get(stepId);
            long longestBefore = dependsOnOf(step).stream().mapToLong(chainEndingAt::get).max().orElse(0);
            chainEndingAt.put(stepId, longestBefore + delayMsOf(step));
        }
        return chainEndingAt.values().stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private static long delayMsOf(StepRequest step) {
        if (StepType.DELAY.wireName().equals(step.type())) {
            return step.delayMs();
        }
        return 0;
    }

    private static List<String> dependsOnOf(StepRequest step) {
        return Objects.requireNonNullElse(step.dependsOn(), List.of());
    }

    private static String stepPath(int index) {
        return "steps[" + index + "]";
    }
}
