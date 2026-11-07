package io.workflowexecutor;

import io.workflowexecutor.config.ExecutorProperties;
import io.workflowexecutor.config.TemporalProperties;
import io.workflowexecutor.definition.DefinitionMapper;
import io.workflowexecutor.definition.DefinitionValidator;
import io.workflowexecutor.definition.RetryRequest;
import io.workflowexecutor.definition.SimulationRequest;
import io.workflowexecutor.definition.StepDefaults;
import io.workflowexecutor.definition.StepRequest;
import io.workflowexecutor.definition.WorkflowDefinitionRequest;
import java.time.Duration;
import java.util.List;

/** Builders for definitions used across tests. Retry intervals are short so tests stay fast. */
public final class TestDefinitions {

    public static final String TASK_QUEUE = "workflow-executor-test";

    public static final StepDefaults STEP_DEFAULTS =
            new StepDefaults(3, Duration.ofSeconds(5), Duration.ofMillis(50), 2.0, Duration.ofMillis(200));

    public static final ExecutorProperties PROPERTIES = new ExecutorProperties(
            new TemporalProperties(
                    "localhost:7233",
                    "default",
                    TASK_QUEUE,
                    true,
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5)),
            STEP_DEFAULTS);

    public static final DefinitionMapper MAPPER = new DefinitionMapper(STEP_DEFAULTS);

    public static final DefinitionValidator VALIDATOR =
            new DefinitionValidator(PROPERTIES.temporal().executionTimeout());

    private TestDefinitions() {}

    public static WorkflowDefinitionRequest definition(StepRequest... steps) {
        return new WorkflowDefinitionRequest(null, List.of(steps));
    }

    public static StepRequest task(String id, String... dependsOn) {
        return new StepRequest(id, "task", List.of(dependsOn), null, null, null, null);
    }

    public static StepRequest delay(String id, long delayMs, String... dependsOn) {
        return new StepRequest(id, "delay", List.of(dependsOn), null, null, delayMs, null);
    }

    /** A task whose every attempt takes {@code workMs}. */
    public static StepRequest workingTask(String id, long workMs, String... dependsOn) {
        SimulationRequest simulate = new SimulationRequest(workMs, null, null);
        return new StepRequest(id, "task", List.of(dependsOn), null, null, null, simulate);
    }

    /** A task whose first {@code failFirstAttempts} attempts fail with a retryable error. */
    public static StepRequest flakyTask(String id, int failFirstAttempts, int maxAttempts, String... dependsOn) {
        SimulationRequest simulate = new SimulationRequest(null, failFirstAttempts, null);
        return new StepRequest(id, "task", List.of(dependsOn), new RetryRequest(maxAttempts), null, null, simulate);
    }

    public static StepRequest permanentlyFailingTask(String id, String... dependsOn) {
        SimulationRequest simulate = new SimulationRequest(null, null, true);
        return new StepRequest(id, "task", List.of(dependsOn), null, null, null, simulate);
    }

    public static StepRequest slowTask(String id, long workMs, long timeoutMs, int maxAttempts) {
        SimulationRequest simulate = new SimulationRequest(workMs, null, null);
        return new StepRequest(id, "task", List.of(), new RetryRequest(maxAttempts), timeoutMs, null, simulate);
    }
}
