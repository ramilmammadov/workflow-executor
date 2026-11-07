package io.workflowexecutor.config;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two Temporal clients with different failure behaviour, as if the API and the worker ran as separate
 * processes. The API client (primary) gives up after {@code api-call-timeout}, so HTTP callers get a fast 503
 * while Temporal is down. The worker client keeps the SDK's longer retry window, so a short Temporal outage
 * does not lose activity results. Both connect lazily: the application starts even when Temporal is down.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutorProperties.class)
class TemporalConfig {

    private static final String WORKER_STUBS = "workerServiceStubs";
    private static final String WORKER_CLIENT = "workerWorkflowClient";
    private static final String CLIENT_METRICS_TAG = "client";
    private static final double METRICS_REPORT_INTERVAL_SECONDS = 10;

    /** Temporal SDK metrics (latencies, failures, task backlog) published through Micrometer to Prometheus. */
    @Bean(destroyMethod = "close")
    Scope temporalMetricsScope(MeterRegistry meterRegistry) {
        return new RootScopeBuilder()
                .reporter(new MicrometerClientStatsReporter(meterRegistry))
                .reportEvery(com.uber.m3.util.Duration.ofSeconds(METRICS_REPORT_INTERVAL_SECONDS));
    }

    @Bean(destroyMethod = "shutdown")
    @Primary
    WorkflowServiceStubs apiServiceStubs(ExecutorProperties properties, Scope temporalMetricsScope) {
        TemporalProperties temporal = properties.temporal();
        RpcRetryOptions giveUpQuickly = RpcRetryOptions.newBuilder()
                .setExpiration(temporal.apiCallTimeout())
                .build();
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporal.target())
                .setRpcTimeout(temporal.apiCallTimeout())
                .setRpcQueryTimeout(temporal.queryTimeout())
                .setRpcRetryOptions(giveUpQuickly)
                .setMetricsScope(temporalMetricsScope.tagged(Map.of(CLIENT_METRICS_TAG, "api")))
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean(name = WORKER_STUBS, destroyMethod = "shutdown")
    WorkflowServiceStubs workerServiceStubs(ExecutorProperties properties, Scope temporalMetricsScope) {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.temporal().target())
                .setMetricsScope(temporalMetricsScope.tagged(Map.of(CLIENT_METRICS_TAG, "worker")))
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    @Primary
    WorkflowClient apiWorkflowClient(WorkflowServiceStubs apiServiceStubs, ExecutorProperties properties) {
        return newClient(apiServiceStubs, properties);
    }

    @Bean(WORKER_CLIENT)
    WorkflowClient workerWorkflowClient(
            @Qualifier(WORKER_STUBS) WorkflowServiceStubs workerServiceStubs, ExecutorProperties properties) {
        return newClient(workerServiceStubs, properties);
    }

    /** Shut down by the worker lifecycle, which also waits for in-flight tasks; Spring must not shut it down again. */
    @Bean(destroyMethod = "")
    WorkerFactory workerFactory(@Qualifier(WORKER_CLIENT) WorkflowClient workerWorkflowClient) {
        return WorkerFactory.newInstance(workerWorkflowClient);
    }

    private static WorkflowClient newClient(WorkflowServiceStubs serviceStubs, ExecutorProperties properties) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
                .setNamespace(properties.temporal().namespace())
                .build();
        return WorkflowClient.newInstance(serviceStubs, options);
    }
}
