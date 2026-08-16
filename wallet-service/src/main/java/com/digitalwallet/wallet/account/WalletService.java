package com.digitalwallet.wallet.account;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.ledger.LedgerPostingService;
import com.digitalwallet.wallet.ledger.PostingCommand;
import com.digitalwallet.wallet.ledger.PostingResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallet-level operations: provisioning, balance, and top-up.
 *
 * <p>Note what is <em>not</em> here — no arithmetic on balances. Anything that changes an amount
 * goes through {@link LedgerPostingService} so the double-entry rule cannot be sidestepped.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    /** Single currency in v1. The column exists so adding more does not need a schema rewrite. */
    private static final String DEFAULT_CURRENCY = "INR";

    private final AccountRepository accountRepository;
    private final LedgerPostingService postingService;

    public WalletService(AccountRepository accountRepository, LedgerPostingService postingService) {
        this.accountRepository = accountRepository;
        this.postingService = postingService;
    }

    /**
     * Returns the user's wallet, creating it if this is the first time we have seen them.
     *
     * <p>Intentionally not annotated {@code @Transactional}. Each repository call runs in its own
     * transaction, which is what allows the race below to be caught and recovered from — a caught
     * exception inside an outer transaction would leave it marked rollback-only and unusable.
     *
     * <p>The race is real: two requests for a brand-new user can both find nothing and both try to
     * insert. The partial unique index on {@code owner_user_id} lets exactly one win, and the
     * loser resolves by re-reading. Provisioning therefore stays idempotent without a lock.
     */
    public Account provisionWallet(UUID ownerUserId) {
        return accountRepository.findByOwnerUserId(ownerUserId)
                .orElseGet(() -> createWallet(ownerUserId));
    }

    private Account createWallet(UUID ownerUserId) {
        try {
            Account created = accountRepository.save(Account.openUserWallet(ownerUserId, DEFAULT_CURRENCY));
            log.info("Provisioned wallet {} for user {}", created.getId(), ownerUserId);
            return created;
        } catch (DataIntegrityViolationException concurrentInsert) {
            log.debug("Concurrent wallet creation for user {}, re-reading", ownerUserId);
            return accountRepository.findByOwnerUserId(ownerUserId)
                    .orElseThrow(() -> concurrentInsert);
        }
    }

    @Transactional(readOnly = true)
    public boolean walletExistsFor(UUID ownerUserId) {
        return accountRepository.existsByOwnerUserId(ownerUserId);
    }

    @Transactional(readOnly = true)
    public Account requireWalletFor(UUID ownerUserId) {
        return accountRepository.findByOwnerUserId(ownerUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "No wallet exists for this user"));
    }

    /**
     * Credits the wallet from the system funding account — a simulated deposit.
     *
     * <p>Not {@code @Transactional}, for the same reason as {@link #provisionWallet}: the posting
     * runs in its own transaction, and if a concurrent request with the same idempotency key
     * commits first, this one fails the unique constraint and recovers by reading the committed
     * result. The caller gets the same answer either way, which is the whole point of an
     * idempotency key.
     */
    public TopUpOutcome topUp(UUID ownerUserId, Money amount, String idempotencyKey) {
        Account wallet = requireWalletFor(ownerUserId);
        String externalRef = topUpReference(ownerUserId, idempotencyKey);

        PostingCommand command = PostingCommand.topUp(
                externalRef, SystemAccounts.FUNDING_ACCOUNT_ID, wallet.getId(), amount, "Wallet top-up");

        PostingResult result;
        try {
            result = postingService.post(command);
        } catch (DataIntegrityViolationException raced) {
            result = postingService.findByExternalRef(externalRef).orElseThrow(() -> raced);
        }

        Long balanceAfter = result.balancesAfter().get(wallet.getId());
        if (balanceAfter == null) {
            // Would mean the entry exists but does not touch this wallet — only reachable if two
            // different users somehow produced the same external reference, which the namespacing
            // in topUpReference prevents.
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency key belongs to a posting that does not involve this wallet");
        }
        return new TopUpOutcome(result.journalEntryId(), balanceAfter, result.replayed());
    }

    /**
     * Namespaces the client's key by user, because {@code external_ref} is unique across the whole
     * ledger. Without the user id, two people independently choosing the key "1" would collide and
     * the second would silently receive the first person's top-up as a replay.
     */
    private String topUpReference(UUID ownerUserId, String idempotencyKey) {
        return "topup:" + ownerUserId + ":" + idempotencyKey;
    }
}
