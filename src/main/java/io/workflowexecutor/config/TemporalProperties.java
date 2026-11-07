package io.workflowexecutor.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * Connection to Temporal and how this instance takes part in it.
 *
 * @param target host:port of the Temporal frontend
 * @param taskQueue queue the workflow and its activities are dispatched on
 * @param workerEnabled whether this instance also executes workflows; false gives an API-only instance
 * @param executionTimeout upper bound for a whole run, a safety net against runs that never finish
 * @param apiCallTimeout longest time an API call to Temporal may take, retries included, so HTTP callers get
 *     a fast answer when Temporal is down
 * @param queryTimeout deadline of a progress query, which also needs a worker to answer it
 */
public record TemporalProperties(
        @NotBlank String target,
        @NotBlank String namespace,
        @NotBlank String taskQueue,
        boolean workerEnabled,
        @NotNull @DurationMin(seconds = 1) Duration executionTimeout,
        @NotNull @DurationMin(millis = 100) Duration apiCallTimeout,
        @NotNull @DurationMin(millis = 100) Duration queryTimeout) {}
