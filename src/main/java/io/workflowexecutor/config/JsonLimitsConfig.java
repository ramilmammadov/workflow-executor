package io.workflowexecutor.config;

import io.workflowexecutor.definition.DefinitionLimits;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;

/**
 * Caps the size of a JSON request body while it is parsed, so an oversized body is rejected before it fills
 * memory. A gateway in front of the service should enforce the same limit; this is the service's own guard.
 */
@Configuration(proxyBeanMethods = false)
class JsonLimitsConfig {

    @Bean
    JsonFactoryBuilderCustomizer requestSizeLimit() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(DefinitionLimits.MAX_REQUEST_BYTES)
                .build();
        return factory -> factory.streamReadConstraints(constraints);
    }
}
