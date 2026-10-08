package com.digitalwallet.wallet.account;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByOwnerUserId(UUID ownerUserId);

    boolean existsByOwnerUserId(UUID ownerUserId);

    /**
     * Loads accounts for update, taking row locks in a deterministic order.
     *
     * <p>The {@code order by a.id} is the entire point of this method, not a tidy-up. Two
     * simultaneous transfers in opposite directions (Alice to Bob, and Bob to Alice) would each
     * lock their own sender first, then block forever waiting for the other's row. Sorting by a
     * stable key means every transaction requests locks in the same sequence, so one simply waits
     * for the other instead of deadlocking.
     *
     * <p>{@code PESSIMISTIC_WRITE} issues {@code SELECT ... FOR UPDATE}, which holds the rows for
     * the rest of the transaction. That is what lets the balance check and the debit happen as one
     * indivisible step.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdInOrder(@Param("ids") Collection<UUID> ids);

    /**
     * Accounts whose cached balance disagrees with the sum of their ledger lines.
     *
     * <p>Should always return empty. Anything else is a bug in the posting path, and it is worth
     * being able to prove that on demand rather than trusting it.
     */
    @Query("""
            select a.id from Account a
            where a.balanceMinor <> coalesce(
                (select sum(l.amountMinor) from LedgerLine l where l.account = a), 0L)
            """)
    List<UUID> findAccountsWhereCachedBalanceDisagreesWithLedger();
}
