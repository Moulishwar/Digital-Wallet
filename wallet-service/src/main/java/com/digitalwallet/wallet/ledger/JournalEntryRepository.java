package com.digitalwallet.wallet.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /**
     * Looks up a posting by the caller's reference.
     *
     * <p>This is how a retry becomes safe: if the caller is unsure whether an earlier attempt
     * committed, it asks here. Combined with the unique constraint on the column, the answer is
     * unambiguous.
     */
    Optional<JournalEntry> findByExternalRef(String externalRef);

    boolean existsByExternalRef(String externalRef);
}
