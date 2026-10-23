package com.recon.core.match;

import com.recon.core.model.AssetClass;
import com.recon.core.model.Side;
import com.recon.core.tolerance.Tolerance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a correct confirm should say, built from one booking, from all the allocations of a
 * block, or from an already-matched confirm (to spot duplicates). Every pass compares a confirm
 * against an Expectation, so the same tolerance rules apply everywhere.
 */
record Expectation(
        String isin,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        LocalDate tradeDate,
        LocalDate settleDate,
        String counterparty,
        BigDecimal netAmount,
        BigDecimal commission,
        AssetClass assetClass) {

    /** Extra precision for the weighted average, so the price check isn't distorted by rounding. */
    private static final int AVERAGE_PRICE_SCALE = 10;

    static Expectation of(NormalizedTrade t) {
        return new Expectation(t.isin(), t.side(), t.quantity(), t.price(), t.tradeDate(), t.settleDate(),
                t.counterparty(), t.netAmount(), t.expectedCommission(), t.assetClass());
    }

    static Expectation of(NormalizedConfirm c, AssetClass assetClass) {
        return new Expectation(c.isin(), c.side(), c.quantity(), c.price(), c.tradeDate(), c.settleDate(),
                c.counterparty(), c.netAmount(), c.commission(), assetClass);
    }

    /**
     * The confirm a broker should send for a whole block: total quantity, quantity-weighted
     * average price, summed net amount and summed scheduled commission. Empty if the allocations
     * disagree on anything that must be shared, because then they can't be one order.
     */
    static Optional<Expectation> ofBlock(List<NormalizedTrade> allocations) {
        NormalizedTrade first = allocations.getFirst();
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (NormalizedTrade t : allocations) {
            boolean sameOrder = t.isin().equals(first.isin()) && t.side() == first.side()
                    && t.tradeDate().equals(first.tradeDate()) && t.settleDate().equals(first.settleDate())
                    && t.counterparty().equals(first.counterparty());
            if (!sameOrder) {
                return Optional.empty();
            }
            quantity = quantity.add(t.quantity());
            notional = notional.add(t.quantity().multiply(t.price()));
            net = net.add(t.netAmount());
            commission = commission.add(t.expectedCommission());
        }
        BigDecimal averagePrice = notional.divide(quantity, AVERAGE_PRICE_SCALE, RoundingMode.HALF_EVEN);
        return Optional.of(new Expectation(first.isin(), first.side(), quantity, averagePrice, first.tradeDate(),
                first.settleDate(), first.counterparty(), net, commission, first.assetClass()));
    }

    /** Fields on which {@code confirm} differs beyond tolerance, in {@link Field} order. */
    List<FieldDiff> diff(NormalizedConfirm confirm, Tolerance tolerance) {
        List<FieldDiff> diffs = new ArrayList<>();
        if (side != confirm.side()) {
            diffs.add(new FieldDiff(Field.SIDE, side, confirm.side()));
        }
        if (quantity.compareTo(confirm.quantity()) != 0) {
            diffs.add(new FieldDiff(Field.QUANTITY, quantity, confirm.quantity()));
        }
        if (!tolerance.priceWithin(price, confirm.price())) {
            diffs.add(new FieldDiff(Field.PRICE, price, confirm.price()));
        }
        if (!counterparty.equals(confirm.counterparty())) {
            diffs.add(new FieldDiff(Field.COUNTERPARTY, counterparty, confirm.counterparty()));
        }
        if (!settleDate.equals(confirm.settleDate())) {
            diffs.add(new FieldDiff(Field.SETTLE_DATE, settleDate, confirm.settleDate()));
        }
        if (!tolerance.commissionWithin(commission, confirm.commission())) {
            diffs.add(new FieldDiff(Field.COMMISSION, commission, confirm.commission()));
        }
        if (!tolerance.netAmountWithin(netAmount, confirm.netAmount())) {
            diffs.add(new FieldDiff(Field.NET_AMOUNT, netAmount, confirm.netAmount()));
        }
        return diffs;
    }

    ExactKey exactKey() {
        return new ExactKey(isin, side, tradeDate, counterparty, quantity);
    }
}
