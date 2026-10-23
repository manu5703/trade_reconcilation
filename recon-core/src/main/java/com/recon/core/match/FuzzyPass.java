package com.recon.core.match;

import com.recon.core.model.Side;
import java.math.BigDecimal;
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
 * <p>An unmatched trade is scored against unmatched confirms on the same ISIN and trade date:
 * 100 points minus the {@link Field} penalties for the fields that differ. Pairs scoring above
 * the threshold are accepted greedily, best score first, each record used at most once. Every
 * differing field on an accepted pair becomes a field-level break.
 *
 * <p>Side is a scored field rather than part of the bucket key: bucketing on side would put a
 * side-flipped confirm in a different bucket, and SIDE_MISMATCH could never be found
 * (docs/decisions/0005).
 *
 * <p><b>Blocking.</b> Scoring every trade against every confirm of an (isin, tradeDate) bucket is
 * quadratic, and with a fixed number of instruments the buckets grow with volume: about 4.5M
 * comparisons at 1M rows, 450M at 10M. But an accepted pair can differ in at most one of side,
 * counterparty and quantity, because any two of them cost at least 40 points. So it always
 * agrees on at least two of the three, and indexing confirms by (side, counterparty),
 * (side, quantity) and (counterparty, quantity) finds exactly the same candidates while comparing
 * only a handful per trade. The constructor checks that the weights and threshold still
 * guarantee this, so changing them can't silently lose matches (docs/decisions/0008).
 */
final class FuzzyPass implements MatchPass {

    // Three blocking keys. They are different record types, so their keys never collide in one map.
    private record SideCounterparty(String isin, LocalDate tradeDate, Side side, String counterparty) {
    }

    private record SideQuantity(String isin, LocalDate tradeDate, Side side, BigDecimal quantity) {
    }

    private record CounterpartyQuantity(String isin, LocalDate tradeDate, String counterparty, BigDecimal quantity) {
    }

    /** The fields the blocking keys are built from: an accepted pair agrees on at least two of them. */
    static final List<Field> BLOCKING_FIELDS = List.of(Field.SIDE, Field.COUNTERPARTY, Field.QUANTITY);

    private record Candidate(NormalizedTrade trade, NormalizedConfirm confirm, int score, List<FieldDiff> diffs) {
    }

    /** Best score first; refs break ties so the greedy choice is deterministic. */
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingInt(Candidate::score).reversed()
            .thenComparing(c -> c.trade().ref())
            .thenComparing(c -> c.confirm().ref());

    private final int thresholdPoints;

    FuzzyPass(int thresholdPoints) {
        if (!blockingIsLossless(thresholdPoints)) {
            throw new IllegalArgumentException(("Fuzzy threshold %d is too low for blocking: a pair that differs in two of %s "
                    + "could score above it and would never be compared. Raise the threshold or the penalties.")
                    .formatted(thresholdPoints, BLOCKING_FIELDS));
        }
        this.thresholdPoints = thresholdPoints;
    }

    /**
     * True if every pair that differs in two or more blocking fields scores at or below the
     * threshold. The best such pair differs in only the two cheapest blocking fields.
     */
    static boolean blockingIsLossless(int thresholdPoints) {
        int cheapestTwo = BLOCKING_FIELDS.stream().mapToInt(Field::penalty).sorted().limit(2).sum();
        return 100 - cheapestTwo <= thresholdPoints;
    }

    @Override
    public void apply(MatchState state) {
        Map<Object, List<NormalizedConfirm>> confirmsByBlock = new HashMap<>();
        for (NormalizedConfirm c : state.unmatchedConfirms()) {
            for (Object key : blockingKeys(c.isin(), c.tradeDate(), c.side(), c.counterparty(), c.quantity())) {
                confirmsByBlock.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (NormalizedTrade trade : state.unmatchedTrades()) {
            Expectation expected = Expectation.of(trade);
            // A confirm that agrees on all three fields shows up under all three keys; score it once.
            Set<String> seen = new HashSet<>();
            for (Object key : blockingKeys(trade.isin(), trade.tradeDate(), trade.side(), trade.counterparty(), trade.quantity())) {
                for (NormalizedConfirm confirm : confirmsByBlock.getOrDefault(key, List.of())) {
                    if (!seen.add(confirm.ref())) {
                        continue;
                    }
                    List<FieldDiff> diffs = expected.diff(confirm, state.tolerances.forAssetClass(trade.assetClass()));
                    int score = 100 - diffs.stream().mapToInt(d -> d.field().penalty()).sum();
                    if (score > thresholdPoints) {
                        candidates.add(new Candidate(trade, confirm, score, diffs));
                    }
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

    private static List<Object> blockingKeys(String isin, LocalDate tradeDate, Side side, String counterparty, BigDecimal quantity) {
        return List.of(new SideCounterparty(isin, tradeDate, side, counterparty),
                new SideQuantity(isin, tradeDate, side, quantity),
                new CounterpartyQuantity(isin, tradeDate, counterparty, quantity));
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
