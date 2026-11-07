package com.productms.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * Money and quantity helpers. All money is BigDecimal with 2 decimals,
 * quantities with up to 3 decimals.
 */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    public static BigDecimal r2(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal r3(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(3) : v.setScale(3, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** "1,250.00" */
    public static String fmt(BigDecimal v) {
        return new DecimalFormat("#,##0.00").format(nz(v));
    }

    /** "Tk 1,250.00" */
    public static String cur(BigDecimal v) {
        return Settings.currency() + " " + fmt(v);
    }

    /** Quantity without useless zeros: 3, 2.5, 0.125 */
    public static String qty(BigDecimal v) {
        BigDecimal x = nz(v).stripTrailingZeros();
        if (x.scale() < 0) {
            x = x.setScale(0);
        }
        return x.toPlainString();
    }

    /** Parses user input like "1,250.50" or "" (returns null when blank). */
    public static BigDecimal parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replace(",", "").replace(Settings.currency(), "").trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text.trim() + "\" is not a valid number.");
        }
    }

    public static BigDecimal parseOr(String text, BigDecimal def) {
        BigDecimal v = parse(text);
        return v == null ? def : v;
    }

    public static boolean isWhole(BigDecimal v) {
        return nz(v).stripTrailingZeros().scale() <= 0;
    }

    public static boolean pos(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static BigDecimal pct(BigDecimal base, BigDecimal percent) {
        return nz(base).multiply(nz(percent)).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    }
}
