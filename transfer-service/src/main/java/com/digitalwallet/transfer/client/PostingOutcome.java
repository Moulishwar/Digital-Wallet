package com.digitalwallet.transfer.client;

import com.digitalwallet.transfer.domain.FailureReason;
import java.util.UUID;

/**
 * What came back from asking wallet-service to post a movement.
 *
 * <p>Three outcomes, and the third is the one that matters. A call can fail without telling you
 * whether it took effect — a read timeout, a dropped connection, a 502 from something in the middle
 * — and in that moment "did the money move?" genuinely has no answer yet. Modelling that as its own
 * case forces every caller to handle it, rather than letting it be quietly lumped in with failure
 * and turned into a lost payment.
 */
public sealed interface PostingOutcome {

    /** The posting committed. */
    record Posted(UUID journalEntryId, long senderBalanceAfterMinor, boolean replayed)
            implements PostingOutcome {
    }

    /** wallet-service refused, definitively. Nothing moved, and retrying as-is will not help. */
    record Rejected(FailureReason reason, String detail) implements PostingOutcome {
    }

    /**
     * The outcome is unknown and must be established by asking again later, keyed on the transfer
     * id — which is safe precisely because that reference is unique over in the ledger.
     */
    record Unknown(String cause) implements PostingOutcome {
    }
}
