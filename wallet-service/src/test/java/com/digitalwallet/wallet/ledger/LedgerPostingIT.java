package com.digitalwallet.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.AbstractPostgresIntegrationTest;
import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.account.AccountRepository;
import com.digitalwallet.wallet.account.SystemAccounts;
import com.digitalwallet.wallet.account.TopUpOutcome;
import com.digitalwallet.wallet.account.WalletService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ledger's guarantees, checked against a real database.
 */
class LedgerPostingIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private LedgerAuditService auditService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    private Account newFundedWallet(long amountMinor) {
        UUID userId = UUID.randomUUID();
        walletService.provisionWallet(userId);
        if (amountMinor > 0) {
            walletService.topUp(userId, Money.positive(amountMinor), UUID.randomUUID().toString());
        }
        return walletService.requireWalletFor(userId);
    }

    @Test
    @DisplayName("a top-up credits the wallet and debits the funding account by the same amount")
    void topUpWritesBothSides() {
        UUID userId = UUID.randomUUID();
        walletService.provisionWallet(userId);

        TopUpOutcome outcome = walletService.topUp(userId, Money.positive(100_000), "key-1");

        assertThat(outcome.balanceMinor()).isEqualTo(100_000);
        assertThat(outcome.replayed()).isFalse();

        Account wallet = walletService.requireWalletFor(userId);
        Account funding = accountRepository.findById(SystemAccounts.FUNDING_ACCOUNT_ID).orElseThrow();

        assertThat(wallet.getBalanceMinor()).isEqualTo(100_000);
        assertThat(funding.getBalanceMinor())
                .as("funding runs negative — that is how much value has entered the platform")
                .isEqualTo(-100_000);
    }

    @Test
    @DisplayName("replaying an idempotency key returns the original result without moving money again")
    void topUpIsIdempotent() {
        UUID userId = UUID.randomUUID();
        walletService.provisionWallet(userId);

        TopUpOutcome first = walletService.topUp(userId, Money.positive(50_000), "same-key");
        TopUpOutcome second = walletService.topUp(userId, Money.positive(50_000), "same-key");

        assertThat(second.journalEntryId()).isEqualTo(first.journalEntryId());
        assertThat(second.replayed()).isTrue();
        assertThat(walletService.requireWalletFor(userId).getBalanceMinor())
                .as("the second call must not deposit a second time")
                .isEqualTo(50_000);
    }

    @Test
    @DisplayName("concurrent requests with one idempotency key produce exactly one posting")
    void concurrentReplaysCollapseToOnePosting() throws Exception {
        UUID userId = UUID.randomUUID();
        walletService.provisionWallet(userId);

        int attempts = 12;
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<TopUpOutcome>> calls = IntStream.range(0, attempts)
                    .<Callable<TopUpOutcome>>mapToObj(i ->
                            () -> walletService.topUp(userId, Money.positive(10_000), "racing-key"))
                    .toList();

            List<Future<TopUpOutcome>> results = pool.invokeAll(calls);
            for (Future<TopUpOutcome> result : results) {
                assertThat(result.get().balanceMinor()).isEqualTo(10_000);
            }
        }

        assertThat(walletService.requireWalletFor(userId).getBalanceMinor())
                .as("twelve concurrent attempts, one deposit")
                .isEqualTo(10_000);
        assertThat(journalEntryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transfer moves the exact amount from one wallet to the other")
    void transferMovesMoney() {
        Account sender = newFundedWallet(80_000);
        Account recipient = newFundedWallet(0);

        postingService.post(PostingCommand.transfer(
                "transfer-1", sender.getId(), recipient.getId(), Money.positive(30_000), "test transfer"));

        assertThat(accountRepository.findById(sender.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(50_000);
        assertThat(accountRepository.findById(recipient.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(30_000);
    }

    @Test
    @DisplayName("a transfer larger than the balance is refused and changes nothing")
    void overdraftIsRefused() {
        Account sender = newFundedWallet(20_000);
        Account recipient = newFundedWallet(0);

        assertThatThrownBy(() -> postingService.post(PostingCommand.transfer(
                "transfer-2", sender.getId(), recipient.getId(), Money.positive(20_001), "too much")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        assertThat(accountRepository.findById(sender.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(20_000);
        assertThat(accountRepository.findById(recipient.getId()).orElseThrow().getBalanceMinor())
                .isZero();
        assertThat(journalEntryRepository.findByExternalRef("transfer-2"))
                .as("a refused posting must leave no journal entry behind")
                .isEmpty();
    }

    @Test
    @DisplayName("the whole ledger always sums to zero — money is moved, never created")
    void ledgerSumsToZero() {
        Account a = newFundedWallet(100_000);
        Account b = newFundedWallet(25_000);

        postingService.post(PostingCommand.transfer("t-a", a.getId(), b.getId(), Money.positive(40_000), null));
        postingService.post(PostingCommand.transfer("t-b", b.getId(), a.getId(), Money.positive(15_000), null));

        assertThat(auditService.totalOfAllLines()).isZero();
    }

    @Test
    @DisplayName("every cached balance still equals the sum of its own ledger lines")
    void cachedBalancesMatchTheLedger() {
        Account a = newFundedWallet(70_000);
        Account b = newFundedWallet(30_000);
        postingService.post(PostingCommand.transfer("t-c", a.getId(), b.getId(), Money.positive(12_345), null));

        assertThat(auditService.accountsWithDriftedBalance()).isEmpty();
    }

    @Test
    @DisplayName("the database itself refuses to update or delete a ledger line")
    void ledgerLinesAreAppendOnlyInTheDatabase() {
        newFundedWallet(10_000);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ledger_line SET amount_minor = 999"))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_line"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("posting to an account that does not exist fails rather than half-writing")
    void unknownAccountIsRejected() {
        Account sender = newFundedWallet(10_000);

        assertThatThrownBy(() -> postingService.post(PostingCommand.transfer(
                "t-missing", sender.getId(), UUID.randomUUID(), Money.positive(1_000), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        assertThat(auditService.totalOfAllLines()).isZero();
    }
}
