package com.recon.service.breaks;

import com.recon.core.model.BreakType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The live queue at a glance: OPEN and ASSIGNED breaks only.
 *
 * @param byType         every break type, zero-filled, in enum order
 * @param byCounterparty most breaks first
 * @param byAge          fixed buckets, youngest first
 * @param latestRun      the most recent completed run, for the STP tile; null before any run
 */
public record BreakSummary(
        long open,
        long assigned,
        Instant oldestOpenedAt,
        Long oldestAgeHours,
        Map<BreakType, Long> byType,
        Map<String, Long> byCounterparty,
        List<AgeBucket> byAge,
        LatestRun latestRun) {

    public record AgeBucket(String bucket, long count) {
    }

    public record LatestRun(long id, LocalDate businessDate, Instant finishedAt, Double matchRate, Double stpRate) {
    }
}
