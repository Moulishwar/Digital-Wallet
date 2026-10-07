package com.digitalwallet.gateway.config;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Gives the API reference a short, stable address: {@code /api/docs}.
 */
@Configuration
public class DocsConfig {

    @Bean
    public RouterFunction<ServerResponse> docsEntryPoint() {
        return route(RequestPredicates.GET("/api/docs"),
                request -> ServerResponse.temporaryRedirect(URI.create("/api/docs/swagger-ui.html")).build());
    }
}
