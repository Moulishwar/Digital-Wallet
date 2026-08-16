package com.digitalwallet.wallet.statement;

import com.digitalwallet.wallet.ledger.JournalEntryType;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a statement, flattened from a ledger line and its journal entry inside the
 * transaction so nothing lazy is left to resolve later.
 *
 * @param amountMinor       signed — negative left the wallet, positive arrived in it
 * @param balanceAfterMinor the wallet balance immediately after this line, recorded at posting
 *                          time rather than recomputed
 */
public record StatementLine(UUID lineId,
                            Instant occurredAt,
                            JournalEntryType type,
                            String description,
                            long amountMinor,
                            long balanceAfterMinor) {
}
