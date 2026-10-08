package com.digitalwallet.transfer.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transferServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Transfer Service API")
                        .version("v1")
                        .description("""
                                Peer-to-peer transfers: the intent to send money, and what became of it.

                                This service holds no balances. It records that a user asked to send \
                                money and asks wallet-service to make the movement, which happens there \
                                as a single balanced posting.

                                POST /api/transfers requires an Idempotency-Key. Retrying with the same \
                                key returns the original outcome rather than sending again. A 202 means \
                                the request is recorded but wallet-service did not confirm whether it \
                                took effect; poll GET /api/transfers/{id} until it settles.

                                Amounts are always integers in minor units (paise): 12345 means 123.45.

                                Authenticate with an RS256 bearer token from auth-service.
                                """)
                        .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                // Relative, so "Try it out" calls whichever origin served the docs: the gateway's
                // /api/docs, this service directly, or a deployment behind a proxy.
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
