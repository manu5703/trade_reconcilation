package com.recon.core.match;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass 7: connects this run's breaks to the ones still open from earlier runs.
 * <ul>
 *   <li>A break with the same {@link Break.Key} as an open one keeps its original
 *       {@code openedAt}, so a break's age survives re-runs.</li>
 *   <li>An open break that didn't recur, although its records were in this run, is
 *       auto-resolved: the records now match, or a different break now describes them (a
 *       MISSING_CONFIRM that became a QUANTITY_MISMATCH once the confirm arrived).</li>
 *   <li>An open break whose records weren't in this run (another business date) is left alone.</li>
 * </ul>
 * The run time is passed in rather than read from a clock, so the function stays pure.
 */
public final class CarryOver {

    private CarryOver() {
    }

    public record OpenBreak(Break.Key key, Instant openedAt) {
    }

    /** A break from this run, with {@code carried} true if it was already open before. */
    public record DatedBreak(Break brk, Instant openedAt, boolean carried) {
    }

    public record Result(List<DatedBreak> breaks, List<OpenBreak> autoResolved) {
        public Result {
            breaks = List.copyOf(breaks);
            autoResolved = List.copyOf(autoResolved);
        }
    }

    public static Result apply(MatchResult current, Collection<OpenBreak> previouslyOpen, Instant runAt) {
        Map<Break.Key, OpenBreak> open = new HashMap<>();
        previouslyOpen.forEach(b -> open.put(b.key(), b));

        List<DatedBreak> dated = new ArrayList<>(current.breaks().size());
        for (Break brk : current.breaks()) {
            OpenBreak earlier = open.remove(brk.key());
            dated.add(earlier == null ? new DatedBreak(brk, runAt, false) : new DatedBreak(brk, earlier.openedAt(), true));
        }

        // Whatever is left in `open` didn't recur in this run. Every input record ends up in a
        // match or a break, so together those are exactly the records this run saw.
        Set<String> seen = new HashSet<>(current.matchedRefs());
        for (Break brk : current.breaks()) {
            addIfPresent(seen, brk.internalRef());
            addIfPresent(seen, brk.externalRef());
        }
        List<OpenBreak> resolved = previouslyOpen.stream()
                .filter(b -> open.containsKey(b.key()))
                .filter(b -> wasInRun(b.key(), seen))
                .sorted((a, b) -> Break.ORDER.compare(asBreak(a.key()), asBreak(b.key())))
                .toList();
        return new Result(dated, resolved);
    }

    private static boolean wasInRun(Break.Key key, Set<String> seen) {
        return (key.internalRef() != null || key.externalRef() != null)
                && (key.internalRef() == null || seen.contains(key.internalRef()))
                && (key.externalRef() == null || seen.contains(key.externalRef()));
    }

    private static void addIfPresent(Set<String> refs, String ref) {
        if (ref != null) {
            refs.add(ref);
        }
    }

    private static Break asBreak(Break.Key key) {
        return new Break(key.type(), key.internalRef(), key.externalRef(), key.field(), null, null);
    }
}
