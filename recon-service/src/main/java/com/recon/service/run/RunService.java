package com.recon.service.run;

import com.recon.service.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class RunService {

    private final ReconRunRepository runs;
    private final ReconDataRepository data;
    private final RunWorker worker;
    private final Executor runExecutor;
    private final Clock clock;

    public RunService(ReconRunRepository runs, ReconDataRepository data, RunWorker worker,
                      @Qualifier("runExecutor") Executor runExecutor, Clock clock) {
        this.runs = runs;
        this.data = data;
        this.worker = worker;
        this.runExecutor = runExecutor;
        this.clock = clock;
    }

    /**
     * Records a RUNNING run and hands it to the executor. The insert commits before the work is
     * queued, so the caller can poll the run straight away. A second run for a date that is
     * already running hits the partial unique index and becomes a 409.
     */
    public ReconRun start(LocalDate businessDate) {
        if (!data.hasRecordsFor(businessDate)) {
            throw ApiException.unprocessable("No trades or confirms for business date " + businessDate
                    + ". Import a dataset first (POST /api/imports).");
        }
        ReconRun run;
        try {
            run = runs.saveAndFlush(ReconRun.started(businessDate, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A run for " + businessDate + " is already in progress; poll GET /api/runs?businessDate="
                    + businessDate + " and retry when it finishes");
        }
        long runId = run.getId();
        runExecutor.execute(() -> worker.process(runId));
        return run;
    }

    public ReconRun get(long id) {
        return runs.findById(id).orElseThrow(() -> ApiException.notFound("Run " + id + " does not exist"));
    }

    /** Runs for one date, newest first, or the 20 most recent runs. */
    public List<ReconRun> list(LocalDate businessDate) {
        return businessDate == null ? runs.findTop20ByOrderByIdDesc() : runs.findByBusinessDateOrderByIdDesc(businessDate);
    }
}
