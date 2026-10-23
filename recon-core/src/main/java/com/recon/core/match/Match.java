package com.recon.core.match;

import java.math.BigDecimal;
import java.util.List;

/**
 * One confirm matched to one or more internal trades (more than one for a block).
 *
 * @param score 1.00 for exact and block matches; the fuzzy score otherwise
 */
public record Match(MatchRule rule, BigDecimal score, List<String> internalRefs, String externalRef) {

    public Match {
        internalRefs = List.copyOf(internalRefs);
    }

    static Match of(MatchRule rule, int scorePoints, List<String> internalRefs, String externalRef) {
        return new Match(rule, BigDecimal.valueOf(scorePoints, 2), internalRefs, externalRef);
    }

    /** One row per internal trade, as stored in {@code match_link}. */
    public List<MatchLink> links() {
        return internalRefs.stream().map(ref -> new MatchLink(ref, externalRef, rule, score)).toList();
    }
}
