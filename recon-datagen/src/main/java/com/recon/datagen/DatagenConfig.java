package com.recon.datagen;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Generator settings. Rates are plain probabilities, so {@code double} is fine here; nothing
 * monetary is stored as a double.
 *
 * @param tradesPerDay internal trades per business day (the denominator of {@code breakRate})
 * @param breakRate    fraction of internal trades that get an injected break
 * @param noiseRate    fraction of clean cases that get harmless noise
 * @param blockShare   fraction of internal trades that are block allocations
 */
public record DatagenConfig(
        int tradesPerDay,
        long seed,
        LocalDate startDate,
        int businessDays,
        double breakRate,
        double noiseRate,
        double blockShare,
        BreakMix breakMix,
        int instruments,
        int counterparties,
        int accounts) {

    /** Fixed default so a run never depends on today's date. */
    public static final LocalDate DEFAULT_START_DATE = LocalDate.of(2026, 1, 15);

    public DatagenConfig {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(breakMix, "breakMix");
        if (tradesPerDay < 1) {
            throw new IllegalArgumentException("trades must be at least 1, was " + tradesPerDay);
        }
        if (businessDays < 1) {
            throw new IllegalArgumentException("days must be at least 1, was " + businessDays);
        }
        requireFraction(breakRate, "break rate");
        requireFraction(noiseRate, "noise rate");
        requireFraction(blockShare, "block share");
        if (instruments < 1 || counterparties < 2) {
            throw new IllegalArgumentException("need at least 1 instrument and 2 counterparties (a counterparty mismatch needs another one)");
        }
        // A block needs 2..8 distinct accounts.
        if (accounts < 8) {
            throw new IllegalArgumentException("need at least 8 accounts, was " + accounts);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static void requireFraction(double value, String name) {
        if (!(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, was " + value);
        }
    }

    public static final class Builder {
        private int tradesPerDay = 10_000;
        private long seed = 42;
        private LocalDate startDate = DEFAULT_START_DATE;
        private int businessDays = 1;
        private double breakRate = 0.03;
        private double noiseRate = 0.10;
        private double blockShare = 0.15;
        private BreakMix breakMix = BreakMix.uniform();
        private int instruments = 200;
        private int counterparties = 30;
        private int accounts = 2_000;

        public Builder tradesPerDay(int value) { tradesPerDay = value; return this; }
        public Builder seed(long value) { seed = value; return this; }
        public Builder startDate(LocalDate value) { startDate = value; return this; }
        public Builder businessDays(int value) { businessDays = value; return this; }
        public Builder breakRate(double value) { breakRate = value; return this; }
        public Builder noiseRate(double value) { noiseRate = value; return this; }
        public Builder blockShare(double value) { blockShare = value; return this; }
        public Builder breakMix(BreakMix value) { breakMix = value; return this; }
        public Builder instruments(int value) { instruments = value; return this; }
        public Builder counterparties(int value) { counterparties = value; return this; }
        public Builder accounts(int value) { accounts = value; return this; }

        public DatagenConfig build() {
            return new DatagenConfig(tradesPerDay, seed, startDate, businessDays, breakRate, noiseRate,
                    blockShare, breakMix, instruments, counterparties, accounts);
        }
    }
}
