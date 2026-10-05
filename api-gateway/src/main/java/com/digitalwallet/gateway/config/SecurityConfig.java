package com.digitalwallet.gateway.config;

import com.digitalwallet.gateway.error.GatewayProblem;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * The gateway's job at the edge: reject what obviously should not reach a service.
 *
 * <p>It verifies the token here <em>and</em> every service verifies it again. That is not redundant
 * work done twice by accident — it is defence in depth. The gateway check means an expired or forged
 * token never costs a service a thread or a database connection; the service check means nothing is
 * trusted merely for having arrived from inside the network.
 *
 * <p>The gateway also strips nothing and adds no identity headers. Downstream services read identity
 * from the signed token itself, so there is no header for anything inside the network to forge.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ObjectMapper objectMapper,
                                                         CorsConfigurationSource corsConfigurationSource) {
        return http
                // Stateless bearer-token API with no cookies, so there is no ambient credential a
                // cross-site request could ride on.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                .authorizeExchange(exchange -> exchange
                        // Signing up, logging in, refreshing and logging out all necessarily happen
                        // without a usable access token.
                        .pathMatchers("/api/auth/**").permitAll()
                        // Public by design: it is the public half of the signing key.
                        .pathMatchers("/.well-known/jwks.json").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()

                        // No route exists to /internal/** anyway, but denying it explicitly means a
                        // route added carelessly later still does not expose it.
                        .pathMatchers("/internal/**").denyAll()

                        .anyExchange().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, denied) -> GatewayProblem.write(
                                exchange, objectMapper, HttpStatus.UNAUTHORIZED,
                                "unauthorized", "Unauthorized",
                                "A valid bearer token is required"))
                        .accessDeniedHandler((exchange, denied) -> GatewayProblem.write(
                                exchange, objectMapper, HttpStatus.FORBIDDEN,
                                "forbidden", "Forbidden",
                                "You are not permitted to access this resource")))
                .build();
    }

    /**
     * Verifies signature, expiry <em>and</em> issuer.
     *
     * <p>A signature alone only proves the token was signed by some key in the configured key set,
     * not that it was minted for this system. The issuer check closes that gap.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${security.jwt.expected-issuer}") String expectedIssuer) {

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(expectedIssuer));

        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * CORS, off unless origins are configured.
     *
     * <p>No wildcard default. Allowing any origin with credentials would let any page a user visits
     * make authenticated calls to this API as them, which is the whole attack CORS exists to
     * prevent.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${gateway.cors.allowed-origins:}") String allowedOrigins) {

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return source;
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split("\\s*,\\s*")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
