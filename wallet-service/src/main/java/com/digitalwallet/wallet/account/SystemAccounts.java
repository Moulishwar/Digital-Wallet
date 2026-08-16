package com.digitalwallet.wallet.account;

import java.util.UUID;

/**
 * Fixed ids for the internal accounts seeded by migration {@code V3__system_accounts.sql}.
 *
 * <p>They are constants rather than lookups so that a posting does not need a query to find its
 * counterparty, and so the ids are identical in every environment.
 */
public final class SystemAccounts {

    public static final UUID FUNDING_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final UUID FEES_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private SystemAccounts() {
    }
}
