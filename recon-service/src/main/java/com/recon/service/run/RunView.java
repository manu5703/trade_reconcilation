package com.recon.service.run;

import java.time.Instant;
import java.time.LocalDate;

/**
 * API representation of a run. {@code counts}, {@code matchRate} and {@code stpRate} appear once
 * the run has completed; {@code error} only when it failed.
 *
 * @param matchRate share of internal trades matched to a confirm (some may still carry a break)
 * @param stpRate   share of internal trades matched with no break at all (straight-through)
 */
public record RunView(
        long id,
        LocalDate businessDate,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Counts counts,
        Double matchRate,
        Double stpRate,
        String error) {

    public record Counts(int internalTrades, int externalConfirms, int matchedTrades, int straightThroughTrades,
                         Matches matches, Breaks breaks) {
    }

    public record Matches(int exact, int block, int fuzzy) {
    }

    /** {@code found} = this run's breaks = {@code newlyOpened} + {@code carriedOver}. */
    public record Breaks(int found, int newlyOpened, int carriedOver, int autoResolved) {
    }

    public static RunView of(ReconRun run) {
        ReconRun.Counts c = run.getCounts();
        Counts counts = c == null ? null : new Counts(c.internal(), c.external(), c.matchedInternal(), c.stpInternal(),
                new Matches(c.exact(), c.block(), c.fuzzy()),
                new Breaks(c.breaksFound(), c.breaksNew(), c.breaksCarried(), c.breaksAutoResolved()));
        return new RunView(run.getId(), run.getBusinessDate(), run.getStatus(), run.getStartedAt(), run.getFinishedAt(),
                run.getDurationMs(), counts,
                c == null ? null : ratio(c.matchedInternal(), c.internal()),
                c == null ? null : ratio(c.stpInternal(), c.internal()),
                run.getError());
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 0 : Math.round(10_000.0 * part / whole) / 10_000.0;
    }
}
