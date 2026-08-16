package com.digitalwallet.auth.token;

import com.digitalwallet.auth.config.JwtProperties;
import com.digitalwallet.auth.user.AppUser;
import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>Refresh tokens are opaque random strings rather than JWTs, because unlike an access token they
 * must be revocable. A JWT is valid until it expires no matter what the server thinks; a row in a
 * table can be marked dead the moment it needs to be.
 *
 * <p>Every use rotates the token. That single rule is what makes theft detectable — see
 * {@link #rotate}.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32; // 256 bits

    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRevocationService sessionRevocationService;
    private final JwtProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               SessionRevocationService sessionRevocationService,
                               JwtProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionRevocationService = sessionRevocationService;
        this.properties = properties;
    }

    /**
     * @return the raw token, which is returned to the client and then unrecoverable — only its
     *         hash is kept
     */
    @Transactional
    public String issue(AppUser user) {
        String raw = randomToken();
        RefreshToken token = new RefreshToken(user, hash(raw), Instant.now().plus(properties.refreshTokenTtl()));
        refreshTokenRepository.save(token);
        return raw;
    }

    /**
     * Exchanges a refresh token for a new one, invalidating the old.
     *
     * <p>The interesting case is a token that is presented after it has already been rotated away.
     * A correct client never does that — it discards the old token the moment it receives a new
     * one. So a second use means two parties hold the same token, and there is no way to tell the
     * legitimate client from the thief. Rather than guess, every live token for that user is
     * revoked and both are forced to log in again.
     *
     * @return the new raw refresh token and the user it belongs to
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken presented = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid refresh token"));

        if (presented.isRevoked()) {
            log.warn("Refresh token reuse detected for user {} — revoking all sessions",
                    presented.getUser().getId());
            // Committed in its own transaction, because the exception below would otherwise roll
            // the revocation back and leave the stolen token usable. See SessionRevocationService.
            sessionRevocationService.revokeAllForUser(presented.getUser().getId());
            throw new ApiException(ErrorCode.UNAUTHORIZED,
                    "Refresh token has already been used. All sessions have been revoked.");
        }

        if (!presented.isUsable()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid refresh token");
        }

        AppUser user = presented.getUser();
        String newRaw = randomToken();
        RefreshToken replacement = new RefreshToken(user, hash(newRaw),
                Instant.now().plus(properties.refreshTokenTtl()));
        refreshTokenRepository.save(replacement);
        presented.rotateTo(replacement);

        return new RotationResult(user, newRaw);
    }

    /** Ends one session. Unknown or already-dead tokens are ignored — logout is idempotent. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(RefreshToken::revoke);
    }

    public int revokeAllForUser(AppUser user) {
        return sessionRevocationService.revokeAllForUser(user.getId());
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, not BCrypt.
     *
     * <p>BCrypt is deliberately slow to make guessing a human-chosen password expensive. This value
     * is 256 bits of cryptographic randomness, so there is nothing to guess — and a slow hash would
     * mean an unindexable lookup on every refresh. A fast digest is the right tool here.
     */
    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    public record RotationResult(AppUser user, String rawRefreshToken) {
    }
}
