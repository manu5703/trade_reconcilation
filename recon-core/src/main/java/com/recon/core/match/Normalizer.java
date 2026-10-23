package com.recon.core.match;

import com.recon.core.model.AssetClass;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.refdata.CommissionSchedule;
import com.recon.core.refdata.StaticData;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pass 1: puts both sides into one canonical form so later passes can compare with plain equality.
 * <ul>
 *   <li>ISINs, block refs and counterparty names are trimmed and upper-cased.</li>
 *   <li>Counterparty aliases map to the canonical ID; an unknown name passes through and will show
 *       up as a COUNTERPARTY_MISMATCH.</li>
 *   <li>Decimals get one scale (6, as in NUMERIC(20,6)). That matters because
 *       {@code BigDecimal.equals} and {@code hashCode} include the scale, and the exact pass uses
 *       quantity as part of a hash key.</li>
 * </ul>
 * Internal trades must reference known instruments and counterparties; that's our own data, so a
 * miss is a reference-data bug and fails fast. Confirms are external and are never rejected.
 */
public final class Normalizer {

    public static final int SCALE = 6;

    private final StaticData staticData;

    public Normalizer(StaticData staticData) {
        this.staticData = staticData;
    }

    public NormalizedTrade normalize(InternalTrade trade) {
        String isin = StaticData.normalizeCode(trade.isin());
        AssetClass assetClass = staticData.assetClass(isin).orElseThrow(() -> new IllegalArgumentException(
                "Trade " + trade.tradeRef() + " has unknown ISIN " + isin + "; add it to the instrument reference data"));
        String counterparty = staticData.canonicalCounterparty(StaticData.normalizeCode(trade.counterparty()));
        BigDecimal bps = staticData.commissionBps(counterparty).orElseThrow(() -> new IllegalArgumentException(
                "Trade " + trade.tradeRef() + " has unknown counterparty " + counterparty + "; add it to the counterparty reference data"));
        BigDecimal quantity = scale(trade.quantity());
        BigDecimal price = scale(trade.price());
        BigDecimal expectedCommission = CommissionSchedule.scheduled(assetClass, bps, assetClass.principal(quantity, price));
        String blockRef = trade.isBlockAllocation() ? StaticData.normalizeCode(trade.blockRef()) : null;
        return new NormalizedTrade(trade.tradeRef(), blockRef, isin, trade.side(), quantity, price, trade.tradeDate(),
                trade.settleDate(), counterparty, scale(trade.netAmount()), scale(expectedCommission), assetClass);
    }

    public NormalizedConfirm normalize(ExternalConfirm confirm) {
        return new NormalizedConfirm(confirm.brokerRef(), StaticData.normalizeCode(confirm.isin()), confirm.side(),
                scale(confirm.quantity()), scale(confirm.price()), scale(confirm.commission()), confirm.tradeDate(),
                confirm.settleDate(), staticData.canonicalCounterparty(StaticData.normalizeCode(confirm.counterparty())),
                scale(confirm.netAmount()));
    }

    static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}
