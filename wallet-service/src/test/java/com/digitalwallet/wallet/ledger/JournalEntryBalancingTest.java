package com.digitalwallet.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.account.Account;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The balancing rule, tested without a database. These are the checks that stop money being
 * created or destroyed, so they should be provable in isolation.
 */
class JournalEntryBalancingTest {

    private static Account wallet() {
        return Account.openUserWallet(UUID.randomUUID(), "INR");
    }

    @Test
    @DisplayName("a matched debit and credit sums to zero and is accepted")
    void balancedEntryIsAccepted() {
        Account from = wallet();
        Account to = wallet();
        from.applyDelta(Money.ofMinor(10_000)); // fund it first

        JournalEntry entry = JournalEntry.create(JournalEntryType.TRANSFER, "ref-1", "test");
        entry.addLine(from, Money.ofMinor(-5_000));
        entry.addLine(to, Money.ofMinor(5_000));

        assertThat(entry.sumOfLines()).isEqualTo(Money.ZERO);
        entry.requireBalanced(); // does not throw
    }

    @Test
    @DisplayName("an entry whose lines do not cancel is rejected before it can be persisted")
    void unbalancedEntryIsRejected() {
        Account from = wallet();
        Account to = wallet();
        from.applyDelta(Money.ofMinor(10_000));

        JournalEntry entry = JournalEntry.create(JournalEntryType.TRANSFER, "ref-2", "test");
        entry.addLine(from, Money.ofMinor(-5_000));
        entry.addLine(to, Money.ofMinor(4_000)); // 1000 paise would vanish

        assertThatThrownBy(entry::requireBalanced)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.UNBALANCED_ENTRY));
    }

    @Test
    @DisplayName("a one-sided entry is rejected — there is no such thing as a lone debit")
    void singleLineEntryIsRejected() {
        Account account = wallet();
        account.applyDelta(Money.ofMinor(10_000));

        JournalEntry entry = JournalEntry.create(JournalEntryType.TRANSFER, "ref-3", "test");
        entry.addLine(account, Money.ofMinor(-5_000));

        assertThatThrownBy(entry::requireBalanced)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least two lines");
    }

    @Test
    @DisplayName("a user wallet refuses to go negative")
    void userWalletCannotOverdraw() {
        Account account = wallet();
        account.applyDelta(Money.ofMinor(1_000));

        assertThatThrownBy(() -> account.applyDelta(Money.ofMinor(-1_001)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        assertThat(account.getBalanceMinor())
                .as("a rejected debit must leave the balance untouched")
                .isEqualTo(1_000);
    }

    @Test
    @DisplayName("each line records the balance as it stood immediately after that line")
    void linesCarryRunningBalance() {
        Account account = wallet();

        JournalEntry first = JournalEntry.create(JournalEntryType.TOPUP, "ref-4", "top-up");
        LedgerLine line = first.addLine(account, Money.ofMinor(7_500));

        assertThat(line.getBalanceAfterMinor()).isEqualTo(7_500);
        assertThat(account.getBalanceMinor()).isEqualTo(7_500);
    }
}
