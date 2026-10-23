package com.recon.core.match;

import com.recon.core.model.Side;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Hash-join key for the exact and block passes. {@code quantity} is a BigDecimal, whose
 * equals/hashCode include the scale; that's safe because the normalizer gives every quantity
 * scale 6.
 */
record ExactKey(String isin, Side side, LocalDate tradeDate, String counterparty, BigDecimal quantity) {

    static ExactKey of(NormalizedConfirm c) {
        return new ExactKey(c.isin(), c.side(), c.tradeDate(), c.counterparty(), c.quantity());
    }
}
