package com.recon.core.model;

import static com.recon.core.Checks.positive;
import static com.recon.core.Checks.required;
import static com.recon.core.Checks.requiredText;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A trade as booked in the firm's own system.
 *
 * <p>Values are kept as received. Canonical forms (trimmed upper-case refs, counterparty IDs,
 * one decimal scale) are the normalizer's job, so a raw record can always be traced back.
 *
 * @param blockRef set when this trade is one allocation of a block order, otherwise null
 */
public record InternalTrade(
        String tradeRef,
        String blockRef,
        String account,
        String isin,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        LocalDate tradeDate,
        LocalDate settleDate,
        String counterparty,
        BigDecimal netAmount) {

    public InternalTrade {
        requiredText(tradeRef, "tradeRef");
        requiredText(account, "account");
        requiredText(isin, "isin");
        required(side, "side");
        positive(quantity, "quantity");
        positive(price, "price");
        required(tradeDate, "tradeDate");
        required(settleDate, "settleDate");
        requiredText(counterparty, "counterparty");
        required(netAmount, "netAmount");
    }

    public boolean isBlockAllocation() {
        return blockRef != null && !blockRef.isBlank();
    }
}
