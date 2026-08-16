package com.digitalwallet.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    @DisplayName("adds and subtracts without losing precision")
    void arithmetic() {
        Money hundred = Money.ofMinor(10_000);
        Money fifty = Money.ofMinor(5_000);

        assertThat(hundred.plus(fifty).minor()).isEqualTo(15_000);
        assertThat(hundred.minus(fifty).minor()).isEqualTo(5_000);
        assertThat(hundred.minus(hundred)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a debit is exactly the negation of its credit, so the pair sums to zero")
    void negationIsExact() {
        Money credit = Money.ofMinor(123_456_789L);
        assertThat(credit.plus(credit.negated())).isEqualTo(Money.ZERO);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -10_000L})
    @DisplayName("positive() rejects amounts that are not strictly positive")
    void positiveRejectsNonPositive(long value) {
        assertThatThrownBy(() -> Money.positive(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("overflow throws instead of silently wrapping a balance negative")
    void overflowThrows() {
        Money nearMax = Money.ofMinor(Long.MAX_VALUE);
        assertThatThrownBy(() -> nearMax.plus(Money.ofMinor(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("converts to and from major units without rounding")
    void majorUnitConversion() {
        assertThat(Money.ofMinor(12_345).toMajor()).isEqualByComparingTo("123.45");
        assertThat(Money.ofMajor(new BigDecimal("123.45")).minor()).isEqualTo(12_345);
        assertThat(Money.ofMajor(new BigDecimal("0.01")).minor()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects amounts finer than one paise rather than rounding them away")
    void rejectsSubMinorPrecision() {
        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("1.005")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more precision");
    }

    @Test
    @DisplayName("0.1 + 0.2 is exactly 0.3 — the reason amounts are integers, not doubles")
    void noBinaryFloatingPointError() {
        Money tenPaise = Money.ofMajor(new BigDecimal("0.10"));
        Money twentyPaise = Money.ofMajor(new BigDecimal("0.20"));

        assertThat(tenPaise.plus(twentyPaise).toMajor()).isEqualByComparingTo("0.30");
        // For contrast: 0.1d + 0.2d == 0.30000000000000004
        assertThat(0.1d + 0.2d).isNotEqualTo(0.3d);
    }
}
