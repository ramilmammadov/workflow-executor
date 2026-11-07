package io.workflowexecutor.execution;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.failure.ApplicationFailure;
import io.workflowexecutor.LogKeys;
import io.workflowexecutor.definition.Simulation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stand-in for real task handlers. It sleeps for the simulated work time and fails on demand, which is
 * enough to exercise timeouts, retries, failure propagation and cancellation end to end.
 */
@Component
public class SimulatedTaskActivities implements TaskActivities {

    public static final String TRANSIENT_FAILURE = "SimulatedTransientFailure";
    public static final String PERMANENT_FAILURE = "SimulatedPermanentFailure";

    private static final Logger log = LoggerFactory.getLogger(SimulatedTaskActivities.class);
    private static final long HEARTBEAT_INTERVAL_MS = 200;

    @Override
    public TaskOutcome runTask(TaskInvocation invocation) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        int attempt = context.getInfo().getAttempt();
        try (MDC.MDCCloseable stepId = MDC.putCloseable(LogKeys.STEP_ID, invocation.stepId())) {
            log.info("Task attempt {} started, idempotencyKey={}", attempt, invocation.idempotencyKey());
            simulateWork(context, invocation.simulation().workMs());
            failIfSimulated(invocation.simulation(), attempt);
            log.info("Task attempt {} succeeded", attempt);
            return new TaskOutcome(attempt);
        }
    }

    /** Works in short slices and heartbeats after each one; a heartbeat is also how cancellation arrives. */
    private static void simulateWork(ActivityExecutionContext context, long workMs) {
        long remainingMs = workMs;
        while (remainingMs > 0) {
            long sliceMs = Math.min(remainingMs, HEARTBEAT_INTERVAL_MS);
            sleep(sliceMs);
            context.heartbeat(null);
            remainingMs -= sliceMs;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw Activity.wrap(interrupted);
        }
    }

    /** The attempt number goes into the failure details, so the workflow can report how many attempts ran. */
    private static void failIfSimulated(Simulation simulation, int attempt) {
        if (simulation.failPermanently()) {
            log.info("Task attempt {} fails with a non-retryable error (simulated)", attempt);
            throw ApplicationFailure.newNonRetryableFailure("Simulated permanent failure", PERMANENT_FAILURE, attempt);
        }
        if (attempt <= simulation.failFirstAttempts()) {
            log.info("Task attempt {} fails with a retryable error (simulated)", attempt);
            throw ApplicationFailure.newFailure(
                    "Simulated transient failure on attempt " + attempt, TRANSIENT_FAILURE, attempt);
        }
    }
}
