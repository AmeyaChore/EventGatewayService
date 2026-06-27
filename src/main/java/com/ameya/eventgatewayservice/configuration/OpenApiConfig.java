package com.ameya.eventgatewayservice.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata shown at the top of the generated Swagger UI
 * (/swagger-ui/index.html) and in the raw OpenAPI spec (/v3/api-docs).
 * No per-endpoint config needed here - springdoc-openapi introspects
 * @RestController classes, @RequestMapping/@GetMapping/@PostMapping,
 * @Valid request bodies, and @PathVariable/@RequestParam automatically.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event Gateway API")
                        .description("""
                                Public-facing entry point for the Event Ledger system. \
                                Receives financial transaction events, enforces idempotency, \
                                stores event records locally, and forwards transactions to the \
                                internal Account Service. Also exposes read-through proxies for \
                                account balance and account detail lookups.""")
                        .version("v1")
                        .contact(new Contact().name("Event Ledger Take-Home Project")));
    }
}