package io.workflowexecutor.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    @Test
    void executionOrderPutsDependenciesFirstAndKeepsDefinitionOrderForTies() {
        Map<String, List<String>> graph = graph("c:a", "a:", "b:");

        assertThat(DependencyGraph.executionOrder(graph)).containsExactly("a", "c", "b");
    }

    @Test
    void executionOrderHandlesFanOutAndFanIn() {
        Map<String, List<String>> graph = graph("join:left,right", "left:start", "right:start", "start:");

        assertThat(DependencyGraph.executionOrder(graph)).containsExactly("start", "left", "right", "join");
    }

    @Test
    void executionOrderRejectsACycle() {
        Map<String, List<String>> graph = graph("a:b", "b:a");

        assertThatThrownBy(() -> DependencyGraph.executionOrder(graph)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findCycleReturnsThePathOfTheCycle() {
        Map<String, List<String>> graph = graph("a:", "b:a,d", "c:b", "d:c");

        assertThat(DependencyGraph.findCycle(graph)).contains(List.of("b", "d", "c", "b"));
    }

    @Test
    void findCycleReturnsEmptyForAnAcyclicGraph() {
        Map<String, List<String>> graph = graph("a:", "b:a", "c:a,b");

        assertThat(DependencyGraph.findCycle(graph)).isEmpty();
    }

    /** Each entry reads "step:dependency,dependency"; "step:" means no dependencies. */
    private static Map<String, List<String>> graph(String... entries) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (String entry : entries) {
            String[] stepAndDependencies = entry.split(":", -1);
            String dependencies = stepAndDependencies[1];
            graph.put(stepAndDependencies[0], dependencies.isEmpty() ? List.of() : List.of(dependencies.split(",")));
        }
        return graph;
    }
}
