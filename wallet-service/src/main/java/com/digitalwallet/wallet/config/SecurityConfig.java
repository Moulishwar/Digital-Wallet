package com.digitalwallet.wallet.config;

import com.digitalwallet.common.security.ProblemDetailAuthEntryPoints;
import com.digitalwallet.common.security.ServiceCredential;
import com.digitalwallet.common.security.ServiceCredentialFilter;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * wallet-service verifies tokens; it never issues them.
 *
 * <p>It holds only auth-service's public key, fetched once from the JWKS endpoint and cached. That
 * asymmetry is the point: if this service were compromised, the attacker still could not mint a
 * token for anyone, because the private key is not here.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ObjectMapper objectMapper,
                                           @Value("${security.service-credential}") String serviceCredential)
            throws Exception {
        http
                // Runs before bearer-token authentication so a service call is recognised as a
                // service call, rather than being examined for a user token it will never carry.
                .addFilterBefore(new ServiceCredentialFilter(serviceCredential),
                        BearerTokenAuthenticationFilter.class)
                // Stateless bearer-token API with no cookies, so there is no ambient credential a
                // cross-site request could ride on. See auth-service SecurityConfig for the longer
                // version of this reasoning.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // Service-to-service routes. Two independent protections, because either
                        // one alone is thin.
                        //
                        // The gateway has no route to /internal, so these are not addressable from
                        // outside. But that is network-level protection, and network-level
                        // protection assumes nothing hostile ever gets inside the perimeter,
                        // which is exactly the assumption that fails. These endpoints move money
                        // and provision wallets, so they also require a credential a caller has to
                        // hold, not merely a position on the network.
                        //
                        // Note this is ROLE_SERVICE, never a user role: no user token, however
                        // legitimate, can post arbitrary movements into the ledger.
                        .requestMatchers("/internal/**").hasAuthority(ServiceCredential.ROLE)

                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
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
