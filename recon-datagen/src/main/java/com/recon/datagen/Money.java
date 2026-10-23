package com.recon.datagen;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Money {

    static final BigDecimal CENT = new BigDecimal("0.01");
    /** Prices and averages are carried to 6 decimals, matching NUMERIC(20,6). */
    static final int PRICE_SCALE = 6;

    private Money() {
    }

    /** Cash amounts are booked in cents with banker's rounding, the usual default for ledgers. */
    static BigDecimal toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }
}
