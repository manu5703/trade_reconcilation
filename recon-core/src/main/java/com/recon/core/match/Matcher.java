package com.recon.core.match;

import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.refdata.StaticData;
import com.recon.core.tolerance.ToleranceConfig;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the matching pipeline over one batch of trades and confirms.
 *
 * <p>Pure and deterministic: no clock, no I/O, no shared state. The same inputs, in any order,
 * always give the same {@link MatchResult}, which is what makes re-running a business date
 * idempotent.
 */
public final class Matcher {

    /** Fuzzy pairs must score above 70 out of 100. */
    public static final int DEFAULT_FUZZY_THRESHOLD = 70;

    private final Normalizer normalizer;
    private final ToleranceConfig tolerances;
    private final List<MatchPass> passes;

    // Package-private so tests can assemble alternative pipelines.
    Matcher(StaticData staticData, ToleranceConfig tolerances, List<MatchPass> passes) {
        this.normalizer = new Normalizer(staticData);
        this.tolerances = tolerances;
        this.passes = passes;
    }

    /** Normalize, block, exact, duplicates, fuzzy, leftovers. */
    public static Matcher full(StaticData staticData, ToleranceConfig tolerances) {
        return new Matcher(staticData, tolerances, List.of(
                new BlockPass(), new ExactPass(), new DuplicatePass(), new FuzzyPass(DEFAULT_FUZZY_THRESHOLD), new LeftoversPass()));
    }

    /**
     * The comparison point for scoring: normalize and the exact pass only. Anything that doesn't
     * match exactly (blocks, field breaks, duplicates) is reported as missing or unexpected.
     */
    public static Matcher baseline(StaticData staticData, ToleranceConfig tolerances) {
        return new Matcher(staticData, tolerances, List.of(new ExactPass(), new LeftoversPass()));
    }

    public MatchResult match(Collection<InternalTrade> trades, Collection<ExternalConfirm> confirms) {
        requireUniqueRefs(trades.stream().map(InternalTrade::tradeRef).toList(), "trade");
        requireUniqueRefs(confirms.stream().map(ExternalConfirm::brokerRef).toList(), "broker");

        List<NormalizedTrade> normalizedTrades = trades.stream().map(normalizer::normalize)
                .sorted(Comparator.comparing(NormalizedTrade::ref)).toList();
        List<NormalizedConfirm> normalizedConfirms = confirms.stream().map(normalizer::normalize)
                .sorted(Comparator.comparing(NormalizedConfirm::ref)).toList();

        MatchState state = new MatchState(normalizedTrades, normalizedConfirms, tolerances);
        for (MatchPass pass : passes) {
            pass.apply(state);
        }

        List<Match> matches = state.matches.stream().sorted(Comparator.comparing(Match::externalRef)).toList();
        List<Break> breaks = state.breaks.stream().sorted(Break.ORDER).toList();
        return new MatchResult(matches, breaks);
    }

    private static void requireUniqueRefs(List<String> refs, String kind) {
        Set<String> seen = new HashSet<>();
        for (String ref : refs) {
            if (!seen.add(ref)) {
                throw new IllegalArgumentException("Duplicate " + kind + " reference in input: " + ref);
            }
        }
    }
}
