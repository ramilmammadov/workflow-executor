package io.workflowexecutor.execution;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.testing.WorkflowReplayer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Replays run histories recorded with the first version of the workflow against the current code. A failure
 * means a change would break runs already in flight: guard that change with {@code Workflow.getVersion}
 * instead of changing the logic in place.
 */
class ReplayCompatibilityTest {

    @ParameterizedTest
    @ValueSource(strings = {"histories/mixed-outcomes.json", "histories/cancelled-run.json"})
    void currentCodeReplaysRecordedHistories(String history) throws IOException {
        String recordedHistory = readResource(history);

        assertThatCode(() -> WorkflowReplayer.replayWorkflowExecution(recordedHistory, DagWorkflowImpl.class))
                .doesNotThrowAnyException();
    }

    /** Read through the class loader: the SDK's own resource helper fails when the checkout path contains a space. */
    private static String readResource(String name) throws IOException {
        try (InputStream stream = ReplayCompatibilityTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Test resource not found on the classpath: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
