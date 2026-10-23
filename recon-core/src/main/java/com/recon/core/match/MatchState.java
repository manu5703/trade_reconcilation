package com.recon.core.match;

import com.recon.core.tolerance.ToleranceConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Working state shared by the passes of one run. Trades and confirms are held sorted by ref, and
 * every pass walks them in that order, so the result never depends on input order.
 */
final class MatchState {

    final List<NormalizedTrade> trades;
    final List<NormalizedConfirm> confirms;
    final ToleranceConfig tolerances;
    final Map<String, NormalizedTrade> tradesByRef = new HashMap<>();
    final Map<String, NormalizedConfirm> confirmsByRef = new HashMap<>();
    final List<Match> matches = new ArrayList<>();
    final List<Break> breaks = new ArrayList<>();
    private final Set<String> matchedTrades = new HashSet<>();
    private final Set<String> matchedConfirms = new HashSet<>();
    /** Confirms already explained by a break (duplicates), so later passes leave them alone. */
    private final Set<String> retiredConfirms = new HashSet<>();

    MatchState(List<NormalizedTrade> sortedTrades, List<NormalizedConfirm> sortedConfirms, ToleranceConfig tolerances) {
        this.trades = sortedTrades;
        this.confirms = sortedConfirms;
        this.tolerances = tolerances;
        sortedTrades.forEach(t -> tradesByRef.put(t.ref(), t));
        sortedConfirms.forEach(c -> confirmsByRef.put(c.ref(), c));
    }

    List<NormalizedTrade> unmatchedTrades() {
        return trades.stream().filter(t -> !matchedTrades.contains(t.ref())).toList();
    }

    List<NormalizedConfirm> unmatchedConfirms() {
        return confirms.stream().filter(c -> !matchedConfirms.contains(c.ref()) && !retiredConfirms.contains(c.ref())).toList();
    }

    /** Records a break that fully explains an unmatched confirm and takes it out of later passes. */
    void retire(NormalizedConfirm confirm, Break brk) {
        retiredConfirms.add(confirm.ref());
        breaks.add(brk);
    }

    /** Records a match. Guards the "no record in two matches" invariant at the point it could break. */
    void addMatch(Match match) {
        if (matchedConfirms.contains(match.externalRef()) || match.internalRefs().stream().anyMatch(matchedTrades::contains)) {
            throw new IllegalStateException("Record already matched: " + match);
        }
        matchedConfirms.add(match.externalRef());
        matchedTrades.addAll(match.internalRefs());
        matches.add(match);
    }
}
