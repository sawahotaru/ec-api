package com.example.ecapi.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * 金額を通貨の補助単位（円なら円、ドルならセント）の整数へ直す。
 * 決済側へ請求する額と、通知で届いた額の照合の両方がこの 1 つを通る。
 */
public final class MinorUnits {

    /** 補助単位を持たない通貨（金額を100倍せずそのまま扱う）。 */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "jpy", "krw", "vnd", "clp", "bif", "djf", "gnf",
            "kmf", "mga", "pyg", "rwf", "ugx", "vuv", "xaf", "xof", "xpf");

    private MinorUnits() {
    }

    public static long of(BigDecimal amount, String currency) {
        if (ZERO_DECIMAL.contains(currency.toLowerCase(Locale.ROOT))) {
            return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
