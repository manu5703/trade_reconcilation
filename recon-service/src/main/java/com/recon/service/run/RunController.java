package com.recon.service.run;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
@Tag(name = "Runs", description = "Reconcile a business date")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    @Operation(summary = "Start a reconciliation run",
            description = "Returns 202 immediately; poll the Location header until status is COMPLETED or FAILED. "
                    + "Re-running a date is idempotent: the same matches and breaks, with open breaks keeping their age.")
    public ResponseEntity<RunView> start(
            @Parameter(description = "Business date to reconcile, yyyy-MM-dd", example = "2026-01-15")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        ReconRun run = runService.start(businessDate);
        return ResponseEntity.accepted().location(URI.create("/api/runs/" + run.getId())).body(RunView.of(run));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Status, counts, match rate and duration of a run")
    public RunView get(@PathVariable long id) {
        return RunView.of(runService.get(id));
    }

    @GetMapping
    @Operation(summary = "Runs for a business date (newest first), or the 20 most recent")
    public List<RunView> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return runService.list(businessDate).stream().map(RunView::of).toList();
    }
}
