package com.recon.core.model;

import java.math.BigDecimal;

/** Asset classes differ in how price converts to cash, their settlement cycle and their tolerances. */
public enum AssetClass {
    /** Priced per share. US equities settle T+1 (since May 2024). */
    EQUITY(BigDecimal.ONE, 1),
    /** Priced as a percentage of face value; quantity is the face amount. Modelled as T+2. */
    FIXED_INCOME(new BigDecimal("0.01"), 2);

    private final BigDecimal priceMultiplier;
    private final int standardSettlementDays;

    AssetClass(BigDecimal priceMultiplier, int standardSettlementDays) {
        this.priceMultiplier = priceMultiplier;
        this.standardSettlementDays = standardSettlementDays;
    }

    /** Cash value of quantity at price, before commission. Exact (unrounded). */
    public BigDecimal principal(BigDecimal quantity, BigDecimal price) {
        return quantity.multiply(price).multiply(priceMultiplier);
    }

    public int standardSettlementDays() {
        return standardSettlementDays;
    }
}
