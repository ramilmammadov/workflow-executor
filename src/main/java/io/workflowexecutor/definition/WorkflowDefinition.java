package io.workflowexecutor.definition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated definition with every default resolved. It is the Temporal workflow input, so it is
 * frozen at submission: changing server defaults later never changes a run that already started.
 */
public record WorkflowDefinition(String name, List<StepDefinition> steps) {

    /** Step id to the ids it depends on, in definition order. */
    public Map<String, List<String>> dependencyGraph() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        steps.forEach(step -> graph.put(step.id(), step.dependsOn()));
        return graph;
    }
}
