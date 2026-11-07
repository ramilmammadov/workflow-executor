package io.workflowexecutor.definition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Algorithms over a step dependency graph, given as step id to the ids it depends on, in definition order.
 *
 * <p>Both methods are deterministic for the same input. The workflow relies on that: Temporal replays
 * workflow code and expects it to make the same decisions every time.
 */
public final class DependencyGraph {

    private DependencyGraph() {}

    /**
     * Orders the steps so that every step comes after all of its dependencies. When several steps are
     * ready at the same time, the one defined first comes first.
     *
     * @throws IllegalArgumentException if the graph has a cycle or depends on an unknown step
     */
    public static List<String> executionOrder(Map<String, List<String>> graph) {
        List<String> order = new ArrayList<>(graph.size());
        Set<String> placed = new HashSet<>();
        while (order.size() < graph.size()) {
            String next = firstReadyStep(graph, placed)
                    .orElseThrow(() -> new IllegalArgumentException("Graph has a cycle or an unknown dependency"));
            order.add(next);
            placed.add(next);
        }
        return order;
    }

    /** Returns one dependency cycle as a path that starts and ends with the same step id. */
    public static Optional<List<String>> findCycle(Map<String, List<String>> graph) {
        Set<String> cycleFree = new HashSet<>();
        for (String stepId : graph.keySet()) {
            Optional<List<String>> cycle = findCycleFrom(stepId, graph, new ArrayList<>(), cycleFree);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstReadyStep(Map<String, List<String>> graph, Set<String> placed) {
        return graph.entrySet().stream()
                .filter(step -> !placed.contains(step.getKey()))
                .filter(step -> placed.containsAll(step.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static Optional<List<String>> findCycleFrom(
            String stepId, Map<String, List<String>> graph, List<String> path, Set<String> cycleFree) {
        if (cycleFree.contains(stepId)) {
            return Optional.empty();
        }
        int firstVisit = path.indexOf(stepId);
        if (firstVisit >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(firstVisit, path.size()));
            cycle.add(stepId);
            return Optional.of(cycle);
        }
        path.add(stepId);
        for (String dependency : graph.getOrDefault(stepId, List.of())) {
            Optional<List<String>> cycle = findCycleFrom(dependency, graph, path, cycleFree);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.removeLast();
        cycleFree.add(stepId);
        return Optional.empty();
    }
}
