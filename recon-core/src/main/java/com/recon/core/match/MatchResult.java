package com.recon.core.match;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Output of one run: matches and breaks, both in canonical order. */
public record MatchResult(List<Match> matches, List<Break> breaks) {

    public MatchResult {
        matches = List.copyOf(matches);
        breaks = List.copyOf(breaks);
    }

    public List<MatchLink> links() {
        return matches.stream().flatMap(m -> m.links().stream()).toList();
    }

    /** Refs of every trade and confirm that ended up in a match. */
    public Set<String> matchedRefs() {
        Set<String> refs = new HashSet<>();
        for (Match m : matches) {
            refs.addAll(m.internalRefs());
            refs.add(m.externalRef());
        }
        return refs;
    }

    public Map<MatchRule, Integer> matchesByRule() {
        Map<MatchRule, Integer> counts = new EnumMap<>(MatchRule.class);
        matches.forEach(m -> counts.merge(m.rule(), 1, Integer::sum));
        return counts;
    }
}
