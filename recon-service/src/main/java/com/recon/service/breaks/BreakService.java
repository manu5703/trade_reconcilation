package com.recon.service.breaks;

import com.recon.core.model.BreakType;
import com.recon.core.refdata.StaticData;
import com.recon.service.run.ReconRun;
import com.recon.service.run.ReconRunRepository;
import com.recon.service.run.RunStatus;
import com.recon.service.run.RunView;
import com.recon.service.web.ApiException;
import com.recon.service.web.ApiException.FieldError;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BreakService {

    /** Fields the queue can be sorted by; a leading '-' means descending. */
    static final Set<String> SORTABLE = Set.of("openedAt", "type", "counterparty", "status", "businessDate");

    /** Filters for the break queue; any of them may be null. */
    public record Query(BreakStatus status, BreakType type, String counterparty, LocalDate businessDate,
                        Integer minAgeHours, int page, int size, String sort) {
    }

    private final ReconBreakRepository breaks;
    private final ReconRunRepository runs;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BreakService(ReconBreakRepository breaks, ReconRunRepository runs, JdbcTemplate jdbc, Clock clock) {
        this.breaks = breaks;
        this.runs = runs;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageView<BreakView> list(Query q) {
        Instant now = clock.instant();
        Specification<ReconBreak> spec = (root, cq, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (q.status() != null) {
                where.add(cb.equal(root.get("status"), q.status()));
            }
            if (q.type() != null) {
                where.add(cb.equal(root.get("type"), q.type()));
            }
            if (q.counterparty() != null && !q.counterparty().isBlank()) {
                // Same normalization as the matcher, so "cp001 " finds CP001.
                where.add(cb.equal(root.get("counterparty"), StaticData.normalizeCode(q.counterparty())));
            }
            if (q.businessDate() != null) {
                where.add(cb.equal(root.get("businessDate"), q.businessDate()));
            }
            if (q.minAgeHours() != null) {
                where.add(cb.lessThanOrEqualTo(root.get("openedAt"), now.minus(Duration.ofHours(q.minAgeHours()))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest page = PageRequest.of(q.page(), q.size(), sort(q.sort()));
        return PageView.of(breaks.findAll(spec, page).map(b -> BreakView.of(b, now)));
    }

    @Transactional(readOnly = true)
    public BreakView get(long id) {
        return BreakView.of(find(id), clock.instant());
    }

    /**
     * Applies a {@link BreakPatch}. The entity's {@code @Version} makes the update conditional,
     * so if a run refreshed this break meanwhile, the commit fails with a 409 rather than
     * overwriting it.
     */
    @Transactional
    public BreakView patch(long id, BreakPatch patch) {
        validate(patch);
        ReconBreak brk = find(id);
        if (patch.expectedVersion() != null && patch.expectedVersion() != brk.getVersion()) {
            throw ApiException.conflict("Break " + id + " is at version " + brk.getVersion() + ", not " + patch.expectedVersion()
                    + "; it changed since you loaded it, so reload and try again");
        }
        Instant now = clock.instant();
        if (patch.owner() != null) {
            brk.assignTo(patch.owner().trim());
        }
        if (patch.note() != null) {
            brk.addNote(patch.note().trim(), now);
        }
        if (patch.status() == BreakStatus.RESOLVED) {
            brk.resolve(patch.reason().trim(), now);
        }
        breaks.flush();
        return BreakView.of(brk, now);
    }

    @Transactional(readOnly = true)
    public BreakSummary summary() {
        Instant now = clock.instant();
        Map<BreakType, Long> byType = new EnumMap<>(BreakType.class);
        for (BreakType type : BreakType.values()) {
            byType.put(type, 0L);
        }
        jdbc.query("select type, count(*) n from recon_break where status <> 'RESOLVED' group by type",
                rs -> { byType.put(BreakType.valueOf(rs.getString("type")), rs.getLong("n")); });

        Map<String, Long> byCounterparty = new LinkedHashMap<>();
        jdbc.query("""
                select coalesce(counterparty, '(unknown)') counterparty, count(*) n from recon_break
                where status <> 'RESOLVED' group by 1 order by n desc, 1""",
                rs -> { byCounterparty.put(rs.getString("counterparty"), rs.getLong("n")); });

        long[] ages = new long[4];
        jdbc.query("""
                select case when opened_at > ? - interval '24 hours' then 0
                            when opened_at > ? - interval '72 hours' then 1
                            when opened_at > ? - interval '168 hours' then 2
                            else 3 end bucket, count(*) n
                from recon_break where status <> 'RESOLVED' group by 1""",
                rs -> { ages[rs.getInt("bucket")] = rs.getLong("n"); }, utc(now), utc(now), utc(now));
        List<BreakSummary.AgeBucket> byAge = List.of(
                new BreakSummary.AgeBucket("<24h", ages[0]), new BreakSummary.AgeBucket("1-3d", ages[1]),
                new BreakSummary.AgeBucket("3-7d", ages[2]), new BreakSummary.AgeBucket(">7d", ages[3]));

        Map<String, Object> totals = jdbc.queryForMap("""
                select count(*) filter (where status = 'OPEN') open, count(*) filter (where status = 'ASSIGNED') assigned
                from recon_break where status <> 'RESOLVED'""");
        OffsetDateTime oldest = jdbc.queryForObject(
                "select min(opened_at) from recon_break where status <> 'RESOLVED'", OffsetDateTime.class);
        Instant oldestAt = oldest == null ? null : oldest.toInstant();

        BreakSummary.LatestRun latest = runs.findFirstByStatusOrderByIdDesc(RunStatus.COMPLETED).map(this::latestRun).orElse(null);
        return new BreakSummary(((Number) totals.get("open")).longValue(), ((Number) totals.get("assigned")).longValue(),
                oldestAt, oldestAt == null ? null : Duration.between(oldestAt, now).toHours(),
                byType, byCounterparty, byAge, latest);
    }

    private BreakSummary.LatestRun latestRun(ReconRun run) {
        RunView view = RunView.of(run);
        return new BreakSummary.LatestRun(view.id(), view.businessDate(), view.finishedAt(), view.matchRate(), view.stpRate());
    }

    private ReconBreak find(long id) {
        return breaks.findWithRecordsById(id).orElseThrow(() -> ApiException.notFound("Break " + id + " does not exist"));
    }

    /** Rules that span fields, reported together so the caller can fix everything in one go. */
    private static void validate(BreakPatch patch) {
        List<FieldError> errors = new ArrayList<>();
        if (patch.owner() == null && patch.note() == null && patch.status() == null) {
            errors.add(new FieldError("body", "Provide at least one of owner, note or status"));
        }
        if (patch.owner() != null && patch.owner().isBlank()) {
            errors.add(new FieldError("owner", "owner must not be blank"));
        }
        if (patch.note() != null && patch.note().isBlank()) {
            errors.add(new FieldError("note", "note must not be blank"));
        }
        if (patch.status() != null && patch.status() != BreakStatus.RESOLVED) {
            errors.add(new FieldError("status", "status can only be set to RESOLVED; to assign a break, set owner"));
        }
        if (patch.status() == BreakStatus.RESOLVED && (patch.reason() == null || patch.reason().isBlank())) {
            errors.add(new FieldError("reason", "reason is required to resolve a break"));
        }
        if (patch.reason() != null && patch.status() != BreakStatus.RESOLVED) {
            errors.add(new FieldError("reason", "reason is only used when resolving; also send \"status\": \"RESOLVED\""));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    String.join("; ", errors.stream().map(FieldError::message).toList()), errors);
        }
    }

    private static Sort sort(String sort) {
        String spec = sort == null || sort.isBlank() ? "openedAt" : sort.trim();
        boolean descending = spec.startsWith("-");
        String field = descending ? spec.substring(1) : spec;
        if (!SORTABLE.contains(field)) {
            throw ApiException.badRequest("sort", "sort must be one of " + String.join(", ", SORTABLE.stream().sorted().toList())
                    + ", optionally prefixed with '-' for descending; got '" + sort + "'");
        }
        // id as a tie-breaker gives a stable order, so paging never skips or repeats a row. It
        // runs in the same direction as the main field so one (field, id) index serves both
        // directions (V2 migration).
        Sort.Direction direction = descending ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, field).and(Sort.by(direction, "id"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
