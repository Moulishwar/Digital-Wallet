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
 * <p>This replaces the M1 {@code X-User-Id} header placeholder. The seam held: no controller or
 * service changed, because they all depend on {@link CurrentUserProvider} rather than on how
 * identity happens to arrive.
 *
 * <p>The token has already been checked by the resource-server filter — signature verified against
 * auth-service's published key, expiry enforced — before anything reaches here. So the subject
 * claim can be trusted, which is exactly the difference from M1: a header the client wrote versus a
 * claim signed by a private key this service does not hold.
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
