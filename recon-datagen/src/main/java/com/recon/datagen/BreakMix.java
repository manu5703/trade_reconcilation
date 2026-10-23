package com.recon.datagen;

import com.recon.core.model.BreakType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Relative weights of the break types to inject, e.g. {@code PRICE_OUT_OF_TOLERANCE=2,MISSING_CONFIRM=1}. */
public record BreakMix(Map<BreakType, Integer> weights) {

    public BreakMix {
        EnumMap<BreakType, Integer> copy = new EnumMap<>(BreakType.class);
        for (BreakType type : BreakType.values()) {
            int weight = weights.getOrDefault(type, 0);
            if (weight < 0) {
                throw new IllegalArgumentException("Break weight for " + type + " must not be negative");
            }
            copy.put(type, weight);
        }
        if (copy.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            throw new IllegalArgumentException("At least one break type needs a positive weight");
        }
        weights = copy;
    }

    public static BreakMix uniform() {
        Map<BreakType, Integer> weights = new EnumMap<>(BreakType.class);
        for (BreakType type : BreakType.values()) {
            weights.put(type, 1);
        }
        return new BreakMix(weights);
    }

    /** Parses {@code TYPE=weight,...}. Types that aren't listed get weight 0. */
    public static BreakMix parse(String spec) {
        Map<BreakType, Integer> weights = new EnumMap<>(BreakType.class);
        for (String entry : spec.split(",")) {
            String[] parts = entry.split("=");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Break mix entry '" + entry.trim() + "' should look like TYPE=weight");
            }
            BreakType type = parseType(parts[0].trim());
            try {
                weights.put(type, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Break mix weight for " + type + " is not an integer: '" + parts[1].trim() + "'", e);
            }
        }
        return new BreakMix(weights);
    }

    private static BreakType parseType(String name) {
        try {
            return BreakType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown break type '" + name + "'; expected one of " + Arrays.toString(BreakType.values()), e);
        }
    }

    /**
     * Splits {@code total} breaks across types in proportion to the weights, using the largest
     * remainder method: everyone gets the floor of their exact share, and the few left over go to
     * the largest fractional parts (ties broken by enum order). Integer arithmetic only, so the
     * result is exact and the counts always sum to {@code total}.
     */
    public Map<BreakType, Integer> allocate(int total) {
        long weightSum = weights.values().stream().mapToLong(Integer::longValue).sum();
        Map<BreakType, Integer> counts = new EnumMap<>(BreakType.class);
        int assigned = 0;
        for (BreakType type : BreakType.values()) {
            int floor = (int) (total * (long) weights.get(type) / weightSum);
            counts.put(type, floor);
            assigned += floor;
        }
        List<BreakType> byRemainder = Arrays.stream(BreakType.values())
                .sorted(Comparator.comparingLong((BreakType t) -> total * (long) weights.get(t) % weightSum).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        for (int i = 0; i < total - assigned; i++) {
            counts.merge(byRemainder.get(i), 1, Integer::sum);
        }
        return counts;
    }
}
