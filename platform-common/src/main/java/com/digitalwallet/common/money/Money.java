package com.digitalwallet.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of money held as a signed count of <em>minor units</em> (paise).
 *
 * <p>There is no {@code double} or {@code float} anywhere in this type, and none should appear
 * anywhere in the codebase. Binary floating point cannot represent 0.10 exactly, so accumulating
 * such values silently loses money. {@link BigDecimal} is used only at the API boundary, to parse
 * and format.
 *
 * <p>Amounts are <strong>signed</strong>: a ledger line carries a negative amount for a debit and
 * a positive amount for a credit, and the lines of a journal entry must sum to zero. Where a value
 * must not be negative (a top-up, a transfer amount), construct it with {@link #positive(long)},
 * which rejects zero and negatives at the boundary rather than deep in the domain.
 *
 * <p>Arithmetic uses {@link Math#addExact} and friends, so an overflow throws rather than silently
 * wrapping a balance around to a negative number.
 */
public record Money(long minor) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    /** Any amount, including zero and negatives: the general case for a ledger line. */
    public static Money ofMinor(long minor) {
        return new Money(minor);
    }

    /**
     * A strictly positive amount. Use this for anything a user supplies as "how much"; it turns
     * a nonsensical request into a clear failure at the edge of the system.
     *
     * @throws IllegalArgumentException if {@code minor} is zero or negative
     */
    public static Money positive(long minor) {
        if (minor <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + minor);
        }
        return new Money(minor);
    }

    /** Parses a major-unit amount such as {@code 123.45}, rejecting sub-paise precision. */
    public static Money ofMajor(BigDecimal major) {
        if (major.scale() > 2) {
            throw new IllegalArgumentException("Amount has more precision than one paise: " + major);
        }
        return new Money(major.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(this.minor, other.minor));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(this.minor, other.minor));
    }

    /** Flips the sign, turning a credit into the matching debit. */
    public Money negated() {
        return new Money(Math.negateExact(this.minor));
    }

    public Money abs() {
        return minor < 0 ? negated() : this;
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isLessThan(Money other) {
        return this.minor < other.minor;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return this.minor >= other.minor;
    }

    /** Major-unit view, for serialization and display only. Never use this for arithmetic. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, 2);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.minor, other.minor);
    }

    @Override
    public String toString() {
        return toMajor().toPlainString();
    }
}
