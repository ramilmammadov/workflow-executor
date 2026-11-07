package io.workflowexecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.workflowexecutor.definition.DefinitionLimits;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The full application context, worker included, pointed at an address where nothing listens. It must start
 * anyway, publish its API description, report itself alive but not ready, and answer API calls quickly.
 */
@SpringBootTest(
        properties = {
            "workflow-executor.temporal.target=127.0.0.1:1",
            "workflow-executor.temporal.worker-enabled=true",
            "workflow-executor.temporal.api-call-timeout=2s"
        })
@AutoConfigureMockMvc
class WorkflowExecutorApplicationTests {

    private static final Duration FAST_ANSWER_LIMIT = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void answersQuicklyWith503WhileTemporalIsUnreachable() throws Exception {
        long startNanos = System.nanoTime();

        mockMvc.perform(post("/api/v1/workflow-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"steps\": [{\"id\": \"a\", \"type\": \"task\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.type").value("urn:workflow-executor:problem:engine-unavailable"));

        assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isLessThan(FAST_ANSWER_LIMIT);
    }

    @Test
    void rejectsARequestBodyOverTheSizeLimitWith413() throws Exception {
        String oversizedName = "x".repeat((int) DefinitionLimits.MAX_REQUEST_BYTES);
        String body = "{\"name\": \"" + oversizedName + "\", \"steps\": [{\"id\": \"a\", \"type\": \"task\"}]}";

        mockMvc.perform(post("/api/v1/workflow-runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.type").value("urn:workflow-executor:problem:request-too-large"));
    }

    @Test
    void publishesTheOpenApiDescriptionOfEveryEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/workflow-runs'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/workflow-runs/{runId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/workflow-runs/{runId}/cancel'].post").exists());
    }

    @Test
    void staysAliveButIsNotReadyWhileTemporalIsUnreachable() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.temporal.status").value("DOWN"));
    }
}
