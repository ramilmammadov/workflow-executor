package io.workflowexecutor.run;

import static io.workflowexecutor.TestDefinitions.definition;
import static io.workflowexecutor.TestDefinitions.task;
import static org.assertj.core.api.Assertions.assertThat;

import io.workflowexecutor.definition.StepRequest;
import io.workflowexecutor.definition.WorkflowDefinitionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestFingerprintsTest {

    @Test
    void theSameRequestAlwaysHasTheSameFingerprint() {
        assertThat(RequestFingerprints.of(definition(task("a"), task("b", "a"))))
                .isEqualTo(RequestFingerprints.of(definition(task("a"), task("b", "a"))));
    }

    @Test
    void anEmptyDependencyListCountsAsNoList() {
        StepRequest withEmptyList = new StepRequest("a", "task", List.of(), null, null, null, null);
        StepRequest withoutList = new StepRequest("a", "task", null, null, null, null, null);

        assertThat(RequestFingerprints.of(definition(withEmptyList)))
                .isEqualTo(RequestFingerprints.of(definition(withoutList)));
    }

    @Test
    void aDifferentRequestHasADifferentFingerprint() {
        WorkflowDefinitionRequest named = new WorkflowDefinitionRequest("nightly", List.of(task("a")));

        assertThat(RequestFingerprints.of(named)).isNotEqualTo(RequestFingerprints.of(definition(task("a"))));
        assertThat(RequestFingerprints.of(definition(task("a"))))
                .isNotEqualTo(RequestFingerprints.of(definition(task("b"))));
    }
}
