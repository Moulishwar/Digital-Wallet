package com.digitalwallet.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Grants {@link ServiceCredential#ROLE} to a caller presenting the shared service credential.
 *
 * <p>This filter only ever <em>adds</em> authority; it never rejects anything. Whether a given route
 * requires the credential is a question for the security configuration, which is where such
 * decisions are visible and reviewable. A filter that decided for itself which paths to guard would
 * be a second, hidden copy of the access rules.
 */
public class ServiceCredentialFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceCredentialFilter.class);

    private final byte[] expectedCredential;

    public ServiceCredentialFilter(String expectedCredential) {
        if (expectedCredential == null || expectedCredential.isBlank()) {
            // Never fall back to "allow everything" or to a default value. A missing credential is
            // a misconfiguration, and it must stop the service starting rather than quietly
            // leaving the money-moving endpoints open (DESIGN.md section 9.5).
            throw new IllegalStateException(
                    "No service credential is configured. Set SERVICE_CREDENTIAL.");
        }
        this.expectedCredential = expectedCredential.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader(ServiceCredential.HEADER);

        if (presented != null && matches(presented)) {
            SecurityContextHolder.getContext().setAuthentication(new ServiceAuthentication());
        } else if (presented != null) {
            log.warn("Rejected an invalid service credential on {}", request.getRequestURI());
        }

        chain.doFilter(request, response);
    }

    /**
     * Constant-time comparison.
     *
     * <p>{@code String.equals} returns as soon as two bytes differ, so how long it takes leaks how
     * much of the value was right. That is enough to recover a secret one character at a time given
     * enough attempts, and it costs nothing to avoid.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedCredential);
    }

    /** A caller that is a service, not a person. It has no user id, and deliberately no way to get one. */
    private static final class ServiceAuthentication extends AbstractAuthenticationToken {

        private ServiceAuthentication() {
            super(List.of(new SimpleGrantedAuthority(ServiceCredential.ROLE)));
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            // Never retained, so it cannot be logged or serialized by accident.
            return null;
        }

        @Override
        public Object getPrincipal() {
            return "service";
        }
    }
}
