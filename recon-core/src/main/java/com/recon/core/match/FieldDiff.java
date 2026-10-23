package com.recon.core.match;

import java.math.BigDecimal;

/**
 * One field on which a confirm disagrees with what we expected. Values are kept raw and only
 * turned into display strings when a break is created: the fuzzy pass computes diffs for many
 * candidate pairs that it then discards.
 */
public record FieldDiff(Field field, Object expected, Object actual) {

    String expectedText() {
        return display(expected);
    }

    String actualText() {
        return display(actual);
    }

    /** Decimals without trailing zeros or exponent: 4900.000000 shows as 4900. */
    static String display(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
