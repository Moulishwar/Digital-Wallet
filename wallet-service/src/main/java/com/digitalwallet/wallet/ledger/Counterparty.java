package com.digitalwallet.wallet.ledger;

/**
 * The other party to one side of a movement, as the statement should name them.
 *
 * <p>A label, not a reference. It is copied onto the ledger line at posting time and never resolved
 * again, so it records who the money went to <em>when it went</em>. This service has no user
 * directory and does not need one.
 *
 * @param handle      the other party's public handle, without a leading {@code @}
 * @param displayName their name as shown to other users; may be null
 */
public record Counterparty(String handle, String displayName) {

    public Counterparty {
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("A counterparty needs a handle");
        }
    }

    /** Null when no handle was supplied, so callers can pass optional request fields straight in. */
    public static Counterparty ofNullable(String handle, String displayName) {
        return handle == null || handle.isBlank() ? null : new Counterparty(handle, displayName);
    }
}
