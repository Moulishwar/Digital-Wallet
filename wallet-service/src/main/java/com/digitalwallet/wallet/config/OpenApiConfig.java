package com.digitalwallet.wallet.config;

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
    public OpenAPI walletServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet Service API")
                        .version("v1")
                        .description("""
                                Accounts, the double-entry ledger, balances and statements.

                                Every movement of value is a journal entry whose signed ledger lines sum \
                                to zero. Balances are derived from that history and cached for speed; \
                                GET /api/admin/reconciliation proves the two agree.

                                Amounts are always integers in minor units (paise): 12345 means 123.45. \
                                No amount is ever sent as a decimal number.

                                Authenticate with an RS256 bearer token from auth-service. Signatures \
                                are verified locally against its published JWKS, so this service never \
                                calls back to auth-service to check a request.
                                """)
                        .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                // Relative, so "Try it out" calls whichever origin served the docs: the gateway's
                // /api/docs, this service directly, or the live demo, with no host configured.
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
