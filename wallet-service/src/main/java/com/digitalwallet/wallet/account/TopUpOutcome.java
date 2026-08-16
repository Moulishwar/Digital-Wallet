package com.digitalwallet.wallet.account;

import java.util.UUID;

/**
 * Result of a top-up, already resolved to the wallet's own balance so the controller does not have
 * to pick its account out of the posting's per-account map.
 *
 * @param replayed true when the idempotency key had already been used and no new money moved
 */
public record TopUpOutcome(UUID journalEntryId, long balanceMinor, boolean replayed) {
}
