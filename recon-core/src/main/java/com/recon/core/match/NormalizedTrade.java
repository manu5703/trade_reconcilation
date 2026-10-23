package com.recon.core.match;

import com.recon.core.model.AssetClass;
import com.recon.core.model.Side;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An internal trade in canonical form. {@code ref} is the original tradeRef, so results can be
 * joined back to the source record.
 *
 * @param expectedCommission what the counterparty's schedule says this booking should be charged
 */
public record NormalizedTrade(
        String ref,
        String blockRef,
        String isin,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        LocalDate tradeDate,
        LocalDate settleDate,
        String counterparty,
        BigDecimal netAmount,
        BigDecimal expectedCommission,
        AssetClass assetClass) {
}
