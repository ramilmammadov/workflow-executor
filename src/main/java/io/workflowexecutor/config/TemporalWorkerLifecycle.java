package io.workflowexecutor.config;

import io.grpc.StatusRuntimeException;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.workflowexecutor.execution.DagWorkflowImpl;
import io.workflowexecutor.execution.TaskActivities;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Starts the Temporal worker once the application context is ready, and on shutdown stops polling and lets
 * in-flight tasks finish. Disabled by configuration on API-only instances.
 *
 * <p>The SDK checks the namespace with Temporal before it starts polling, and fails when Temporal is down.
 * The worker therefore starts in the background and retries with backoff, so the application starts either
 * way; readiness reports Temporal as down until it is reachable.
 */
@Component
class TemporalWorkerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerLifecycle.class);
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(10);

    private final WorkerFactory workerFactory;
    private final TaskActivities taskActivities;
    private final TemporalProperties temporal;
    private final ScheduledExecutorService starter = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("temporal-worker-starter").daemon().factory());
    private volatile boolean running;

    TemporalWorkerLifecycle(WorkerFactory workerFactory, TaskActivities taskActivities, ExecutorProperties properties) {
        this.workerFactory = workerFactory;
        this.taskActivities = taskActivities;
        this.temporal = properties.temporal();
    }

    @Override
    public void start() {
        if (!temporal.workerEnabled()) {
            log.info("Temporal worker disabled: this instance serves the API only");
            return;
        }
        Worker worker = workerFactory.newWorker(temporal.taskQueue());
        worker.registerWorkflowImplementationTypes(DagWorkflowImpl.class);
        worker.registerActivitiesImplementations(taskActivities);
        running = true;
        starter.execute(() -> startPolling(FIRST_RETRY_DELAY));
    }

    /**
     * Runs on the starter thread, the boundary of this background task, so any failure is logged and retried.
     * If the application stops while this is in progress, a worker that started anyway is shut down again.
     */
    private void startPolling(Duration retryDelay) {
        if (!running) {
            return;
        }
        try {
            workerFactory.start();
        } catch (RuntimeException startFailure) {
            retryLater(retryDelay, startFailure);
            return;
        }
        log.info("Temporal worker polling task queue '{}' in namespace '{}'", temporal.taskQueue(), temporal.namespace());
        if (!running) {
            shutDownWorkerFactory();
        }
    }

    private void retryLater(Duration retryDelay, RuntimeException startFailure) {
        if (!running) {
            return;
        }
        log.warn("Temporal worker could not start, retrying in {} s: {}", retryDelay.toSeconds(), startFailure.getMessage());
        starter.schedule(() -> startPolling(nextRetryDelay(retryDelay)), retryDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Duration nextRetryDelay(Duration retryDelay) {
        Duration doubled = retryDelay.multipliedBy(2);
        if (doubled.compareTo(MAX_RETRY_DELAY) > 0) {
            return MAX_RETRY_DELAY;
        }
        return doubled;
    }

    @Override
    public void stop() {
        running = false;
        starter.shutdownNow();
        if (!workerFactory.isStarted()) {
            log.info("Temporal worker never started polling; nothing to stop");
            return;
        }
        log.info("Stopping the Temporal worker");
        shutDownWorkerFactory();
        workerFactory.awaitTermination(SHUTDOWN_GRACE_PERIOD.toSeconds(), TimeUnit.SECONDS);
        if (!workerFactory.isTerminated()) {
            log.warn("Temporal worker did not finish its in-flight tasks within {} s", SHUTDOWN_GRACE_PERIOD.toSeconds());
        }
    }

    /** The SDK tells Temporal that the worker is leaving; when Temporal is down that call fails, which is logged. */
    private void shutDownWorkerFactory() {
        try {
            workerFactory.shutdown();
        } catch (StatusRuntimeException temporalUnavailable) {
            log.warn("Could not tell Temporal that the worker is stopping: {}", temporalUnavailable.getStatus().getCode());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
