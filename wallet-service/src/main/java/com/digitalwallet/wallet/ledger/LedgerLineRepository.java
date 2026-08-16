package com.digitalwallet.wallet.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerLineRepository extends JpaRepository<LedgerLine, UUID> {

    /**
     * First page of a statement, newest first.
     *
     * <p>Paging is keyset-based rather than offset-based. With {@code OFFSET}, a row inserted
     * while the user is paging shifts everything down, so an entry can be shown twice or skipped
     * entirely — on a bank statement that reads as a duplicated or vanished transaction. Ordering
     * by {@code (created_at, id)} and asking for "everything strictly after this row" is stable
     * regardless of concurrent inserts, and it uses the index instead of counting past rows.
     */
    @Query("""
            select l from LedgerLine l
            join fetch l.journalEntry
            where l.account.id = :accountId
            order by l.createdAt desc, l.id desc
            """)
    List<LedgerLine> findFirstPage(@Param("accountId") UUID accountId, Pageable pageable);

    /**
     * The page following the row identified by the cursor.
     *
     * <p>{@code createdAt} alone is not unique — two lines of the same entry are written in the
     * same instant — so the id is the tiebreaker that makes the ordering total.
     */
    @Query("""
            select l from LedgerLine l
            join fetch l.journalEntry
            where l.account.id = :accountId
              and (l.createdAt < :cursorCreatedAt
                   or (l.createdAt = :cursorCreatedAt and l.id < :cursorId))
            order by l.createdAt desc, l.id desc
            """)
    List<LedgerLine> findNextPage(@Param("accountId") UUID accountId,
                                  @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                  @Param("cursorId") UUID cursorId,
                                  Pageable pageable);

    /** The balance implied by the ledger itself, ignoring the cached column. */
    @Query("select coalesce(sum(l.amountMinor), 0L) from LedgerLine l where l.account.id = :accountId")
    long sumAmountsForAccount(@Param("accountId") UUID accountId);

    /**
     * The signed total of every line in the ledger.
     *
     * <p>Must be zero. Money is only ever moved between accounts, never created or destroyed, so
     * every debit in the system has a matching credit somewhere.
     */
    @Query("select coalesce(sum(l.amountMinor), 0L) from LedgerLine l")
    long sumAllAmounts();
}
