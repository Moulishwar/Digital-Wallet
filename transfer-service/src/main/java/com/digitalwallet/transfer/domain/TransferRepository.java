package com.digitalwallet.transfer.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    /**
     * First page of the caller's own history, newest first.
     *
     * <p>Keyset-paged for the same reason the wallet statement is: with {@code OFFSET}, a transfer
     * created while the user is paging shifts every later row down, so one can be shown twice or
     * skipped. Ordering by {@code (created_at, id)} and asking for everything strictly after a
     * given row is stable under concurrent inserts.
     */
    @Query("""
            select t from Transfer t
            where t.senderUserId = :userId
            order by t.createdAt desc, t.id desc
            """)
    List<Transfer> findFirstPageForSender(@Param("userId") UUID userId, Pageable pageable);

    /**
     * The page following the row identified by the cursor.
     *
     * <p>{@code createdAt} alone is not unique (two transfers can be opened in the same instant),
     * so the id is the tiebreaker that makes the ordering total.
     */
    @Query("""
            select t from Transfer t
            where t.senderUserId = :userId
              and (t.createdAt < :cursorCreatedAt
                   or (t.createdAt = :cursorCreatedAt and t.id < :cursorId))
            order by t.createdAt desc, t.id desc
            """)
    List<Transfer> findNextPageForSender(@Param("userId") UUID userId,
                                         @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                         @Param("cursorId") UUID cursorId,
                                         Pageable pageable);

    /**
     * Transfers whose outcome is still unknown, oldest first: the reconciliation sweep's input.
     *
     * <p>The age floor matters. A transfer marked unresolved a moment ago may still have its
     * original request in flight, and sweeping it immediately would race that request rather than
     * resolve it. Waiting lets the in-flight attempt finish and settle the row on its own.
     */
    @Query("""
            select t from Transfer t
            where t.status = com.digitalwallet.transfer.domain.TransferStatus.NEEDS_RECONCILIATION
              and t.createdAt < :olderThan
            order by t.createdAt asc
            """)
    List<Transfer> findUnresolved(@Param("olderThan") Instant olderThan, Pageable pageable);
}
