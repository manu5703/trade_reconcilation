package com.recon.datagen;

import com.recon.core.model.AssetClass;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.tolerance.Tolerance;
import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.refdata.Counterparty;
import com.recon.datagen.refdata.Instrument;
import com.recon.datagen.refdata.ReferenceData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Test-only reference check: compares one confirm with the trades it should match, after the
 * normalization a correct matcher would apply, and reports which fields are out of tolerance.
 * Deliberately independent of the generator's internals.
 */
final class CaseOracle {

    private final ToleranceConfig tolerances;
    private final Map<String, Instrument> instruments;
    private final Map<String, Counterparty> counterparties;
    private final Map<String, String> counterpartyByAlias = new HashMap<>();

    CaseOracle(ReferenceData referenceData, ToleranceConfig tolerances) {
        this.tolerances = tolerances;
        this.instruments = referenceData.instrumentsByIsin();
        this.counterparties = referenceData.counterpartiesById();
        for (Counterparty c : referenceData.counterparties()) {
            counterpartyByAlias.put(c.id(), c.id());
            c.aliases().forEach(alias -> counterpartyByAlias.put(alias.toUpperCase(Locale.ROOT), c.id()));
        }
    }

    /** Fields on which {@code confirm} disagrees with {@code trades} beyond tolerance. */
    Set<String> differingFields(List<InternalTrade> trades, ExternalConfirm confirm) {
        InternalTrade first = trades.getFirst();
        Instrument instrument = instruments.get(first.isin());
        AssetClass assetClass = instrument.assetClass();
        Tolerance tolerance = tolerances.forAssetClass(assetClass);
        Counterparty counterparty = counterparties.get(first.counterparty());

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (InternalTrade t : trades) {
            quantity = quantity.add(t.quantity());
            notional = notional.add(t.quantity().multiply(t.price()));
            net = net.add(t.netAmount());
            commission = commission.add(counterparty.scheduledCommission(assetClass, assetClass.principal(t.quantity(), t.price())));
        }
        BigDecimal averagePrice = notional.divide(quantity, 10, RoundingMode.HALF_EVEN);

        Set<String> fields = new TreeSet<>();
        if (!confirm.isin().trim().toUpperCase(Locale.ROOT).equals(first.isin())) {
            fields.add("isin");
        }
        if (confirm.side() != first.side()) {
            fields.add("side");
        }
        if (!confirm.tradeDate().equals(first.tradeDate())) {
            fields.add("tradeDate");
        }
        if (!confirm.settleDate().equals(first.settleDate())) {
            fields.add("settleDate");
        }
        if (!first.counterparty().equals(counterpartyByAlias.get(confirm.counterparty().trim().toUpperCase(Locale.ROOT)))) {
            fields.add("counterparty");
        }
        if (confirm.quantity().compareTo(quantity) != 0) {
            fields.add("quantity");
        }
        if (!tolerance.priceWithin(averagePrice, confirm.price())) {
            fields.add("price");
        }
        if (!tolerance.netAmountWithin(net, confirm.netAmount())) {
            fields.add("netAmount");
        }
        if (!tolerance.commissionWithin(commission, confirm.commission())) {
            fields.add("commission");
        }
        return fields;
    }
}
