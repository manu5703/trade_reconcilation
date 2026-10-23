package com.recon.service.metrics;

import com.recon.core.model.BreakType;
import com.recon.service.run.ReconRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Business metrics, exposed at /actuator/prometheus next to the standard JVM, HTTP and pool
 * metrics:
 * <ul>
 *   <li>{@code recon_runs_total{status}} and {@code recon_run_duration_seconds}</li>
 *   <li>{@code recon_matches_total{rule}}: which rule matched how much, the trend to watch if a
 *       data change starts pushing pairs out of the exact pass</li>
 *   <li>{@code recon_breaks_found_total{type}}, {@code recon_stp_ratio} for the latest run</li>
 *   <li>{@code recon_breaks_open}: the queue size, read from the database at scrape time (one
 *       indexed count, so cheap)</li>
 * </ul>
 */
@Component
public final class ReconMetrics {

    private final MeterRegistry registry;
    private final Timer runDuration;
    private volatile double latestStpRatio = Double.NaN;

    public ReconMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.runDuration = Timer.builder("recon.run.duration").description("Wall time of completed runs").register(registry);
        Gauge.builder("recon.breaks.open", jdbc,
                        j -> j.queryForObject("select count(*) from recon_break where status <> 'RESOLVED'", Long.class))
                .description("Breaks currently OPEN or ASSIGNED")
                .register(registry);
        Gauge.builder("recon.stp.ratio", this, m -> m.latestStpRatio)
                .description("Share of internal trades matched with no break, latest completed run")
                .register(registry);
    }

    public void runCompleted(ReconRun run, Map<BreakType, Long> breaksByType) {
        Counter.builder("recon.runs").tag("status", "COMPLETED").register(registry).increment();
        runDuration.record(Duration.ofMillis(run.getDurationMs()));
        ReconRun.Counts counts = run.getCounts();
        Counter.builder("recon.matches").tag("rule", "EXACT").register(registry).increment(counts.exact());
        Counter.builder("recon.matches").tag("rule", "BLOCK").register(registry).increment(counts.block());
        Counter.builder("recon.matches").tag("rule", "FUZZY").register(registry).increment(counts.fuzzy());
        breaksByType.forEach((type, n) -> Counter.builder("recon.breaks.found").tag("type", type.name()).register(registry).increment(n));
        latestStpRatio = counts.internal() == 0 ? Double.NaN : (double) counts.stpInternal() / counts.internal();
    }

    public void runFailed() {
        Counter.builder("recon.runs").tag("status", "FAILED").register(registry).increment();
    }
}
