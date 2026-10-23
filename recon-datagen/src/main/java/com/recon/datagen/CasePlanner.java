package com.recon.datagen;

import com.recon.core.model.BreakType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Decides the shape of every case for one business day before any trade is generated, so the
 * block share and break counts are exact rather than "about right on average".
 *
 * <p>The plan is two parallel arrays (allocations per case, break type per case) rather than a
 * list of objects, so planning 10M trades costs tens of megabytes, not gigabytes.
 */
final class CasePlanner {

    static final int MIN_BLOCK = 2;
    static final int MAX_BLOCK = 8;

    private CasePlanner() {
    }

    /** {@code allocations[i]} is 0 for an unexpected confirm, 1 for a single trade, 2-8 for a block. */
    record CasePlan(int[] allocations, BreakType[] breakTypes) {
        int size() {
            return allocations.length;
        }
    }

    static CasePlan plan(int trades, DatagenConfig config, RandomGenerator rng) {
        List<Integer> blockSizes = blockSizes(trades, config.blockShare(), rng);
        int blockTrades = blockSizes.stream().mapToInt(Integer::intValue).sum();
        int singles = trades - blockTrades;

        int totalBreaks = (int) Math.round(trades * config.breakRate());
        Map<BreakType, Integer> breakCounts = config.breakMix().allocate(totalBreaks);
        // Unexpected confirms have no internal trade, so they are extra cases on top of the N trades.
        int unexpected = breakCounts.get(BreakType.UNEXPECTED_CONFIRM);
        int singleBreaks = totalBreaks - unexpected;
        if (singleBreaks > singles) {
            throw new IllegalArgumentException(("break rate %.4f needs %d single trades with breaks, but only %d of %d trades "
                    + "are singles (block share %.2f); lower the break rate or the block share")
                    .formatted(config.breakRate(), singleBreaks, singles, trades, config.blockShare()));
        }

        int cases = blockSizes.size() + singles + unexpected;
        int[] allocations = new int[cases];
        BreakType[] breakTypes = new BreakType[cases];
        int i = 0;
        for (int size : blockSizes) {
            allocations[i++] = size;
        }
        // Breaks go on singles only (see docs/decisions/0004). Laid out in order here; the shuffle
        // below spreads them uniformly.
        for (BreakType type : BreakType.values()) {
            if (type == BreakType.UNEXPECTED_CONFIRM) {
                continue;
            }
            for (int n = 0; n < breakCounts.get(type); n++) {
                allocations[i] = 1;
                breakTypes[i++] = type;
            }
        }
        for (int n = 0; n < singles - singleBreaks; n++) {
            allocations[i++] = 1;
        }
        for (int n = 0; n < unexpected; n++) {
            allocations[i] = 0;
            breakTypes[i++] = BreakType.UNEXPECTED_CONFIRM;
        }

        shuffle(allocations, breakTypes, rng);
        return new CasePlan(allocations, breakTypes);
    }

    /** Random block sizes of 2-8 whose total is round(trades * share), or one less if the last gap is a single trade. */
    private static List<Integer> blockSizes(int trades, double share, RandomGenerator rng) {
        int target = (int) Math.round(trades * share);
        List<Integer> sizes = new ArrayList<>();
        int planned = 0;
        while (target - planned >= MIN_BLOCK) {
            int size = Math.min(rng.nextInt(MIN_BLOCK, MAX_BLOCK + 1), target - planned);
            sizes.add(size);
            planned += size;
        }
        return sizes;
    }

    /** Fisher-Yates, applying the same swaps to both arrays so each case keeps its break type. */
    private static void shuffle(int[] allocations, BreakType[] breakTypes, RandomGenerator rng) {
        for (int i = allocations.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int a = allocations[i];
            allocations[i] = allocations[j];
            allocations[j] = a;
            BreakType b = breakTypes[i];
            breakTypes[i] = breakTypes[j];
            breakTypes[j] = b;
        }
    }
}
