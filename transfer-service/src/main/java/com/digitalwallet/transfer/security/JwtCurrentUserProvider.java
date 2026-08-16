package com.digitalwallet.transfer.security;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller from the verified JWT.
 *
 * <p>The token has already been checked by the resource-server filter — signature verified against
 * auth-service's published key, issuer and expiry enforced — before anything reaches here, so the
 * subject claim can be trusted. This service holds no signing key and could not mint a token if it
 * wanted to.
 */
@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UUID requireCurrentUserId() {
        Jwt jwt = requireJwt();

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Token has no subject claim");
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            // auth-service always puts a user id here. Anything else means a token from a source
            // this service should not be honouring.
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Token subject is not a valid user id");
        }
    }

    @Override
    public String requireCurrentToken() {
        return requireJwt().getTokenValue();
    }

    private Jwt requireJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required");
        }
        return jwt;
    }
}
