package io.workflowexecutor.definition;

import static io.workflowexecutor.TestDefinitions.PROPERTIES;
import static io.workflowexecutor.TestDefinitions.VALIDATOR;
import static io.workflowexecutor.TestDefinitions.definition;
import static io.workflowexecutor.TestDefinitions.delay;
import static io.workflowexecutor.TestDefinitions.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefinitionValidatorTest {

    private final DefinitionValidator validator = VALIDATOR;

    @Test
    void acceptsAValidGraph() {
        WorkflowDefinitionRequest request = definition(task("validate"), task("process", "validate"), delay("wait", 10, "process"));

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnsupportedStepType() {
        StepRequest email = new StepRequest("send", "email", null, null, null, null, null);

        assertThat(violationsOf(definition(email)))
                .containsExactly(new DefinitionViolation(
                        "steps[0].type",
                        ViolationCode.UNSUPPORTED_STEP_TYPE,
                        "Unsupported step type 'email'; supported types are: task, delay"));
    }

    @Test
    void rejectsADelayStepWithoutDelayMs() {
        StepRequest wait = new StepRequest("wait", "delay", null, null, null, null, null);

        assertThat(violationsOf(definition(wait)))
                .extracting(DefinitionViolation::field, DefinitionViolation::code)
                .containsExactly(tuple("steps[0].delayMs", ViolationCode.MISSING_FIELD));
    }

    @Test
    void rejectsFieldsThatDoNotApplyToTheStepType() {
        StepRequest taskWithDelay = new StepRequest("work", "task", null, null, null, 100L, null);
        StepRequest delayWithRetry = new StepRequest("wait", "delay", null, new RetryRequest(2), 1_000L, 100L, null);

        assertThat(violationsOf(definition(taskWithDelay, delayWithRetry)))
                .extracting(DefinitionViolation::field)
                .containsExactly("steps[0].delayMs", "steps[1].retry", "steps[1].timeoutMs");
    }

    @Test
    void rejectsDuplicateStepIds() {
        assertThat(violationsOf(definition(task("a"), task("a"))))
                .containsExactly(new DefinitionViolation(
                        "steps[1].id", ViolationCode.DUPLICATE_STEP_ID, "Step id 'a' is used more than once"));
    }

    @Test
    void rejectsUnknownSelfAndDuplicateDependencies() {
        WorkflowDefinitionRequest request = definition(task("a"), task("b", "missing", "b", "a", "a"));

        assertThat(violationsOf(request))
                .extracting(DefinitionViolation::field, DefinitionViolation::code)
                .containsExactly(
                        tuple("steps[1].dependsOn[0]", ViolationCode.UNKNOWN_DEPENDENCY),
                        tuple("steps[1].dependsOn[1]", ViolationCode.SELF_DEPENDENCY),
                        tuple("steps[1].dependsOn[3]", ViolationCode.DUPLICATE_DEPENDENCY));
    }

    @Test
    void rejectsACycleAndNamesTheStepsInIt() {
        WorkflowDefinitionRequest request = definition(task("a", "c"), task("b", "a"), task("c", "b"));

        assertThat(violationsOf(request))
                .containsExactly(new DefinitionViolation(
                        "steps",
                        ViolationCode.DEPENDENCY_CYCLE,
                        "Steps depend on each other in a cycle: a -> c -> b -> a (each step depends on the next)"));
    }

    @Test
    void reportsEveryViolationAtOnceIncludingACycle() {
        StepRequest unsupported = new StepRequest("d", "email", null, null, null, null, null);
        WorkflowDefinitionRequest request =
                definition(task("a", "c"), task("b", "a", "missing"), task("c", "b"), unsupported);

        assertThat(violationsOf(request))
                .extracting(DefinitionViolation::field, DefinitionViolation::code)
                .containsExactly(
                        tuple("steps[3].type", ViolationCode.UNSUPPORTED_STEP_TYPE),
                        tuple("steps[1].dependsOn[1]", ViolationCode.UNKNOWN_DEPENDENCY),
                        tuple("steps", ViolationCode.DEPENDENCY_CYCLE));
    }

    @Test
    void skipsTheCycleCheckWhenStepIdsAreNotUnique() {
        assertThat(violationsOf(definition(task("x", "x"), task("x", "ghost"))))
                .extracting(DefinitionViolation::code)
                .containsExactly(
                        ViolationCode.DUPLICATE_STEP_ID, ViolationCode.SELF_DEPENDENCY, ViolationCode.UNKNOWN_DEPENDENCY);
    }

    @Test
    void rejectsDelaysThatCannotFinishWithinTheExecutionTimeout() {
        long timeoutMs = PROPERTIES.temporal().executionTimeout().toMillis();
        WorkflowDefinitionRequest request =
                definition(delay("first", timeoutMs / 2), task("work", "first"), delay("second", timeoutMs / 2, "work"));

        assertThat(violationsOf(request))
                .containsExactly(new DefinitionViolation(
                        "steps",
                        ViolationCode.EXCEEDS_EXECUTION_TIMEOUT,
                        ("Delays along the longest dependency chain add up to %d ms; "
                                        + "a run must finish within the execution timeout of %d ms")
                                .formatted(timeoutMs, timeoutMs)));
    }

    @Test
    void acceptsParallelDelaysWhoseLongestChainFitsWithinTheExecutionTimeout() {
        long halfTimeoutMs = PROPERTIES.temporal().executionTimeout().toMillis() / 2;
        WorkflowDefinitionRequest request =
                definition(delay("left", halfTimeoutMs), delay("right", halfTimeoutMs), task("join", "left", "right"));

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    private List<DefinitionViolation> violationsOf(WorkflowDefinitionRequest request) {
        return catchThrowableOfType(InvalidDefinitionException.class, () -> validator.validate(request))
                .violations();
    }
}
