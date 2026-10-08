package com.digitalwallet.wallet.ledger;

import com.digitalwallet.wallet.account.AccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks the ledger's own invariants on demand.
 *
 * <p>Both checks below should be boring; they exist so that "the balances are correct" is
 * something that can be demonstrated rather than asserted. The same queries back the integration
 * tests, so a regression in the posting path fails the build as well as this endpoint.
 */
@Service
public class LedgerAuditService {

    private final AccountRepository accountRepository;
    private final LedgerLineRepository ledgerLineRepository;

    public LedgerAuditService(AccountRepository accountRepository,
                              LedgerLineRepository ledgerLineRepository) {
        this.accountRepository = accountRepository;
        this.ledgerLineRepository = ledgerLineRepository;
    }

    /**
     * The signed total of every ledger line in the system.
     *
     * <p>Must be zero. Each entry is internally balanced, so the sum of all of them is too, since
     * value only ever moves between accounts.
     */
    @Transactional(readOnly = true)
    public long totalOfAllLines() {
        return ledgerLineRepository.sumAllAmounts();
    }

    /**
     * Accounts whose cached {@code balance_minor} no longer equals the sum of their ledger lines.
     *
     * <p>Must be empty. Anything here means the cache and the truth have diverged, which is a bug
     * in the posting path rather than a data issue to patch up.
     */
    @Transactional(readOnly = true)
    public List<UUID> accountsWithDriftedBalance() {
        return accountRepository.findAccountsWhereCachedBalanceDisagreesWithLedger();
    }
}
