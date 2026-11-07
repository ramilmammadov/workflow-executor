package io.workflowexecutor.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.workflowexecutor.definition.WorkflowDefinitionRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * SHA-256 of the request in a canonical JSON form. Stored with the run, so a repeated Idempotency-Key can be
 * told apart from a different request that reuses the key by mistake.
 *
 * <p>The canonical form leaves out absent and empty values and sorts properties by name. A retry therefore
 * keeps its fingerprint even if the API gains a new optional field in between, and an empty list counts the
 * same as no list.
 */
final class RequestFingerprints {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_EMPTY))
            .build();

    private RequestFingerprints() {}

    static String of(WorkflowDefinitionRequest request) {
        return HexFormat.of().formatHex(sha256(CANONICAL_JSON.writeValueAsBytes(request)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(input);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available on this JVM", unavailable);
        }
    }
}
