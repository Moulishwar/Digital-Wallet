package com.digitalwallet.wallet.account;

public enum AccountType {

    /** A customer's wallet. Exactly one per user, and it may never go negative. */
    USER_WALLET,

    /**
     * The counterparty for money entering or leaving the platform. Runs a large negative balance
     * by design; its magnitude is the total value held in user wallets.
     */
    SYSTEM_FUNDING,

    /** Reserved for fee collection. Unused in v1. */
    SYSTEM_FEES;

    public boolean isSystemAccount() {
        return this != USER_WALLET;
    }
}
