package com.digitalwallet.auth.config;

import com.digitalwallet.common.security.ProblemDetailAuthEntryPoints;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt at cost 12 — roughly 250ms per hash on current hardware.
     *
     * <p>Slow on purpose. The work factor is what makes an offline attack against a stolen password
     * table expensive, and it is the one security parameter here that should be raised over time as
     * hardware improves. BCrypt stores the cost in the hash itself, so raising it later still
     * verifies existing passwords.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                // No CSRF tokens, and that is correct here rather than a shortcut. CSRF exists
                // because browsers attach cookies automatically. Everything except the token
                // endpoints is authenticated by an Authorization header a browser never sends on
                // its own. The one cookie there is — the refresh cookie — is SameSite=Strict, and
                // is honoured only on requests carrying a custom header another site cannot add
                // without a CORS preflight (see RefreshTokenCookie).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout").permitAll()
                        .requestMatchers(
                                "/.well-known/jwks.json",
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        // Default deny. Any endpoint added later is protected unless someone
                        // deliberately opens it, which is the safe direction for this to fail in.
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

    /**
     * Reads authorities from the {@code roles} claim.
     *
     * <p>The values already carry the {@code ROLE_} prefix, so the converter's default prefix is
     * cleared — otherwise Spring would look for {@code ROLE_ROLE_ADMIN} and every role check would
     * silently fail.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
