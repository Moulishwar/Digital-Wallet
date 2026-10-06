package com.digitalwallet.wallet.security;

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
 * auth-service's published key, expiry enforced — before anything reaches here. So the subject
 * claim can be trusted: it was signed by a private key this service does not hold, not written by
 * the client.
 */
@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UUID requireCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication is required");
        }

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
}
