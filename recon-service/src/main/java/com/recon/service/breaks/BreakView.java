package com.recon.service.breaks;

import com.recon.core.model.BreakType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * API representation of a break, with the trade and confirm it concerns so a reviewer can see
 * expected against actual without another call.
 *
 * @param ageHours whole hours since the break first opened (survives re-runs)
 * @param version  send back as {@code expectedVersion} on PATCH to detect someone else's edit
 */
public record BreakView(
        long id,
        long runId,
        LocalDate businessDate,
        BreakType type,
        BreakStatus status,
        String field,
        String expected,
        String actual,
        String counterparty,
        Instant openedAt,
        long ageHours,
        Instant resolvedAt,
        String owner,
        String note,
        String resolutionReason,
        long version,
        Trade trade,
        Confirm confirm) {

    public record Trade(String tradeRef, String blockRef, String account, String isin, String side, BigDecimal quantity,
                        BigDecimal price, LocalDate tradeDate, LocalDate settleDate, String counterparty, BigDecimal netAmount) {
    }

    public record Confirm(String brokerRef, String source, String isin, String side, BigDecimal quantity, BigDecimal price,
                          BigDecimal commission, LocalDate tradeDate, LocalDate settleDate, String counterparty, BigDecimal netAmount) {
    }

    static BreakView of(ReconBreak b, Instant now) {
        TradeRecord t = b.getTrade();
        ConfirmRecord c = b.getConfirm();
        Instant ageEnd = b.getResolvedAt() != null ? b.getResolvedAt() : now;
        return new BreakView(b.getId(), b.getRunId(), b.getBusinessDate(), b.getType(), b.getStatus(), b.getField(),
                b.getExpected(), b.getActual(), b.getCounterparty(), b.getOpenedAt(),
                Duration.between(b.getOpenedAt(), ageEnd).toHours(), b.getResolvedAt(), b.getOwner(), b.getNote(),
                b.getResolutionReason(), b.getVersion(),
                t == null ? null : new Trade(t.getTradeRef(), t.getBlockRef(), t.getAccount(), t.getIsin(), t.getSide(),
                        t.getQuantity(), t.getPrice(), t.getTradeDate(), t.getSettleDate(), t.getCounterparty(), t.getNetAmount()),
                c == null ? null : new Confirm(c.getBrokerRef(), c.getSource(), c.getIsin(), c.getSide(), c.getQuantity(),
                        c.getPrice(), c.getCommission(), c.getTradeDate(), c.getSettleDate(), c.getCounterparty(), c.getNetAmount()));
    }
}
