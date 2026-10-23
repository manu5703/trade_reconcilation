package com.recon.core.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pass 2: a broker confirms a block order once, for the total. Unmatched trades are grouped by
 * blockRef; a group matches a confirm whose quantity equals the summed quantity and whose price
 * is within tolerance of the quantity-weighted average price (net and commission are summed too).
 * The match links every allocation to the one confirm.
 *
 * <p>Runs before the exact pass. Otherwise, at scale, a single allocation sometimes exact-matches
 * an unrelated confirm with the same key and a price within tolerance, and the rest of the block
 * then can't reconcile (docs/decisions/0005).
 *
 * <p>Only whole blocks match. If a block doesn't reconcile, its allocations fall through to the
 * fuzzy pass and leftovers individually.
 */
final class BlockPass implements MatchPass {

    @Override
    public void apply(MatchState state) {
        // TreeMap: visit blocks in blockRef order for a deterministic result.
        Map<String, List<NormalizedTrade>> blocks = new TreeMap<>();
        for (NormalizedTrade trade : state.unmatchedTrades()) {
            if (trade.blockRef() != null) {
                blocks.computeIfAbsent(trade.blockRef(), k -> new ArrayList<>()).add(trade);
            }
        }
        Map<ExactKey, List<NormalizedConfirm>> index = new HashMap<>();
        for (NormalizedConfirm confirm : state.unmatchedConfirms()) {
            index.computeIfAbsent(ExactKey.of(confirm), k -> new ArrayList<>()).add(confirm);
        }

        for (List<NormalizedTrade> allocations : blocks.values()) {
            Optional<Expectation> block = Expectation.ofBlock(allocations);
            if (block.isEmpty()) {
                continue;
            }
            List<NormalizedConfirm> candidates = index.get(block.get().exactKey());
            if (candidates == null) {
                continue;
            }
            for (Iterator<NormalizedConfirm> it = candidates.iterator(); it.hasNext(); ) {
                NormalizedConfirm confirm = it.next();
                if (block.get().diff(confirm, state.tolerances.forAssetClass(block.get().assetClass())).isEmpty()) {
                    it.remove();
                    List<String> refs = allocations.stream().map(NormalizedTrade::ref).toList();
                    state.addMatch(Match.of(MatchRule.BLOCK, 100, refs, confirm.ref()));
                    break;
                }
            }
        }
    }
}
