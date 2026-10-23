package com.recon.datagen;

import com.recon.core.model.AssetClass;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.Side;
import com.recon.core.model.Source;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Mutable working copy of a confirm. The generator builds the clean confirm, then applies one
 * break or one kind of noise by changing fields before calling {@link #build}.
 *
 * <p>The net amount is derived at build time from the (possibly altered) quantity, price, side
 * and commission. A real broker's confirm is internally consistent, so a quantity break also
 * moves the net amount, just as it would in production.
 */
final class ConfirmDraft {

    /** One allocation as booked internally: account, quantity, fill price and scheduled commission. */
    record Fill(String account, BigDecimal quantity, BigDecimal price, BigDecimal commission) {
    }

    final AssetClass assetClass;
    final Source source;
    final LocalDate tradeDate;
    String isin;
    Side side;
    BigDecimal quantity;
    BigDecimal price;
    BigDecimal commission;
    LocalDate settleDate;
    String counterparty;
    UnaryOperator<BigDecimal> netRounding = Money::toCents;

    private ConfirmDraft(AssetClass assetClass, Source source, LocalDate tradeDate) {
        this.assetClass = assetClass;
        this.source = source;
        this.tradeDate = tradeDate;
    }

    /**
     * The clean confirm a broker sends for these fills: total quantity, quantity-weighted average
     * price and total commission. For a single fill that is just the fill itself.
     */
    static ConfirmDraft aggregate(AssetClass assetClass, Source source, String isin, Side side, String counterparty,
                                  LocalDate tradeDate, LocalDate settleDate, List<Fill> fills) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (Fill fill : fills) {
            quantity = quantity.add(fill.quantity());
            notional = notional.add(fill.quantity().multiply(fill.price()));
            commission = commission.add(fill.commission());
        }
        ConfirmDraft draft = new ConfirmDraft(assetClass, source, tradeDate);
        draft.isin = isin;
        draft.side = side;
        draft.quantity = quantity;
        draft.price = fills.size() == 1
                ? fills.getFirst().price()
                : notional.divide(quantity, Money.PRICE_SCALE, RoundingMode.HALF_EVEN);
        draft.commission = commission;
        draft.settleDate = settleDate;
        draft.counterparty = counterparty;
        return draft;
    }

    BigDecimal netAmount() {
        return netRounding.apply(side.netOf(assetClass.principal(quantity, price), commission));
    }

    ExternalConfirm build(String brokerRef) {
        return new ExternalConfirm(brokerRef, source, isin, side, quantity, price, commission,
                tradeDate, settleDate, counterparty, netAmount());
    }
}
