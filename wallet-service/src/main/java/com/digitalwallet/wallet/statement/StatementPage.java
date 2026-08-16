package com.digitalwallet.wallet.statement;

import java.util.List;

/**
 * A page of statement rows plus the cursor for the next one.
 *
 * @param nextCursor null when this is the last page
 */
public record StatementPage(List<StatementLine> lines, String nextCursor) {

    public boolean hasMore() {
        return nextCursor != null;
    }
}
