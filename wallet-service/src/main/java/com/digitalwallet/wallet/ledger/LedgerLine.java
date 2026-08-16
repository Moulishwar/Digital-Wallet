package com.digitalwallet.wallet.ledger;

import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One side of a journal entry: a signed amount applied to one account.
 *
 * <p>Deliberately has no setters, and is marked {@link Immutable} so Hibernate will not generate
 * an UPDATE for it even if something in the persistence context is changed by accident. The
 * database enforces the same rule with a trigger. A wrong posting is corrected by writing a
 * {@link JournalEntryType#REVERSAL}, never by changing what is already recorded.
 */
@Entity
@Table(name = "ledger_line")
@Immutable
public class LedgerLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
    private JournalEntry journalEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    /** Signed: negative debits the account, positive credits it. Never zero. */
    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "balance_after_minor", nullable = false, updatable = false)
    private long balanceAfterMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerLine() {
        // for JPA
    }

    LedgerLine(JournalEntry journalEntry, Account account, Money amount, Money balanceAfter) {
        if (amount.isZero()) {
            throw new IllegalArgumentException("A ledger line must move a non-zero amount");
        }
        this.id = UUID.randomUUID();
        this.journalEntry = journalEntry;
        this.account = account;
        this.amountMinor = amount.minor();
        this.balanceAfterMinor = balanceAfter.minor();
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public JournalEntry getJournalEntry() {
        return journalEntry;
    }

    public Account getAccount() {
        return account;
    }

    public Money getAmount() {
        return Money.ofMinor(amountMinor);
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getBalanceAfterMinor() {
        return balanceAfterMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isDebit() {
        return amountMinor < 0;
    }
}
