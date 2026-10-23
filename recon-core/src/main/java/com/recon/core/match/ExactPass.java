package com.recon.core.match;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass 3: hash join on (isin, side, tradeDate, counterparty, quantity). A pair is accepted
 * only if every other field also agrees: price and net amount within tolerance, and also settle date
 * and commission. Otherwise a pair with a settle-date or commission break would be quietly
 * matched here and the break lost.
 *
 * <p>When several trades share a key, the closest pairs (smallest price gap, then net gap) are
 * taken first. Taking the first ref instead lets one trade grab a near-identical neighbour's
 * confirm, which shows up as a false MISSING_CONFIRM at scale (docs/decisions/0005).
 *
 * <p>O(n): building the indexes and walking them is linear, and a key bucket holds a handful
 * of records.
 */
final class ExactPass implements MatchPass {

    private record Pair(NormalizedTrade trade, NormalizedConfirm confirm, BigDecimal priceGap, BigDecimal netGap) {
    }

    private static final Comparator<Pair> CLOSEST_FIRST = Comparator
            .comparing(Pair::priceGap)
            .thenComparing(Pair::netGap)
            .thenComparing(p -> p.trade().ref())
            .thenComparing(p -> p.confirm().ref());

    @Override
    public void apply(MatchState state) {
        Map<ExactKey, List<NormalizedConfirm>> confirmsByKey = new HashMap<>();
        for (NormalizedConfirm confirm : state.unmatchedConfirms()) {
            confirmsByKey.computeIfAbsent(ExactKey.of(confirm), k -> new ArrayList<>()).add(confirm);
        }
        Map<ExactKey, List<NormalizedTrade>> tradesByKey = new HashMap<>();
        for (NormalizedTrade trade : state.unmatchedTrades()) {
            tradesByKey.computeIfAbsent(Expectation.of(trade).exactKey(), k -> new ArrayList<>()).add(trade);
        }

        // Buckets share no records, so each is resolved on its own and the order buckets are
        // visited in doesn't affect the result.
        for (Map.Entry<ExactKey, List<NormalizedTrade>> bucket : tradesByKey.entrySet()) {
            List<NormalizedConfirm> confirms = confirmsByKey.getOrDefault(bucket.getKey(), List.of());
            List<Pair> pairs = new ArrayList<>();
            for (NormalizedTrade trade : bucket.getValue()) {
                Expectation expected = Expectation.of(trade);
                for (NormalizedConfirm confirm : confirms) {
                    if (expected.diff(confirm, state.tolerances.forAssetClass(trade.assetClass())).isEmpty()) {
                        pairs.add(new Pair(trade, confirm, trade.price().subtract(confirm.price()).abs(),
                                trade.netAmount().subtract(confirm.netAmount()).abs()));
                    }
                }
            }
            pairs.sort(CLOSEST_FIRST);
            Set<String> takenTrades = new HashSet<>();
            Set<String> takenConfirms = new HashSet<>();
            for (Pair p : pairs) {
                if (!takenTrades.contains(p.trade().ref()) && !takenConfirms.contains(p.confirm().ref())) {
                    takenTrades.add(p.trade().ref());
                    takenConfirms.add(p.confirm().ref());
                    state.addMatch(Match.of(MatchRule.EXACT, 100, List.of(p.trade().ref()), p.confirm().ref()));
                }
            }
        }
    }
}
