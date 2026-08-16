package com.digitalwallet.auth.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup is by hash — the raw token is never stored, so it cannot be searched for. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token belonging to a user.
     *
     * <p>Used when a rotated token is presented a second time. At that point there are two parties
     * holding the same token and there is no way to tell which is the legitimate one, so the safe
     * move is to invalidate everything and make both re-authenticate.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    long countByUserIdAndRevokedAtIsNull(UUID userId);
}
