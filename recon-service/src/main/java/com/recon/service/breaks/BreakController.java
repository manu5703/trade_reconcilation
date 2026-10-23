package com.recon.service.breaks;

import com.recon.core.model.BreakType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/breaks")
@Tag(name = "Breaks", description = "The break queue")
public class BreakController {

    private final BreakService breakService;

    public BreakController(BreakService breakService) {
        this.breakService = breakService;
    }

    @GetMapping
    @Operation(summary = "List breaks, oldest first by default")
    public PageView<BreakView> list(
            @RequestParam(required = false) BreakStatus status,
            @RequestParam(required = false) BreakType type,
            @Parameter(description = "Canonical counterparty ID, e.g. CP007") @RequestParam(required = false) String counterparty,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @Parameter(description = "Only breaks open at least this many hours") @RequestParam(required = false) @Min(0) Integer minAgeHours,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "openedAt, type, counterparty, status or businessDate; prefix '-' for descending")
            @RequestParam(required = false) String sort) {
        return breakService.list(new BreakService.Query(status, type, counterparty, businessDate, minAgeHours, page, size, sort));
    }

    @GetMapping("/summary")
    @Operation(summary = "Open breaks by type, counterparty and age bucket, plus the latest run's STP rate")
    public BreakSummary summary() {
        return breakService.summary();
    }

    @GetMapping("/{id}")
    @Operation(summary = "One break with its trade and confirm")
    public BreakView get(@PathVariable long id) {
        return breakService.get(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Assign, add a note, or resolve (resolving needs a reason)")
    public BreakView patch(@PathVariable long id, @Valid @RequestBody BreakPatch patch) {
        return breakService.patch(id, patch);
    }
}
