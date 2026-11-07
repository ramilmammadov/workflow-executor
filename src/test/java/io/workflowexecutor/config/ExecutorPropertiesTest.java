package io.workflowexecutor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Configuration is validated at startup: a bad value stops the application instead of misbehaving later. */
class ExecutorPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "workflow-executor.temporal.target=localhost:7233",
                    "workflow-executor.temporal.namespace=default",
                    "workflow-executor.temporal.task-queue=workflow-executor",
                    "workflow-executor.temporal.worker-enabled=true",
                    "workflow-executor.temporal.execution-timeout=1h",
                    "workflow-executor.temporal.api-call-timeout=5s",
                    "workflow-executor.temporal.query-timeout=5s",
                    "workflow-executor.step-defaults.max-attempts=3",
                    "workflow-executor.step-defaults.attempt-timeout=30s",
                    "workflow-executor.step-defaults.initial-retry-interval=1s",
                    "workflow-executor.step-defaults.backoff-coefficient=2.0",
                    "workflow-executor.step-defaults.max-retry-interval=30s");

    @Test
    void bindsAValidConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ExecutorProperties properties = context.getBean(ExecutorProperties.class);
            assertThat(properties.temporal().executionTimeout()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.stepDefaults().maxAttempts()).isEqualTo(3);
        });
    }

    @Test
    void rejectsZeroMaxAttempts() {
        contextRunner
                .withPropertyValues("workflow-executor.step-defaults.max-attempts=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("maxAttempts"));
    }

    @Test
    void rejectsAMaxRetryIntervalShorterThanTheInitialOne() {
        contextRunner
                .withPropertyValues("workflow-executor.step-defaults.max-retry-interval=100ms")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("retryIntervalRangeValid"));
    }

    @Test
    void rejectsABlankTemporalTarget() {
        contextRunner
                .withPropertyValues("workflow-executor.temporal.target= ")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("target"));
    }

    @EnableConfigurationProperties(ExecutorProperties.class)
    static class PropertiesConfiguration {}
}
