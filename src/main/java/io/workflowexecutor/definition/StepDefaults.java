package io.workflowexecutor.definition;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;

/** Server-side defaults for task steps that do not set their own values. Bound from configuration. */
public record StepDefaults(
        @Min(1) @Max(DefinitionLimits.MAX_ATTEMPTS) int maxAttempts,
        @NotNull @DurationMin(millis = DefinitionLimits.MIN_TIMEOUT_MS) @DurationMax(millis = DefinitionLimits.MAX_TIMEOUT_MS)
                Duration attemptTimeout,
        @NotNull @DurationMin(millis = 1) Duration initialRetryInterval,
        @DecimalMin("1.0") double backoffCoefficient,
        @NotNull @DurationMin(millis = 1) Duration maxRetryInterval) {

    @AssertTrue(message = "maxRetryInterval must not be shorter than initialRetryInterval")
    public boolean isRetryIntervalRangeValid() {
        return initialRetryInterval == null
                || maxRetryInterval == null
                || maxRetryInterval.compareTo(initialRetryInterval) >= 0;
    }
}
