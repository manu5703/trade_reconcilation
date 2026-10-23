package com.recon.core.match;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass 5: pairs what is left when exactly one or two things are wrong.
 *
 * <p>Within each (isin, tradeDate) bucket, every unmatched trade is scored against every
 * unmatched confirm: 100 points minus the {@link Field} penalties for the fields that differ.
 * Pairs scoring above the threshold are accepted greedily, best score first, each record used at
 * most once. Every differing field on an accepted pair becomes a field-level break.
 *
 * <p>Side is a scored field rather than part of the bucket key: bucketing on side would put a
 * side-flipped confirm in a different bucket, and SIDE_MISMATCH could never be found
 * (docs/decisions/0005).
 *
 * <p>Cost is the sum of (trades × confirms) per bucket. Leftovers after the exact and block
 * passes are a few percent of the input, spread over many buckets, so the buckets stay small.
 */
final class FuzzyPass implements MatchPass {

    private record Bucket(String isin, LocalDate tradeDate) {
    }

    private record Candidate(NormalizedTrade trade, NormalizedConfirm confirm, int score, List<FieldDiff> diffs) {
    }

    /** Best score first; refs break ties so the greedy choice is deterministic. */
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingInt(Candidate::score).reversed()
            .thenComparing(c -> c.trade().ref())
            .thenComparing(c -> c.confirm().ref());

    private final int thresholdPoints;

    FuzzyPass(int thresholdPoints) {
        this.thresholdPoints = thresholdPoints;
    }

    @Override
    public void apply(MatchState state) {
        Map<Bucket, List<NormalizedConfirm>> confirmsByBucket = new HashMap<>();
        for (NormalizedConfirm confirm : state.unmatchedConfirms()) {
            confirmsByBucket.computeIfAbsent(new Bucket(confirm.isin(), confirm.tradeDate()), k -> new ArrayList<>()).add(confirm);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (NormalizedTrade trade : state.unmatchedTrades()) {
            List<NormalizedConfirm> bucket = confirmsByBucket.getOrDefault(new Bucket(trade.isin(), trade.tradeDate()), List.of());
            Expectation expected = Expectation.of(trade);
            for (NormalizedConfirm confirm : bucket) {
                List<FieldDiff> diffs = expected.diff(confirm, state.tolerances.forAssetClass(trade.assetClass()));
                int score = 100 - diffs.stream().mapToInt(d -> d.field().penalty()).sum();
                if (score > thresholdPoints) {
                    candidates.add(new Candidate(trade, confirm, score, diffs));
                }
            }
        }
        candidates.sort(BEST_FIRST);

        Set<String> takenTrades = new HashSet<>();
        Set<String> takenConfirms = new HashSet<>();
        for (Candidate c : candidates) {
            if (takenTrades.contains(c.trade().ref()) || takenConfirms.contains(c.confirm().ref())) {
                continue;
            }
            takenTrades.add(c.trade().ref());
            takenConfirms.add(c.confirm().ref());
            state.addMatch(Match.of(MatchRule.FUZZY, c.score(), List.of(c.trade().ref()), c.confirm().ref()));
            state.breaks.addAll(breaksFor(c));
        }
    }

    /**
     * One break per differing field. A net-amount difference only counts on its own: alongside
     * another field it is just that field's knock-on effect, since a confirm's net is derived from
     * its own quantity, price and commission.
     */
    private static List<Break> breaksFor(Candidate c) {
        boolean netOnly = c.diffs().size() == 1 && c.diffs().getFirst().field() == Field.NET_AMOUNT;
        return c.diffs().stream()
                .filter(d -> netOnly || d.field() != Field.NET_AMOUNT)
                .map(d -> Break.fieldBreak(c.trade().ref(), c.confirm().ref(), d))
                .toList();
    }
}
