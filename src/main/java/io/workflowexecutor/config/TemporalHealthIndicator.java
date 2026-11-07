package io.workflowexecutor.config;

import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the Temporal frontend answers. Part of the readiness group only: when Temporal is down
 * the instance stops receiving traffic, but it is not restarted, because a restart would not help.
 */
@Component("temporal")
class TemporalHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TemporalHealthIndicator.class);

    private final WorkflowServiceStubs serviceStubs;

    TemporalHealthIndicator(WorkflowServiceStubs serviceStubs) {
        this.serviceStubs = serviceStubs;
    }

    @Override
    public Health health() {
        try {
            HealthCheckResponse response = serviceStubs.healthCheck();
            if (response.getStatus() == HealthCheckResponse.ServingStatus.SERVING) {
                return Health.up().build();
            }
            return Health.down().withDetail("servingStatus", response.getStatus().name()).build();
        } catch (RuntimeException unreachable) {
            log.warn("Temporal health check failed: {}", unreachable.getMessage());
            return Health.down(unreachable).build();
        }
    }
}
