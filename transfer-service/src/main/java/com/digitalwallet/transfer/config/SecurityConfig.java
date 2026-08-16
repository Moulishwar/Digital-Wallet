package com.digitalwallet.transfer.config;

import com.digitalwallet.common.security.ProblemDetailAuthEntryPoints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * transfer-service verifies tokens; it never issues them, and it holds no signing key.
 *
 * <p>Every route here is authenticated. There is no public surface: sending money and reading your
 * own history both require knowing who is asking, and nothing else lives at this service.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                // Stateless bearer-token API with no cookies, so there is no ambient credential a
                // cross-site request could ride on.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(ProblemDetailAuthEntryPoints.unauthorized(objectMapper))
                        .accessDeniedHandler(ProblemDetailAuthEntryPoints.forbidden(objectMapper)))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(ProblemDetailAuthEntryPoints.unauthorized(objectMapper))
                        .accessDeniedHandler(ProblemDetailAuthEntryPoints.forbidden(objectMapper)));

        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        // The claim values already include the ROLE_ prefix; leaving Spring's default in place
        // would produce ROLE_ROLE_ADMIN and silently break every role check.
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
