package com.digitalwallet.wallet.account;

public enum AccountStatus {

    ACTIVE,

    /** Temporarily blocked — no postings in or out, but the account still exists. */
    FROZEN,

    /** Permanently closed. */
    CLOSED;

    public boolean canPost() {
        return this == ACTIVE;
    }
}
