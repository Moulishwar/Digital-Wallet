package com.digitalwallet.auth.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Callers must pass an already-normalized address — see {@link AppUser#normalizeEmail}. */
    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByHandle(String handle);

    boolean existsByEmail(String email);

    boolean existsByHandle(String handle);
}
