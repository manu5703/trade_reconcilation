package com.recon.datagen;

import com.recon.core.model.AssetClass;
import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.model.Side;
import com.recon.core.tolerance.Tolerance;
import com.recon.core.tolerance.ToleranceConfig;
import com.recon.datagen.ConfirmDraft.Fill;
import com.recon.datagen.refdata.Counterparty;
import com.recon.datagen.refdata.Instrument;
import com.recon.datagen.refdata.ReferenceData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Turns one planned case into concrete trades and confirms for a single business day.
 *
 * <p>Every case starts as a clean booking plus its clean confirm. Then exactly one thing may
 * happen to the confirm: a break (decided by the plan) or harmless noise (decided here with
 * probability {@code noiseRate}, clean cases only).
 */
final class CaseGenerator {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal EQUITY_TICK = new BigDecimal("0.01");
    private static final BigDecimal BOND_TICK = new BigDecimal("0.001");

    private final ReferenceData referenceData;
    private final ToleranceConfig tolerances;
    private final double noiseRate;
    private final LocalDate tradeDate;
    private final String datePart;
    private final RandomGenerator rng;

    // Per-day sequences make every reference unique and reproducible.
    private int caseSeq;
    private int tradeSeq;
    private int blockSeq;
    private int confirmSeq;

    CaseGenerator(ReferenceData referenceData, ToleranceConfig tolerances, double noiseRate,
                  LocalDate tradeDate, RandomGenerator rng) {
        this.referenceData = referenceData;
        this.tolerances = tolerances;
        this.noiseRate = noiseRate;
        this.tradeDate = tradeDate;
        this.datePart = tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        this.rng = rng;
    }

    TradeCase next(int allocations, BreakType breakType) {
        String caseId = "C-%s-%07d".formatted(datePart, ++caseSeq);
        Instrument instrument = pick(referenceData.instruments());
        Counterparty counterparty = pick(referenceData.counterparties());
        AssetClass assetClass = instrument.assetClass();
        Tolerance tolerance = tolerances.forAssetClass(assetClass);
        Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
        LocalDate settleDate = BusinessDays.add(tradeDate, assetClass.standardSettlementDays());

        // An unexpected confirm still describes a plausible trade, just one we never booked.
        List<Fill> fills = fills(instrument, counterparty, Math.max(allocations, 1));
        ConfirmDraft draft = ConfirmDraft.aggregate(assetClass, counterparty.confirmSource(), instrument.isin(), side,
                counterparty.id(), tradeDate, settleDate, fills);

        if (breakType == BreakType.UNEXPECTED_CONFIRM) {
            return new TradeCase(caseId, tradeDate, breakType, null, null, null,
                    List.of(), List.of(confirm(draft, counterparty)));
        }

        String blockRef = allocations > 1 ? "BLK-%s-%06d".formatted(datePart, ++blockSeq) : null;
        List<InternalTrade> trades = book(instrument, counterparty, side, settleDate, blockRef, fills);

        if (breakType == null) {
            NoiseType noise = null;
            if (rng.nextDouble() < noiseRate) {
                BigDecimal bookedNet = trades.stream().map(InternalTrade::netAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                noise = applyNoise(draft, counterparty, tolerance, bookedNet);
            }
            return new TradeCase(caseId, tradeDate, null, null, noise, blockRef, trades, List.of(confirm(draft, counterparty)));
        }

        return switch (breakType) {
            case MISSING_CONFIRM -> new TradeCase(caseId, tradeDate, breakType, null, null, blockRef, trades, List.of());
            // A resend: same economics, new broker reference.
            case DUPLICATE_CONFIRM -> new TradeCase(caseId, tradeDate, breakType, null, null, blockRef, trades,
                    List.of(confirm(draft, counterparty), confirm(draft, counterparty)));
            default -> {
                String field = applyBreak(breakType, draft, counterparty, tolerance);
                yield new TradeCase(caseId, tradeDate, breakType, field, null, blockRef, trades, List.of(confirm(draft, counterparty)));
            }
        };
    }

    private List<Fill> fills(Instrument instrument, Counterparty counterparty, int count) {
        AssetClass assetClass = instrument.assetClass();
        // ±2% around the reference price, in 4 decimals.
        BigDecimal basePrice = instrument.referencePrice()
                .multiply(BigDecimal.valueOf(0.98 + 0.04 * rng.nextDouble()))
                .setScale(4, RoundingMode.HALF_EVEN);
        BigDecimal tick = assetClass == AssetClass.EQUITY ? EQUITY_TICK : BOND_TICK;

        List<Fill> fills = new ArrayList<>(count);
        for (String account : distinctAccounts(count)) {
            BigDecimal quantity = assetClass == AssetClass.EQUITY
                    ? BigDecimal.valueOf(100L * rng.nextInt(1, 500))        // round lots, 100 to 49,900 shares
                    : BigDecimal.valueOf(1_000L * rng.nextInt(1, 1_000));   // face value, 1k to 999k
            // Block allocations are booked at their own fill price, a few ticks either side of the
            // base; the broker confirms the block at the weighted average.
            BigDecimal price = count == 1 ? basePrice : basePrice.add(tick.multiply(BigDecimal.valueOf(rng.nextInt(-3, 4))));
            BigDecimal commission = counterparty.scheduledCommission(assetClass, assetClass.principal(quantity, price));
            fills.add(new Fill(account, quantity, price, commission));
        }
        return fills;
    }

    private Set<String> distinctAccounts(int count) {
        Set<String> accounts = new LinkedHashSet<>();
        while (accounts.size() < count) {
            accounts.add(pick(referenceData.accounts()));
        }
        return accounts;
    }

    private List<InternalTrade> book(Instrument instrument, Counterparty counterparty, Side side, LocalDate settleDate,
                                     String blockRef, List<Fill> fills) {
        List<InternalTrade> trades = new ArrayList<>(fills.size());
        for (Fill fill : fills) {
            BigDecimal principal = instrument.assetClass().principal(fill.quantity(), fill.price());
            trades.add(new InternalTrade("TR-%s-%08d".formatted(datePart, ++tradeSeq), blockRef, fill.account(),
                    instrument.isin(), side, fill.quantity(), fill.price(), tradeDate, settleDate, counterparty.id(),
                    Money.toCents(side.netOf(principal, fill.commission()))));
        }
        return trades;
    }

    private ExternalConfirm confirm(ConfirmDraft draft, Counterparty counterparty) {
        return draft.build("%s-%s-%07d".formatted(counterparty.refPrefix(), datePart, ++confirmSeq));
    }

    /** Alters one field of the confirm so it is clearly outside tolerance. Returns the field name. */
    private String applyBreak(BreakType type, ConfirmDraft draft, Counterparty counterparty, Tolerance tolerance) {
        switch (type) {
            case QUANTITY_MISMATCH -> {
                // Up to 20% of the quantity, at least one unit (one share, or one 1,000 bond lot).
                BigDecimal unit = draft.assetClass == AssetClass.EQUITY ? BigDecimal.ONE : BigDecimal.valueOf(1_000);
                int maxUnits = Math.max(1, draft.quantity.divideToIntegralValue(unit).intValueExact() / 5);
                BigDecimal delta = unit.multiply(BigDecimal.valueOf(rng.nextInt(1, maxUnits + 1)));
                draft.quantity = rng.nextBoolean() && draft.quantity.compareTo(delta) > 0
                        ? draft.quantity.subtract(delta)
                        : draft.quantity.add(delta);
                return "quantity";
            }
            case PRICE_OUT_OF_TOLERANCE -> {
                // 3x to 20x the tolerance band; rounding up keeps it strictly outside.
                BigDecimal delta = tolerance.priceTolerance(draft.price)
                        .multiply(BigDecimal.valueOf(rng.nextInt(3, 21)))
                        .setScale(Money.PRICE_SCALE, RoundingMode.UP);
                draft.price = rng.nextBoolean() && draft.price.compareTo(delta) > 0
                        ? draft.price.subtract(delta)
                        : draft.price.add(delta);
                return "price";
            }
            case SETTLE_DATE_MISMATCH -> {
                draft.settleDate = BusinessDays.add(draft.settleDate, rng.nextInt(1, 3));
                return "settleDate";
            }
            case SIDE_MISMATCH -> {
                draft.side = draft.side.opposite();
                return "side";
            }
            case COUNTERPARTY_MISMATCH -> {
                Counterparty other;
                do {
                    other = pick(referenceData.counterparties());
                } while (other.id().equals(counterparty.id()));
                draft.counterparty = other.id();
                return "counterparty";
            }
            case COMMISSION_DIFFERENCE -> {
                // $1.00 to $100.00, and never less than twice the tolerance.
                BigDecimal delta = BigDecimal.valueOf(rng.nextInt(100, 10_001), 2)
                        .max(tolerance.commissionAbs().multiply(TWO).setScale(2, RoundingMode.UP));
                draft.commission = rng.nextBoolean() && draft.commission.compareTo(delta) >= 0
                        ? draft.commission.subtract(delta)
                        : draft.commission.add(delta);
                return "commission";
            }
            default -> throw new IllegalStateException(type + " is not a field-level break");
        }
    }

    private NoiseType applyNoise(ConfirmDraft draft, Counterparty counterparty, Tolerance tolerance, BigDecimal bookedNet) {
        NoiseType noise = NoiseType.values()[rng.nextInt(NoiseType.values().length)];
        if (noise == NoiseType.PRICE_WITHIN_TOLERANCE && !shiftPriceWithinTolerance(draft, tolerance, bookedNet)) {
            // The trade is too large for any price move to stay inside the net-amount band.
            noise = NoiseType.NET_AMOUNT_ROUNDING;
        }
        switch (noise) {
            case NET_AMOUNT_ROUNDING -> draft.netRounding = rng.nextBoolean()
                    ? amount -> amount.setScale(2, RoundingMode.DOWN)
                    : amount -> amount.setScale(4, RoundingMode.HALF_UP);
            case REF_FORMAT -> {
                draft.isin = rng.nextBoolean() ? draft.isin.toLowerCase(Locale.ROOT) : " " + draft.isin + " ";
                draft.counterparty = pick(counterparty.aliases());
            }
            case PRICE_WITHIN_TOLERANCE -> {
                // Already applied above.
            }
        }
        return noise;
    }

    /**
     * Moves the confirm price by at most half of whichever is tighter: the price tolerance, or
     * the price move that would push the net amount out of its tolerance. A cent is held back for
     * the confirm's own rounding. Returns false if no move of at least 0.000001 is safe.
     */
    private boolean shiftPriceWithinTolerance(ConfirmDraft draft, Tolerance tolerance, BigDecimal bookedNet) {
        BigDecimal existingGap = draft.netAmount().subtract(bookedNet).abs();
        BigDecimal netBudget = tolerance.netAmountTolerance(bookedNet).subtract(existingGap).subtract(Money.CENT);
        if (netBudget.signum() <= 0) {
            return false;
        }
        // How much the principal changes per 1.00 of price.
        BigDecimal principalPerPricePoint = draft.assetClass.principal(draft.quantity, BigDecimal.ONE);
        BigDecimal maxByNet = netBudget.divide(principalPerPricePoint, Money.PRICE_SCALE, RoundingMode.DOWN);
        BigDecimal maxDelta = tolerance.priceTolerance(draft.price).min(maxByNet)
                .divide(TWO, Money.PRICE_SCALE, RoundingMode.DOWN);
        long maxMicros = maxDelta.movePointRight(Money.PRICE_SCALE).longValueExact();
        if (maxMicros < 1) {
            return false;
        }
        BigDecimal delta = BigDecimal.valueOf(rng.nextLong(1, maxMicros + 1), Money.PRICE_SCALE);
        draft.price = rng.nextBoolean() ? draft.price.add(delta) : draft.price.subtract(delta);
        return true;
    }

    private <T> T pick(List<T> items) {
        return items.get(rng.nextInt(items.size()));
    }
}
