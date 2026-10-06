package com.digitalwallet.wallet.ledger;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.account.AccountRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code in the system that writes to the ledger.
 *
 * <p>Everything that moves money funnels through {@link #post(PostingCommand)}, which does the
 * whole movement in one transaction: lock the accounts, check the rules, write both sides, update
 * the cached balances. There is no path that writes one side without the other.
 */
@Service
public class LedgerPostingService {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public LedgerPostingService(AccountRepository accountRepository,
                                JournalEntryRepository journalEntryRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    /**
     * Posts a balanced journal entry.
     *
     * <p>The ordering here is deliberate:
     * <ol>
     *   <li><b>Replay check.</b> If this {@code externalRef} was already posted, return it
     *       untouched. A caller retrying after a timeout gets the original outcome, not a second
     *       movement of money.</li>
     *   <li><b>Lock every account at once, in id order.</b> Deadlock avoidance — see
     *       {@link AccountRepository#lockAllByIdInOrder}.</li>
     *   <li><b>Apply the legs.</b> Each one runs the account's own rules, including the overdraft
     *       check, while the row is held.</li>
     *   <li><b>Require balance.</b> Nothing is written until the lines are proven to sum to
     *       zero.</li>
     * </ol>
     *
     * <p>The balance check and the write are inside the same locked transaction, so two concurrent
     * debits against one wallet cannot both read the pre-debit balance and both succeed.
     *
     * <p>If a concurrent caller commits the same {@code externalRef} first, the unique constraint
     * rejects this transaction. That surfaces as a {@code DataIntegrityViolationException} and is
     * resolved by the caller re-reading — see {@code WalletService.topUp}.
     */
    @Transactional
    public PostingResult post(PostingCommand command) {
        Optional<JournalEntry> alreadyPosted = journalEntryRepository.findByExternalRef(command.externalRef());
        if (alreadyPosted.isPresent()) {
            log.debug("Replaying posting for externalRef {}", command.externalRef());
            return PostingResult.of(alreadyPosted.get(), true);
        }

        Map<UUID, Account> accounts = lockAccounts(command.distinctAccountIds());

        JournalEntry entry = JournalEntry.create(
                command.type(), command.externalRef(), command.description(), command.memo());
        for (PostingCommand.PostingLeg leg : command.legs()) {
            entry.addLine(accounts.get(leg.accountId()), leg.amount(), leg.counterparty());
        }
        entry.requireBalanced();

        // Flush inside the transaction so a duplicate externalRef fails here, where it can be
        // told apart from an unrelated failure, rather than at an ambiguous commit boundary.
        journalEntryRepository.saveAndFlush(entry);

        log.info("Posted {} entry {} ref={} lines={}",
                entry.getType(), entry.getId(), entry.getExternalRef(), entry.getLines().size());
        return PostingResult.of(entry, false);
    }

    /** Used by callers resolving whether an earlier, uncertain attempt actually committed. */
    @Transactional(readOnly = true)
    public Optional<PostingResult> findByExternalRef(String externalRef) {
        return journalEntryRepository.findByExternalRef(externalRef)
                .map(entry -> PostingResult.of(entry, true));
    }

    private Map<UUID, Account> lockAccounts(List<UUID> accountIds) {
        List<Account> locked = accountRepository.lockAllByIdInOrder(accountIds);

        Map<UUID, Account> byId = new HashMap<>();
        for (Account account : locked) {
            byId.put(account.getId(), account);
        }
        for (UUID id : accountIds) {
            if (!byId.containsKey(id)) {
                throw ApiException.accountNotFound(id);
            }
        }
        return byId;
    }
}
