package com.recon.core.match;

import com.recon.core.model.AssetClass;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.model.Side;
import com.recon.core.model.Source;
import com.recon.core.refdata.CommissionSchedule;
import com.recon.core.refdata.CounterpartyRef;
import com.recon.core.refdata.StaticData;
import com.recon.core.tolerance.Tolerance;
import com.recon.core.tolerance.ToleranceConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Small, readable test universe: two equities, one bond, two counterparties. */
final class Fixtures {

    static final String APPLE = "US0378331005";
    static final String MICROSOFT = "US5949181045";
    static final String BOND = "XS0000000009";
    static final String ALPHA = "CP001";
    static final String BETA = "CP002";
    static final LocalDate T = LocalDate.of(2026, 1, 15);
    static final LocalDate T_PLUS_1 = LocalDate.of(2026, 1, 16);

    static final StaticData STATIC_DATA = new StaticData(
            Map.of(APPLE, AssetClass.EQUITY, MICROSOFT, AssetClass.EQUITY, BOND, AssetClass.FIXED_INCOME),
            List.of(new CounterpartyRef(ALPHA, List.of("Alpha Securities LLC", "ALPHA SEC"), new BigDecimal("1.0")),
                    new CounterpartyRef(BETA, List.of("Beta Capital"), new BigDecimal("2.5"))));

    /** Same values as config/tolerances.properties. */
    static final ToleranceConfig TOLERANCES = new ToleranceConfig(Map.of(
            AssetClass.EQUITY, new Tolerance(dec("0.005"), dec("2"), dec("0.05"), dec("2"), dec("0.01")),
            AssetClass.FIXED_INCOME, new Tolerance(dec("0.001"), dec("1"), dec("0.05"), dec("1"), dec("0.01"))));

    private Fixtures() {
    }

    static Matcher fullMatcher() {
        return Matcher.full(STATIC_DATA, TOLERANCES);
    }

    static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    static TradeBuilder trade(String ref) {
        return new TradeBuilder(ref);
    }

    /** A clean confirm for {@code trade}: same economics, scheduled commission. */
    static ConfirmBuilder confirmFor(InternalTrade trade, String ref) {
        ConfirmBuilder b = new ConfirmBuilder(ref);
        b.isin = trade.isin();
        b.side = trade.side();
        b.quantity = trade.quantity();
        b.price = trade.price();
        b.tradeDate = trade.tradeDate();
        b.settleDate = trade.settleDate();
        b.counterparty = trade.counterparty();
        b.commission = commission(trade.isin(), trade.counterparty(), trade.quantity(), trade.price());
        return b;
    }

    /** A clean confirm for a whole block: total quantity, weighted average price, summed commission. */
    static ConfirmBuilder confirmForBlock(List<InternalTrade> allocations, String ref) {
        ConfirmBuilder b = confirmFor(allocations.getFirst(), ref);
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (InternalTrade t : allocations) {
            quantity = quantity.add(t.quantity());
            notional = notional.add(t.quantity().multiply(t.price()));
            commission = commission.add(commission(t.isin(), t.counterparty(), t.quantity(), t.price()));
        }
        b.quantity = quantity;
        b.price = notional.divide(quantity, 6, RoundingMode.HALF_EVEN);
        b.commission = commission;
        return b;
    }

    private static BigDecimal commission(String isin, String counterparty, BigDecimal quantity, BigDecimal price) {
        AssetClass assetClass = STATIC_DATA.assetClass(isin).orElseThrow();
        BigDecimal bps = STATIC_DATA.commissionBps(counterparty).orElseThrow();
        return CommissionSchedule.scheduled(assetClass, bps, assetClass.principal(quantity, price));
    }

    private static BigDecimal net(String isin, Side side, BigDecimal quantity, BigDecimal price, BigDecimal commission) {
        AssetClass assetClass = STATIC_DATA.assetClass(StaticData.normalizeCode(isin)).orElse(AssetClass.EQUITY);
        return side.netOf(assetClass.principal(quantity, price), commission).setScale(2, RoundingMode.HALF_EVEN);
    }

    static final class TradeBuilder {
        private final String ref;
        private String blockRef;
        private String account = "ACC00001";
        private String isin = APPLE;
        private Side side = Side.BUY;
        private BigDecimal quantity = dec("1000");
        private BigDecimal price = dec("100.00");
        private LocalDate tradeDate = T;
        private LocalDate settleDate = T_PLUS_1;
        private String counterparty = ALPHA;

        private TradeBuilder(String ref) {
            this.ref = ref;
        }

        TradeBuilder block(String value) { blockRef = value; return this; }
        TradeBuilder account(String value) { account = value; return this; }
        TradeBuilder isin(String value) { isin = value; return this; }
        TradeBuilder side(Side value) { side = value; return this; }
        TradeBuilder quantity(String value) { quantity = dec(value); return this; }
        TradeBuilder price(String value) { price = dec(value); return this; }
        TradeBuilder tradeDate(LocalDate value) { tradeDate = value; return this; }
        TradeBuilder settleDate(LocalDate value) { settleDate = value; return this; }
        TradeBuilder counterparty(String value) { counterparty = value; return this; }

        /** Net amount is derived the way a booking system would: principal ± scheduled commission. */
        InternalTrade build() {
            BigDecimal commission = commission(isin, counterparty, quantity, price);
            return new InternalTrade(ref, blockRef, account, isin, side, quantity, price, tradeDate, settleDate,
                    counterparty, net(isin, side, quantity, price, commission));
        }
    }

    static final class ConfirmBuilder {
        private final String ref;
        private String isin;
        private Side side;
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal commission;
        private LocalDate tradeDate;
        private LocalDate settleDate;
        private String counterparty;
        private BigDecimal netOverride;

        private ConfirmBuilder(String ref) {
            this.ref = ref;
        }

        ConfirmBuilder isin(String value) { isin = value; return this; }
        ConfirmBuilder side(Side value) { side = value; return this; }
        ConfirmBuilder quantity(String value) { quantity = dec(value); return this; }
        ConfirmBuilder price(String value) { price = dec(value); return this; }
        ConfirmBuilder commission(String value) { commission = dec(value); return this; }
        ConfirmBuilder tradeDate(LocalDate value) { tradeDate = value; return this; }
        ConfirmBuilder settleDate(LocalDate value) { settleDate = value; return this; }
        ConfirmBuilder counterparty(String value) { counterparty = value; return this; }
        ConfirmBuilder netAmount(String value) { netOverride = dec(value); return this; }

        /** Like a real broker's confirm, the net amount follows from its own fields unless overridden. */
        ExternalConfirm build() {
            BigDecimal netAmount = netOverride != null ? netOverride : net(isin, side, quantity, price, commission);
            return new ExternalConfirm(ref, Source.BROKER, isin, side, quantity, price, commission, tradeDate, settleDate,
                    counterparty, netAmount);
        }
    }
}
