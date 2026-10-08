package com.digitalwallet.auth.config;

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
    public OpenAPI authServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Auth Service API")
                        .version("v1")
                        .description("""
                                Users, credentials, roles and the token lifecycle.

                                Access tokens are RS256 JWTs valid for 15 minutes. Every other service \
                                verifies them offline using the public key at /.well-known/jwks.json, \
                                so no service ever calls back here to check a request.

                                Refresh tokens are opaque, stored only as a SHA-256 hash, and single \
                                use: refreshing returns a new one and kills the old. Presenting an \
                                already-used refresh token is treated as theft and revokes every \
                                session for that user.
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
