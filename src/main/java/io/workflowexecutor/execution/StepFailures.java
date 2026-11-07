package io.workflowexecutor.execution;

import io.temporal.api.enums.v1.RetryState;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.Workflow;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Translates the failures Temporal hands to workflow code into step outcomes. Only the failure types that tasks
 * declare reach clients. Anything else, such as a plain Java exception, is reported as an unexpected error, so
 * no internal detail leaks; the worker log keeps the full failure.
 */
final class StepFailures {

    static final String TIMEOUT = "Timeout";
    static final String UNEXPECTED_ERROR = "UnexpectedError";

    private static final Logger log = Workflow.getLogger(StepFailures.class);
    private static final String UNEXPECTED_ERROR_MESSAGE =
            "The step failed with an unexpected error; the worker log has the details";
    private static final Set<String> DECLARED_TASK_FAILURES =
            Set.of(SimulatedTaskActivities.TRANSIENT_FAILURE, SimulatedTaskActivities.PERMANENT_FAILURE);
    private static final String RETRY_STATE_PREFIX = "RETRY_STATE_";
    private static final String TIMEOUT_TYPE_PREFIX = "TIMEOUT_TYPE_";

    private StepFailures() {}

    static StepOutcome taskOutcome(RuntimeException failure, int maxAttempts) {
        if (isCancellation(failure)) {
            return StepOutcome.cancelled();
        }
        if (failure instanceof ActivityFailure activityFailure) {
            return StepOutcome.failed(attemptsOf(activityFailure, maxAttempts), describe(activityFailure));
        }
        return StepOutcome.failed(null, unexpected(failure, null));
    }

    static StepOutcome timerOutcome(RuntimeException failure) {
        if (isCancellation(failure)) {
            return StepOutcome.cancelled();
        }
        return StepOutcome.failed(null, unexpected(failure, null));
    }

    private static boolean isCancellation(RuntimeException failure) {
        return failure instanceof CanceledFailure || failure.getCause() instanceof CanceledFailure;
    }

    private static StepFailure describe(ActivityFailure failure) {
        String retryState = withoutPrefix(failure.getRetryState().name(), RETRY_STATE_PREFIX);
        Throwable cause = failure.getCause();
        if (cause instanceof ApplicationFailure application && isDeclared(application)) {
            return new StepFailure(application.getType(), application.getOriginalMessage(), retryState);
        }
        if (cause instanceof TimeoutFailure timeout) {
            String timeoutType = withoutPrefix(timeout.getTimeoutType().name(), TIMEOUT_TYPE_PREFIX);
            return new StepFailure(TIMEOUT, "Attempt exceeded its " + timeoutType + " timeout", retryState);
        }
        return unexpected(failure, retryState);
    }

    /** Declared task failures carry the attempt number in their details; a timeout carries no details. */
    private static Integer attemptsOf(ActivityFailure failure, int maxAttempts) {
        if (failure.getCause() instanceof ApplicationFailure application
                && isDeclared(application)
                && application.getDetails().getSize() > 0) {
            return application.getDetails().get(Integer.class);
        }
        if (failure.getRetryState() == RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED) {
            return maxAttempts;
        }
        return null;
    }

    private static boolean isDeclared(ApplicationFailure failure) {
        return DECLARED_TASK_FAILURES.contains(failure.getType());
    }

    private static StepFailure unexpected(RuntimeException failure, String retryState) {
        log.warn("Step failed with an unexpected error", failure);
        return new StepFailure(UNEXPECTED_ERROR, UNEXPECTED_ERROR_MESSAGE, retryState);
    }

    private static String withoutPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }
}
