package com.digitalwallet.wallet.ledger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The outcome of a posting, assembled inside the transaction so callers never touch a lazy
 * association after the session has closed.
 *
 * @param replayed      true when this posting had already been committed by an earlier request
 *                      with the same {@code externalRef}, and nothing new was written
 * @param balancesAfter resulting balance per account touched, in minor units
 */
public record PostingResult(UUID journalEntryId,
                            String externalRef,
                            JournalEntryType type,
                            Instant postedAt,
                            boolean replayed,
                            Map<UUID, Long> balancesAfter) {

    static PostingResult of(JournalEntry entry, boolean replayed) {
        Map<UUID, Long> balances = new LinkedHashMap<>();
        for (LedgerLine line : entry.getLines()) {
            balances.put(line.getAccount().getId(), line.getBalanceAfterMinor());
        }
        return new PostingResult(entry.getId(), entry.getExternalRef(), entry.getType(),
                entry.getPostedAt(), replayed, Map.copyOf(balances));
    }
}
