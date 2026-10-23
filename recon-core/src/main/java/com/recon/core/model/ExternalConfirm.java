package com.recon.core.model;

import static com.recon.core.Checks.nonNegative;
import static com.recon.core.Checks.positive;
import static com.recon.core.Checks.required;
import static com.recon.core.Checks.requiredText;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A trade confirmation received from a broker or clearing house. Values are kept as received;
 * see {@link InternalTrade} on normalization.
 */
public record ExternalConfirm(
        String brokerRef,
        Source source,
        String isin,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal commission,
        LocalDate tradeDate,
        LocalDate settleDate,
        String counterparty,
        BigDecimal netAmount) {

    public ExternalConfirm {
        requiredText(brokerRef, "brokerRef");
        required(source, "source");
        requiredText(isin, "isin");
        required(side, "side");
        positive(quantity, "quantity");
        positive(price, "price");
        nonNegative(commission, "commission");
        required(tradeDate, "tradeDate");
        required(settleDate, "settleDate");
        requiredText(counterparty, "counterparty");
        required(netAmount, "netAmount");
    }
}
