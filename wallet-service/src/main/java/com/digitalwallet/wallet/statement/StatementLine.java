package com.digitalwallet.wallet.statement;

import com.digitalwallet.wallet.ledger.JournalEntryType;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a statement, flattened from a ledger line and its journal entry inside the
 * transaction so nothing lazy is left to resolve later.
 *
 * @param amountMinor       signed: negative left the wallet, positive arrived in it
 * @param balanceAfterMinor the wallet balance immediately after this line, recorded at posting
 *                          time rather than recomputed
 * @param counterpartyHandle who the money came from or went to; null for a top-up
 * @param counterpartyName  their display name at the time; null when not recorded
 * @param memo              the sender's note, the same on both parties' statements; may be null
 */
public record StatementLine(UUID lineId,
                            UUID journalEntryId,
                            Instant occurredAt,
                            JournalEntryType type,
                            String description,
                            long amountMinor,
                            long balanceAfterMinor,
                            String counterpartyHandle,
                            String counterpartyName,
                            String memo) {
}
