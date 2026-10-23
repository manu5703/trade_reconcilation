package com.recon.core.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass 4: finds resent confirms. An unmatched confirm whose economics repeat a confirm already
 * matched by the block or exact pass becomes DUPLICATE_CONFIRM against that trade.
 *
 * <p>This runs before the fuzzy pass on purpose. Otherwise the fuzzy pass could pair the resend
 * with some other unmatched trade that happens to be close, reporting a false field break and
 * losing the duplicate (docs/decisions/0005).
 */
final class DuplicatePass implements MatchPass {

    @Override
    public void apply(MatchState state) {
        Map<ExactKey, List<Match>> matchedByKey = new HashMap<>();
        for (Match match : state.matches) {
            matchedByKey.computeIfAbsent(ExactKey.of(state.confirmsByRef.get(match.externalRef())), k -> new ArrayList<>()).add(match);
        }
        for (NormalizedConfirm confirm : state.unmatchedConfirms()) {
            Match original = duplicatedMatch(state, matchedByKey.getOrDefault(ExactKey.of(confirm), List.of()), confirm);
            if (original != null) {
                state.retire(confirm, Break.duplicateConfirm(original.internalRefs().getFirst(), original.externalRef(), confirm.ref()));
            }
        }
    }

    /** The match whose confirm this one repeats, or null. */
    private static Match duplicatedMatch(MatchState state, List<Match> sameKey, NormalizedConfirm confirm) {
        for (Match match : sameKey) {
            NormalizedTrade trade = state.tradesByRef.get(match.internalRefs().getFirst());
            Expectation original = Expectation.of(state.confirmsByRef.get(match.externalRef()), trade.assetClass());
            if (original.diff(confirm, state.tolerances.forAssetClass(trade.assetClass())).isEmpty()) {
                return match;
            }
        }
        return null;
    }
}
