package com.recon.core.model;

import java.math.BigDecimal;

public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    /**
     * Net cash amount of a trade: a buyer pays principal plus commission, a seller receives
     * principal minus commission. Unrounded; callers round to the currency's scale.
     */
    public BigDecimal netOf(BigDecimal principal, BigDecimal commission) {
        return this == BUY ? principal.add(commission) : principal.subtract(commission);
    }
}
