package cloud.gehan.finance.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    private Money() {
    }

    /** "12.34" or "-0.5" to cents. Null and blank stay null. */
    public static Long cents(String decimal) {
        if (decimal == null || decimal.isBlank()) {
            return null;
        }
        return new BigDecimal(decimal.trim().replace(",", "")).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long cents(double amount) {
        return BigDecimal.valueOf(amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
