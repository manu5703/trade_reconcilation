package com.recon.datagen;

import com.recon.core.model.BreakType;
import com.recon.datagen.refdata.ReferenceData;
import java.util.EnumMap;
import java.util.Map;

/** Running counts of what was generated, printed by the CLI. */
public final class GenerationSummary {

    private final Map<BreakType, Integer> breaks = new EnumMap<>(BreakType.class);
    private final Map<NoiseType, Integer> noise = new EnumMap<>(NoiseType.class);
    private ReferenceData referenceData;
    private int cases;
    private int internalTrades;
    private int blockAllocations;
    private int confirms;

    void referenceData(ReferenceData data) {
        referenceData = data;
    }

    void record(TradeCase tradeCase) {
        cases++;
        internalTrades += tradeCase.trades().size();
        if (tradeCase.blockRef() != null) {
            blockAllocations += tradeCase.trades().size();
        }
        confirms += tradeCase.confirms().size();
        if (tradeCase.breakType() != null) {
            breaks.merge(tradeCase.breakType(), 1, Integer::sum);
        }
        if (tradeCase.noise() != null) {
            noise.merge(tradeCase.noise(), 1, Integer::sum);
        }
    }

    public ReferenceData referenceData() { return referenceData; }
    public int cases() { return cases; }
    public int internalTrades() { return internalTrades; }
    public int blockAllocations() { return blockAllocations; }
    public int confirms() { return confirms; }

    public int breaks(BreakType type) {
        return breaks.getOrDefault(type, 0);
    }

    public int totalBreaks() {
        return breaks.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int noise(NoiseType type) {
        return noise.getOrDefault(type, 0);
    }

    public int totalNoise() {
        return noise.values().stream().mapToInt(Integer::intValue).sum();
    }
}
