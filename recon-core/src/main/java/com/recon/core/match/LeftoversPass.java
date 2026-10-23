package com.recon.core.match;

/**
 * Pass 6: everything still unmatched becomes a break. A trade becomes MISSING_CONFIRM and a
 * confirm becomes UNEXPECTED_CONFIRM. (Duplicates were already taken out by {@link DuplicatePass}.)
 */
final class LeftoversPass implements MatchPass {

    @Override
    public void apply(MatchState state) {
        for (NormalizedTrade trade : state.unmatchedTrades()) {
            state.breaks.add(Break.missingConfirm(trade.ref()));
        }
        for (NormalizedConfirm confirm : state.unmatchedConfirms()) {
            state.breaks.add(Break.unexpectedConfirm(confirm.ref()));
        }
    }
}
