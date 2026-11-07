package io.workflowexecutor.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.workflowexecutor.definition.DefinitionViolation;
import io.workflowexecutor.definition.InvalidDefinitionException;
import io.workflowexecutor.definition.StepType;
import io.workflowexecutor.definition.ViolationCode;
import io.workflowexecutor.execution.StepStatus;
import io.workflowexecutor.run.CancellationResult;
import io.workflowexecutor.run.IdempotencyKeyReusedException;
import io.workflowexecutor.run.InvalidIdempotencyKeyException;
import io.workflowexecutor.run.RunNotCancellableException;
import io.workflowexecutor.run.RunNotFoundException;
import io.workflowexecutor.run.RunStatus;
import io.workflowexecutor.run.StepView;
import io.workflowexecutor.run.SubmittedRun;
import io.workflowexecutor.run.WorkflowRunService;
import io.workflowexecutor.run.WorkflowRunView;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The HTTP contract: status codes, headers and the problem body for every documented outcome. */
@WebMvcTest(WorkflowRunController.class)
class WorkflowRunControllerTest {

    private static final String RUNS = "/api/v1/workflow-runs";
    private static final String RUN_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String PROBLEM = "urn:workflow-executor:problem:";
    private static final String VALID_BODY = """
            {"steps": [{"id": "validate", "type": "task"}, {"id": "process", "type": "task", "dependsOn": ["validate"]}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowRunService service;

    @Test
    void submitReturns202WithTheRunAndItsLocation() throws Exception {
        when(service.submit(any(), isNull())).thenReturn(new SubmittedRun(run(RunStatus.RUNNING), false));

        submit(VALID_BODY)
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + RUNS + "/" + RUN_ID))
                .andExpect(header().doesNotExist(ApiHeaders.IDEMPOTENT_REPLAYED))
                .andExpect(jsonPath("$.id").value(RUN_ID))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-07T10:15:30Z"))
                .andExpect(jsonPath("$.closedAt").doesNotExist())
                .andExpect(jsonPath("$.steps[0].type").value("task"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"));
    }

    @Test
    void submitPassesTheIdempotencyKeyAndMarksAReplay() throws Exception {
        when(service.submit(any(), eq("order-42"))).thenReturn(new SubmittedRun(run(RunStatus.SUCCEEDED), true));

        mockMvc.perform(post(RUNS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, "order-42")
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(ApiHeaders.IDEMPOTENT_REPLAYED, "true"))
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + RUNS + "/" + RUN_ID))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void submitRejectsADefinitionWithoutSteps() throws Exception {
        submit("{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM + "invalid-definition"))
                .andExpect(jsonPath("$.errors[0].field").value("steps"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FIELD"));
        verifyNoInteractions(service);
    }

    @Test
    void submitRejectsAnInvalidStepId() throws Exception {
        submit("""
                        {"steps": [{"id": "-bad id", "type": "task"}]}
                        """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("steps[0].id"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be 1 to 64 letters, digits, '.', '_' or '-', starting with a letter or digit"));
        verifyNoInteractions(service);
    }

    @Test
    void submitRejectsAnUnknownField() throws Exception {
        submit("""
                        {"steps": [{"id": "a", "type": "task", "dependOn": ["b"]}]}
                        """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "malformed-request"))
                .andExpect(jsonPath("$.errors[0].field").value("steps[0].dependOn"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown field 'dependOn'"));
        verifyNoInteractions(service);
    }

    @Test
    void submitRejectsAValueOfTheWrongType() throws Exception {
        submit("""
                        {"steps": [{"id": "a", "type": "task", "timeoutMs": "soon"}]}
                        """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "malformed-request"))
                .andExpect(jsonPath("$.errors[0].field").value("steps[0].timeoutMs"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.errors[0].message").value("Expected a JSON number"));
    }

    @Test
    void submitRejectsBrokenJson() throws Exception {
        submit("{\"steps\": [")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "malformed-request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void submitReturnsTheRuleViolationsFoundByTheService() throws Exception {
        DefinitionViolation unknownDependency = new DefinitionViolation(
                "steps[1].dependsOn[0]", ViolationCode.UNKNOWN_DEPENDENCY, "Step 'process' depends on unknown step 'x'");
        when(service.submit(any(), isNull())).thenThrow(new InvalidDefinitionException(List.of(unknownDependency)));

        submit(VALID_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "invalid-definition"))
                .andExpect(jsonPath("$.errors[0].field").value("steps[1].dependsOn[0]"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_DEPENDENCY"));
    }

    @Test
    void listsAtMostOneHundredViolations() throws Exception {
        List<DefinitionViolation> violations = IntStream.range(0, 150)
                .mapToObj(index -> new DefinitionViolation(
                        "steps[" + index + "].dependsOn[0]", ViolationCode.UNKNOWN_DEPENDENCY, "Unknown step"))
                .toList();
        when(service.submit(any(), isNull())).thenThrow(new InvalidDefinitionException(violations));

        submit(VALID_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(100))
                .andExpect(jsonPath("$.detail").value(containsString("the first 100 of 150 violations are listed")));
    }

    @Test
    void submitReturns422WhenTheKeyWasUsedForADifferentRequest() throws Exception {
        when(service.submit(any(), eq("order-42"))).thenThrow(new IdempotencyKeyReusedException());

        mockMvc.perform(post(RUNS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, "order-42")
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "idempotency-key-reused"));
    }

    @Test
    void submitReturns400ForAnInvalidIdempotencyKey() throws Exception {
        when(service.submit(any(), eq("bad key"))).thenThrow(new InvalidIdempotencyKeyException());

        mockMvc.perform(post(RUNS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, "bad key")
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "invalid-idempotency-key"));
    }

    @Test
    void submitReturns503WithRetryAfterWhenTemporalIsUnreachable() throws Exception {
        when(service.submit(any(), isNull())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        submit(VALID_BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.type").value(PROBLEM + "engine-unavailable"));
    }

    @Test
    void getReturns503WhenNoWorkerCanAnswerTheProgressQuery() throws Exception {
        StatusRuntimeException noPoller = new StatusRuntimeException(
                Status.FAILED_PRECONDITION.withDescription("no poller seen for task queue recently, worker may be down"));
        when(service.get(RUN_ID)).thenThrow(noPoller);

        mockMvc.perform(get(RUNS + "/" + RUN_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.type").value(PROBLEM + "engine-unavailable"));
    }

    @Test
    void getReturnsTheRun() throws Exception {
        when(service.get(RUN_ID)).thenReturn(run(RunStatus.RUNNING));

        mockMvc.perform(get(RUNS + "/" + RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RUN_ID))
                .andExpect(jsonPath("$.steps[0].id").value("validate"));
    }

    @Test
    void getReturns404ForAnUnknownRun() throws Exception {
        when(service.get(RUN_ID)).thenThrow(new RunNotFoundException());

        mockMvc.perform(get(RUNS + "/" + RUN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM + "run-not-found"))
                .andExpect(jsonPath("$.instance").value(RUNS + "/" + RUN_ID));
    }

    @Test
    void cancelReturns202WhenCancellationIsRequested() throws Exception {
        when(service.cancel(RUN_ID)).thenReturn(new CancellationResult(run(RunStatus.RUNNING), false));

        mockMvc.perform(post(RUNS + "/" + RUN_ID + "/cancel")).andExpect(status().isAccepted());
    }

    @Test
    void cancelReturns200WhenTheRunWasAlreadyCancelled() throws Exception {
        when(service.cancel(RUN_ID)).thenReturn(new CancellationResult(run(RunStatus.CANCELLED), true));

        mockMvc.perform(post(RUNS + "/" + RUN_ID + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelReturns409WhenTheRunAlreadyFinished() throws Exception {
        when(service.cancel(RUN_ID)).thenThrow(new RunNotCancellableException(RunStatus.SUCCEEDED));

        mockMvc.perform(post(RUNS + "/" + RUN_ID + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "run-not-cancellable"))
                .andExpect(jsonPath("$.detail").value(containsString("SUCCEEDED")));
    }

    @Test
    void unexpectedErrorsReturn500WithoutInternalDetails() throws Exception {
        when(service.get(RUN_ID)).thenThrow(new IllegalStateException("connection string with password hunter2"));

        mockMvc.perform(get(RUNS + "/" + RUN_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(PROBLEM + "internal-error"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void echoesTheRequestIdAndAddsItToProblemResponses() throws Exception {
        when(service.get(RUN_ID)).thenThrow(new RunNotFoundException());

        mockMvc.perform(get(RUNS + "/" + RUN_ID).header(ApiHeaders.REQUEST_ID, "trace-123"))
                .andExpect(header().string(ApiHeaders.REQUEST_ID, "trace-123"))
                .andExpect(jsonPath("$.requestId").value("trace-123"));
    }

    @Test
    void replacesARequestIdThatIsNotSafeToLog() throws Exception {
        when(service.get(RUN_ID)).thenReturn(run(RunStatus.RUNNING));

        mockMvc.perform(get(RUNS + "/" + RUN_ID).header(ApiHeaders.REQUEST_ID, "id with spaces"))
                .andExpect(header().string(ApiHeaders.REQUEST_ID, matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void frameworkErrorsAlsoUseProblemDetails() throws Exception {
        mockMvc.perform(post(RUNS).contentType(MediaType.TEXT_PLAIN).content("steps"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.requestId").exists());
    }

    private ResultActions submit(String body) throws Exception {
        return mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static WorkflowRunView run(RunStatus status) {
        StepView validate = new StepView(
                "validate", StepType.TASK, List.of(), StepStatus.PENDING, null, null, null, null, null);
        return new WorkflowRunView(RUN_ID, null, status, Instant.parse("2026-11-07T10:15:30Z"), null, List.of(validate));
    }
}
