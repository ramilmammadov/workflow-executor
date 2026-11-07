package io.workflowexecutor.run;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Run ids are UUIDs and double as Temporal workflow ids. A run submitted with an Idempotency-Key gets a
 * name-based UUID derived from the key, so a repeated request maps to the same workflow id and
 * Temporal's workflow-id uniqueness rejects the duplicate start. No extra storage is needed.
 */
final class RunIds {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{1,255}$");
    private static final String KEY_NAMESPACE = "workflow-executor:idempotency-key:";

    private RunIds() {}

    static String random() {
        return UUID.randomUUID().toString();
    }

    static String fromIdempotencyKey(String idempotencyKey) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        byte[] name = (KEY_NAMESPACE + idempotencyKey).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(name).toString();
    }

    static boolean isWellFormed(String runId) {
        return CANONICAL_UUID.matcher(runId).matches();
    }
}
