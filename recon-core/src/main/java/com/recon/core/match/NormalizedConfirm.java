package com.recon.core.match;

import com.recon.core.model.Side;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An external confirm in canonical form. {@code ref} is the original brokerRef. */
public record NormalizedConfirm(
        String ref,
        String isin,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal commission,
        LocalDate tradeDate,
        LocalDate settleDate,
        String counterparty,
        BigDecimal netAmount) {
}
