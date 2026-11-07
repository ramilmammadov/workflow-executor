package io.workflowexecutor.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI workflowExecutorOpenApi() {
        Info info = new Info()
                .title("Workflow Executor API")
                .version("v1")
                .description("Accepts workflow definitions whose steps depend on each other and runs them on Temporal. "
                        + "Errors use RFC 9457 problem details.");
        return new OpenAPI().info(info);
    }
}
