package com.recon.service.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/** One reconciliation of one business date, and the counts it produced. */
@Entity
@Table(name = "recon_run")
public class ReconRun {

    /** The counts a completed run records. */
    public record Counts(int internal, int external, int matchedInternal, int stpInternal, int exact, int block, int fuzzy,
                         int breaksFound, int breaksNew, int breaksCarried, int breaksAutoResolved) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "internal_count")
    private Integer internalCount;
    @Column(name = "external_count")
    private Integer externalCount;
    @Column(name = "matched_internal")
    private Integer matchedInternal;
    @Column(name = "stp_internal")
    private Integer stpInternal;
    @Column(name = "exact_matches")
    private Integer exactMatches;
    @Column(name = "block_matches")
    private Integer blockMatches;
    @Column(name = "fuzzy_matches")
    private Integer fuzzyMatches;
    @Column(name = "breaks_found")
    private Integer breaksFound;
    @Column(name = "breaks_new")
    private Integer breaksNew;
    @Column(name = "breaks_carried")
    private Integer breaksCarried;
    @Column(name = "breaks_auto_resolved")
    private Integer breaksAutoResolved;

    private String error;

    protected ReconRun() {
        // for JPA
    }

    public static ReconRun started(LocalDate businessDate, Instant now) {
        ReconRun run = new ReconRun();
        run.businessDate = businessDate;
        run.status = RunStatus.RUNNING;
        run.startedAt = now;
        return run;
    }

    void complete(Counts counts, Instant now) {
        status = RunStatus.COMPLETED;
        internalCount = counts.internal();
        externalCount = counts.external();
        matchedInternal = counts.matchedInternal();
        stpInternal = counts.stpInternal();
        exactMatches = counts.exact();
        blockMatches = counts.block();
        fuzzyMatches = counts.fuzzy();
        breaksFound = counts.breaksFound();
        breaksNew = counts.breaksNew();
        breaksCarried = counts.breaksCarried();
        breaksAutoResolved = counts.breaksAutoResolved();
        finish(now);
    }

    void fail(String message, Instant now) {
        status = RunStatus.FAILED;
        error = message;
        finish(now);
    }

    private void finish(Instant now) {
        finishedAt = now;
        durationMs = Duration.between(startedAt, now).toMillis();
    }

    public Long getId() { return id; }
    public LocalDate getBusinessDate() { return businessDate; }
    public RunStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public String getError() { return error; }

    /** Null until the run completes. */
    public Counts getCounts() {
        if (status != RunStatus.COMPLETED) {
            return null;
        }
        return new Counts(internalCount, externalCount, matchedInternal, stpInternal, exactMatches, blockMatches, fuzzyMatches,
                breaksFound, breaksNew, breaksCarried, breaksAutoResolved);
    }
}
