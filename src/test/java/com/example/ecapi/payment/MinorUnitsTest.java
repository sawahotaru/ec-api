package com.example.ecapi.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MinorUnitsTest {

    @Test
    void zeroDecimalCurrencyRoundsHalfUpToWholeUnits() {
        assertThat(MinorUnits.of(new BigDecimal("1000.50"), "jpy")).isEqualTo(1001L);
        assertThat(MinorUnits.of(new BigDecimal("1000.49"), "jpy")).isEqualTo(1000L);
    }

    @Test
    void currencyWithMinorUnitIsScaledByHundred() {
        assertThat(MinorUnits.of(new BigDecimal("10.005"), "usd")).isEqualTo(1001L);
    }

    @Test
    void currencyCodeIsCaseInsensitive() {
        assertThat(MinorUnits.of(new BigDecimal("5990"), "JPY")).isEqualTo(5990L);
    }
}
