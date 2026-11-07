package io.workflowexecutor.config;

import io.workflowexecutor.definition.DefinitionMapper;
import io.workflowexecutor.definition.DefinitionValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the definition rules to configuration, so the definition package itself knows nothing about Spring config. */
@Configuration(proxyBeanMethods = false)
class DefinitionConfig {

    @Bean
    DefinitionValidator definitionValidator(ExecutorProperties properties) {
        return new DefinitionValidator(properties.temporal().executionTimeout());
    }

    @Bean
    DefinitionMapper definitionMapper(ExecutorProperties properties) {
        return new DefinitionMapper(properties.stepDefaults());
    }
}
