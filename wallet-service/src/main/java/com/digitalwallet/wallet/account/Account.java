package com.digitalwallet.wallet.account;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * An account in the ledger — either a user's wallet or one of the internal system accounts.
 *
 * <p>{@code balanceMinor} is a cache of {@code SUM(ledger_line.amount_minor)} for this account,
 * maintained in the same transaction as the posting that changes it. The ledger stays the source
 * of truth; a reconciliation check proves the two never diverge.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", updatable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private AccountType type;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    private Account(UUID id, UUID ownerUserId, AccountType type, String currency) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.currency = currency;
        this.balanceMinor = 0L;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public static Account openUserWallet(UUID ownerUserId, String currency) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("A user wallet must have an owner");
        }
        return new Account(UUID.randomUUID(), ownerUserId, AccountType.USER_WALLET, currency);
    }

    /**
     * Applies a signed change to the cached balance, enforcing the rules that must hold whichever
     * code path is posting.
     *
     * <p>The overdraft check lives here, on the entity, so it cannot be bypassed by a caller that
     * forgets it. It runs while the row is locked, in the same transaction as the ledger write —
     * checking a balance in one transaction and debiting in another is the classic
     * time-of-check-to-time-of-use bug that lets a wallet go negative under concurrency.
     *
     * @return the new balance, which is written to the ledger line as {@code balance_after_minor}
     */
    public Money applyDelta(Money delta) {
        if (!status.canPost()) {
            throw new ApiException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "Account %s is %s and cannot be posted to".formatted(id, status));
        }

        Money updated = balance().plus(delta);

        // SYSTEM_FUNDING is intentionally exempt: it is the source of value entering the platform
        // and is expected to be negative. Only user wallets are held to a floor of zero.
        if (type == AccountType.USER_WALLET && updated.isNegative()) {
            throw ApiException.insufficientFunds(balanceMinor, -delta.minor());
        }

        this.balanceMinor = updated.minor();
        return updated;
    }

    public Money balance() {
        return Money.ofMinor(balanceMinor);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
