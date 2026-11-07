package io.workflowexecutor.definition;

import static io.workflowexecutor.TestDefinitions.MAPPER;
import static io.workflowexecutor.TestDefinitions.STEP_DEFAULTS;
import static io.workflowexecutor.TestDefinitions.definition;
import static io.workflowexecutor.TestDefinitions.delay;
import static io.workflowexecutor.TestDefinitions.task;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefinitionMapperTest {

    @Test
    void resolvesServerDefaultsForATaskThatSetsNothing() {
        StepDefinition step = MAPPER.toDefinition(definition(task("validate"))).steps().getFirst();

        RetrySpec expectedRetry = new RetrySpec(
                STEP_DEFAULTS.maxAttempts(),
                STEP_DEFAULTS.initialRetryInterval().toMillis(),
                STEP_DEFAULTS.backoffCoefficient(),
                STEP_DEFAULTS.maxRetryInterval().toMillis());
        assertThat(step.type()).isEqualTo(StepType.TASK);
        assertThat(step.dependsOn()).isEmpty();
        assertThat(step.task())
                .isEqualTo(new TaskSpec(STEP_DEFAULTS.attemptTimeout().toMillis(), expectedRetry, Simulation.NONE));
    }

    @Test
    void keepsValuesTheStepSets() {
        StepRequest request = new StepRequest(
                "charge",
                "task",
                List.of("validate"),
                new RetryRequest(5),
                2_000L,
                null,
                new SimulationRequest(300L, 2, false));

        StepDefinition step = MAPPER.toDefinition(definition(task("validate"), request)).steps().get(1);

        assertThat(step.dependsOn()).containsExactly("validate");
        assertThat(step.task().timeoutMs()).isEqualTo(2_000L);
        assertThat(step.task().retry().maxAttempts()).isEqualTo(5);
        assertThat(step.task().simulation()).isEqualTo(new Simulation(300, 2, false));
    }

    @Test
    void mapsADelayStepWithoutTaskSettings() {
        StepDefinition step = MAPPER.toDefinition(definition(delay("cool-down", 1_500))).steps().getFirst();

        assertThat(step.type()).isEqualTo(StepType.DELAY);
        assertThat(step.delayMs()).isEqualTo(1_500);
        assertThat(step.task()).isNull();
    }
}
