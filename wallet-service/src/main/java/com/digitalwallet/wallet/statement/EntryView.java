package com.digitalwallet.wallet.statement;

import com.digitalwallet.wallet.ledger.JournalEntryType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A journal entry from one participant's point of view.
 *
 * @param sumMinor the signed total of every line. Always zero; included so a client can show the
 *                 invariant rather than take it on trust
 */
public record EntryView(UUID journalEntryId,
                        JournalEntryType type,
                        Instant postedAt,
                        String description,
                        String memo,
                        List<Line> lines,
                        long sumMinor) {

    /** Whose line this is, relative to the caller. */
    public enum Party {
        YOU,
        COUNTERPARTY,
        /** The account standing for money arriving from outside the platform. */
        FUNDING,
        FEES
    }

    /**
     * @param balanceAfterMinor set only on the caller's own line
     */
    public record Line(Party party,
                       String counterpartyHandle,
                       String counterpartyName,
                       long amountMinor,
                       Long balanceAfterMinor) {
    }
}
