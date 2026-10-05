package com.digitalwallet.transfer.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a transfer has got to.
 *
 * <p>The interesting one is {@link #NEEDS_RECONCILIATION}. It exists because a call to
 * wallet-service can fail in a way that does not say whether the money moved — a timeout, a dropped
 * connection, a 502 from something in between. Guessing in that moment is how you either lose a
 * payment or make it twice, so the transfer records that the answer is not yet known and the
 * reconciliation sweep goes and finds out.
 */
public enum TransferStatus {

    /** Recorded, not yet posted. Nothing has moved. */
    PENDING,

    /** wallet-service confirmed the posting. Money has moved. Terminal. */
    COMPLETED,

    /** wallet-service refused, for a reason the sender can understand. Nothing moved. Terminal. */
    FAILED,

    /** The posting's outcome is unknown and must be established before the transfer can settle. */
    NEEDS_RECONCILIATION;

    private static final Set<TransferStatus> TERMINAL = EnumSet.of(COMPLETED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Whether this status may move to {@code next}.
     *
     * <p>Terminal means terminal: once a transfer has completed or failed, nothing — not a retry,
     * not a late reconciliation sweep finding a stale row — may move it again. A COMPLETED transfer
     * that could be reopened is a transfer that could be paid twice.
     */
    public boolean canTransitionTo(TransferStatus next) {
        return switch (this) {
            case PENDING -> next == COMPLETED || next == FAILED || next == NEEDS_RECONCILIATION;
            case NEEDS_RECONCILIATION -> next == COMPLETED || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
