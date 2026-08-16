package com.digitalwallet.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.AbstractPostgresIntegrationTest;
import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.account.AccountRepository;
import com.digitalwallet.wallet.account.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The test that proves the locking strategy, and fails loudly if anyone ever "optimises away" the
 * row lock in {@link LedgerPostingService}.
 *
 * <p>The bug it guards against is the classic one. Check the balance, then debit, in two separate
 * steps: two concurrent withdrawals both read the same pre-debit balance, both decide there is
 * enough, and both proceed — leaving the wallet negative and money created out of nothing. It is
 * invisible in single-threaded testing and in casual manual use, and it is exactly the sort of thing
 * that only shows up under real load.
 *
 * <p>This lives in wallet-service rather than transfer-service on purpose. The guarantee under test
 * belongs to the posting path — a real database, real row locks, real transactions. Run against a
 * stubbed wallet-service it would prove nothing at all.
 */
class OverdraftConcurrencyIT extends AbstractPostgresIntegrationTest {

    private static final long STARTING_BALANCE = 10_000L;   // 100.00
    private static final long TRANSFER_AMOUNT = 1_000L;     // 10.00
    private static final int ATTEMPTS = 50;
    private static final int EXPECTED_SUCCESSES = (int) (STARTING_BALANCE / TRANSFER_AMOUNT);

    @Autowired
    private WalletService walletService;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private LedgerAuditService auditService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerLineRepository ledgerLineRepository;

    @Test
    @DisplayName("fifty concurrent transfers against a balance that covers ten: exactly ten succeed "
            + "and the wallet lands on zero, never below")
    void concurrentDebitsCannotOverdrawTheWallet() throws Exception {
        UUID senderUserId = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();
        walletService.provisionWallet(senderUserId);
        walletService.provisionWallet(recipientUserId);
        walletService.topUp(senderUserId, Money.positive(STARTING_BALANCE), UUID.randomUUID().toString());

        Account sender = walletService.requireWalletFor(senderUserId);
        Account recipient = walletService.requireWalletFor(recipientUserId);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();

        // Every thread waits on the same latch, so the attempts genuinely overlap instead of
        // trickling through one at a time — which would pass even with the lock removed.
        CountDownLatch startLine = new CountDownLatch(1);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            String externalRef = "concurrent-transfer-" + i;
            attempts.add(() -> {
                startLine.await();
                try {
                    postingService.post(PostingCommand.transfer(
                            externalRef, sender.getId(), recipient.getId(),
                            Money.positive(TRANSFER_AMOUNT), "Concurrent transfer"));
                    succeeded.incrementAndGet();
                } catch (ApiException refused) {
                    if (refused.code() == ErrorCode.INSUFFICIENT_FUNDS) {
                        insufficientFunds.incrementAndGet();
                    } else {
                        otherFailures.incrementAndGet();
                    }
                } catch (RuntimeException unexpected) {
                    otherFailures.incrementAndGet();
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get())
                .as("exactly the number of transfers the balance covers")
                .isEqualTo(EXPECTED_SUCCESSES);
        assertThat(insufficientFunds.get())
                .as("every other attempt refused for the right reason")
                .isEqualTo(ATTEMPTS - EXPECTED_SUCCESSES);
        assertThat(otherFailures.get())
                .as("no deadlocks and no unexpected errors — the lock ordering holds")
                .isZero();

        Account senderAfter = accountRepository.findById(sender.getId()).orElseThrow();
        Account recipientAfter = accountRepository.findById(recipient.getId()).orElseThrow();

        assertThat(senderAfter.getBalanceMinor())
                .as("drained to exactly zero, never negative")
                .isZero();
        assertThat(recipientAfter.getBalanceMinor()).isEqualTo(STARTING_BALANCE);

        // The cache is not merely plausible, it matches the ledger it summarises.
        assertThat(ledgerLineRepository.sumAmountsForAccount(sender.getId())).isZero();
        assertThat(auditService.accountsWithDriftedBalance()).isEmpty();
        assertThat(auditService.totalOfAllLines())
                .as("money was moved, never created or destroyed")
                .isZero();
    }

    @Test
    @DisplayName("two wallets paying each other at the same instant do not deadlock")
    void oppositeTransfersDoNotDeadlock() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        walletService.provisionWallet(aliceId);
        walletService.provisionWallet(bobId);
        walletService.topUp(aliceId, Money.positive(50_000L), UUID.randomUUID().toString());
        walletService.topUp(bobId, Money.positive(50_000L), UUID.randomUUID().toString());

        Account alice = walletService.requireWalletFor(aliceId);
        Account bob = walletService.requireWalletFor(bobId);

        // Alice→Bob and Bob→Alice, fired together. If each transaction locked its own sender first,
        // they would wait on each other forever. Locking in a fixed id order means one simply waits.
        int rounds = 20;
        CountDownLatch startLine = new CountDownLatch(1);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            int round = i;
            attempts.add(() -> {
                startLine.await();
                postingService.post(PostingCommand.transfer(
                        "a-to-b-" + round, alice.getId(), bob.getId(),
                        Money.positive(100L), "Alice pays Bob"));
                return null;
            });
            attempts.add(() -> {
                startLine.await();
                postingService.post(PostingCommand.transfer(
                        "b-to-a-" + round, bob.getId(), alice.getId(),
                        Money.positive(100L), "Bob pays Alice"));
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(attempts.size());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            startLine.countDown();
            for (Future<Void> future : futures) {
                // A deadlock would show up here as a timeout rather than as a hung suite.
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Equal traffic both ways nets to nothing.
        assertThat(accountRepository.findById(alice.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(50_000L);
        assertThat(accountRepository.findById(bob.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(50_000L);
        assertThat(auditService.accountsWithDriftedBalance()).isEmpty();
    }
}
