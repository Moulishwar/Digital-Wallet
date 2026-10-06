package com.digitalwallet.wallet.ledger;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.account.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A single movement of value, made up of the lines that record both sides of it.
 *
 * <p>The invariant this class exists to protect: <strong>the signed amounts of an entry's lines
 * must sum to exactly zero</strong>. If they do not, money has been created or destroyed. That is
 * checked in {@link #requireBalanced()} before anything is persisted, so an unbalanced entry
 * cannot reach the database.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private JournalEntryType type;

    /**
     * The caller's identifier for this posting. Unique in the database, which is what makes a
     * retried posting safe: the second attempt fails the constraint instead of moving money twice.
     */
    @Column(name = "external_ref", nullable = false, length = 128, updatable = false, unique = true)
    private String externalRef;

    @Column(name = "description", length = 255, updatable = false)
    private String description;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    /** The sender's note, shown to both parties. Null when none was given. */
    @Column(name = "memo", length = 140, updatable = false)
    private String memo;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL)
    private List<LedgerLine> lines = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    private JournalEntry(JournalEntryType type, String externalRef, String description, String memo) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.externalRef = externalRef;
        this.description = description;
        this.memo = memo;
        this.postedAt = Instant.now();
    }

    static JournalEntry create(JournalEntryType type, String externalRef, String description, String memo) {
        return new JournalEntry(type, externalRef, description, memo);
    }

    /**
     * Records one side of the movement and applies it to the account's cached balance.
     *
     * <p>The account must already be locked by the caller — see
     * {@code AccountRepository.lockAllByIdInOrder}.
     *
     * @param counterparty who this line's statement row should name as the other party; may be null
     */
    LedgerLine addLine(Account account, Money amount, Counterparty counterparty) {
        Money balanceAfter = account.applyDelta(amount);
        LedgerLine line = new LedgerLine(this, account, amount, balanceAfter, counterparty);
        lines.add(line);
        return line;
    }

    /** The signed total of every line. Must be zero for a valid entry. */
    Money sumOfLines() {
        Money total = Money.ZERO;
        for (LedgerLine line : lines) {
            total = total.plus(line.getAmount());
        }
        return total;
    }

    /**
     * @throws ApiException if the lines do not sum to zero, or if there are fewer than two of them
     */
    void requireBalanced() {
        if (lines.size() < 2) {
            throw new ApiException(ErrorCode.UNBALANCED_ENTRY,
                    "A journal entry needs at least two lines, got " + lines.size());
        }
        Money sum = sumOfLines();
        if (!sum.isZero()) {
            // A 500, not a 4xx — reaching here means a bug in the posting code, not bad input.
            throw new ApiException(ErrorCode.UNBALANCED_ENTRY,
                    "Journal entry lines sum to %s, expected 0".formatted(sum.minor()));
        }
    }

    public UUID getId() {
        return id;
    }

    public JournalEntryType getType() {
        return type;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public String getDescription() {
        return description;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public String getMemo() {
        return memo;
    }

    public List<LedgerLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
