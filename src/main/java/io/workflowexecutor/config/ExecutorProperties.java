package io.workflowexecutor.config;

import io.workflowexecutor.definition.StepDefaults;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All application settings under {@code workflow-executor.*}. Validated at startup, so bad config fails fast. */
@Validated
@ConfigurationProperties("workflow-executor")
public record ExecutorProperties(@NotNull @Valid TemporalProperties temporal, @NotNull @Valid StepDefaults stepDefaults) {}
