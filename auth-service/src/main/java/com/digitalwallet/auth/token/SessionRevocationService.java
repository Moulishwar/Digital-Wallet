package com.digitalwallet.auth.token;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every live session for a user, in its own transaction.
 *
 * <p>The separate transaction is the entire reason this class exists, and it is not a style choice.
 * Reuse detection has to do two things: revoke everything, and then reject the request. Rejecting
 * means throwing, and a thrown exception rolls the surrounding transaction back — which would undo
 * the revocation and leave the stolen token working. The caller would still get a 401 and the bug
 * would look like correct behaviour from the outside.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this work independently, so
 * the revocation survives the exception that follows it. A separate bean is required because
 * Spring's propagation is applied by a proxy, and a call to another method on the same object never
 * passes through that proxy.
 */
@Service
public class SessionRevocationService {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public SessionRevocationService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(UUID userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.info("Revoked {} live session(s) for user {}", revoked, userId);
        return revoked;
    }
}
