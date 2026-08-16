package com.digitalwallet.wallet.ledger;

public enum JournalEntryType {

    /** Value entering the platform: debit SYSTEM_FUNDING, credit the user's wallet. */
    TOPUP,

    /** Value moving between two user wallets. */
    TRANSFER,

    /** A compensating entry that undoes an earlier one. History is never edited. */
    REVERSAL
}
