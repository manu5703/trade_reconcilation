package com.recon.service.run;

import com.recon.core.match.Break;
import com.recon.core.match.MatchLink;
import com.recon.core.model.AssetClass;
import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.model.Side;
import com.recon.core.model.Source;
import com.recon.core.refdata.CounterpartyRef;
import com.recon.core.refdata.StaticData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk reads and writes for a run, in plain JDBC. A run touches every trade and confirm of a
 * business date and writes a link per trade, so batched SQL beats loading JPA entities one by one.
 * JPA is used where it helps: the break queue, where people edit individual rows.
 */
@Repository
public class ReconDataRepository {

    /** One business date's input, with database ids by ref for writing results back. */
    public record Records(List<InternalTrade> trades, Map<String, Long> tradeIds,
                          List<ExternalConfirm> confirms, Map<String, Long> confirmIds) {
    }

    /** A break still open from an earlier run, keyed the same way the core keys breaks. */
    public record OpenBreakRow(long id, Break.Key key, Instant openedAt) {
    }

    /** A break to insert, with its foreign keys resolved. */
    public record NewBreak(Break brk, Long internalId, Long externalId, String counterparty, Instant openedAt) {
    }

    /** A carried break to refresh with this run's values. */
    public record CarriedBreak(long id, Break brk, String counterparty) {
    }

    private final JdbcTemplate jdbc;

    public ReconDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StaticData staticData() {
        Map<String, AssetClass> instruments = new HashMap<>();
        jdbc.query("select isin, asset_class from instrument",
                rs -> { instruments.put(rs.getString("isin"), AssetClass.valueOf(rs.getString("asset_class"))); });

        Map<String, List<String>> aliases = new HashMap<>();
        jdbc.query("select counterparty_id, alias from counterparty_alias",
                rs -> { aliases.computeIfAbsent(rs.getString("counterparty_id"), k -> new ArrayList<>()).add(rs.getString("alias")); });
        List<CounterpartyRef> counterparties = jdbc.query("select counterparty_id, commission_bps from counterparty",
                (rs, n) -> new CounterpartyRef(rs.getString("counterparty_id"),
                        aliases.getOrDefault(rs.getString("counterparty_id"), List.of()), rs.getBigDecimal("commission_bps")));
        return new StaticData(instruments, counterparties);
    }

    public boolean hasRecordsFor(LocalDate businessDate) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from internal_trade where trade_date = ?)
                    or exists (select 1 from external_confirm where trade_date = ?)""",
                Boolean.class, businessDate, businessDate);
        return Boolean.TRUE.equals(exists);
    }

    public Records recordsFor(LocalDate businessDate) {
        Map<String, Long> tradeIds = new HashMap<>();
        List<InternalTrade> trades = jdbc.query("select * from internal_trade where trade_date = ?", (rs, n) -> {
            tradeIds.put(rs.getString("trade_ref"), rs.getLong("id"));
            return new InternalTrade(rs.getString("trade_ref"), rs.getString("block_ref"), rs.getString("account"),
                    rs.getString("isin"), Side.valueOf(rs.getString("side")), rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("price"), date(rs, "trade_date"), date(rs, "settle_date"), rs.getString("counterparty"),
                    rs.getBigDecimal("net_amount"));
        }, businessDate);

        Map<String, Long> confirmIds = new HashMap<>();
        List<ExternalConfirm> confirms = jdbc.query("select * from external_confirm where trade_date = ?", (rs, n) -> {
            confirmIds.put(rs.getString("broker_ref"), rs.getLong("id"));
            return new ExternalConfirm(rs.getString("broker_ref"), Source.valueOf(rs.getString("source")), rs.getString("isin"),
                    Side.valueOf(rs.getString("side")), rs.getBigDecimal("quantity"), rs.getBigDecimal("price"),
                    rs.getBigDecimal("commission"), date(rs, "trade_date"), date(rs, "settle_date"), rs.getString("counterparty"),
                    rs.getBigDecimal("net_amount"));
        }, businessDate);
        return new Records(trades, tradeIds, confirms, confirmIds);
    }

    /** Breaks for this business date that are OPEN or ASSIGNED, keyed by refs as in the core. */
    public List<OpenBreakRow> openBreaksFor(LocalDate businessDate) {
        return jdbc.query("""
                select b.id, b.type, t.trade_ref, c.broker_ref, b.field, b.opened_at
                from recon_break b
                left join internal_trade t on t.id = b.internal_id
                left join external_confirm c on c.id = b.external_id
                where b.business_date = ? and b.status <> 'RESOLVED'""",
                (rs, n) -> new OpenBreakRow(rs.getLong("id"),
                        new Break.Key(BreakType.valueOf(rs.getString("type")), rs.getString("trade_ref"), rs.getString("broker_ref"), rs.getString("field")),
                        rs.getObject("opened_at", OffsetDateTime.class).toInstant()),
                businessDate);
    }

    public void insertLinks(long runId, List<MatchLink> links, Map<String, Long> tradeIds, Map<String, Long> confirmIds) {
        List<Object[]> rows = new ArrayList<>(links.size());
        for (MatchLink link : links) {
            rows.add(new Object[] {runId, tradeIds.get(link.internalRef()), confirmIds.get(link.externalRef()), link.rule().name(), link.score()});
        }
        jdbc.batchUpdate("insert into match_link (run_id, internal_id, external_id, rule, score) values (?, ?, ?, ?, ?)", rows);
    }

    public void insertBreaks(long runId, LocalDate businessDate, List<NewBreak> breaks) {
        List<Object[]> rows = new ArrayList<>(breaks.size());
        for (NewBreak b : breaks) {
            rows.add(new Object[] {runId, businessDate, b.brk().type().name(), b.internalId(), b.externalId(), b.brk().field(),
                b.brk().expected(), b.brk().actual(), b.counterparty(), utc(b.openedAt())});
        }
        jdbc.batchUpdate("""
                insert into recon_break (run_id, business_date, type, internal_id, external_id, field, expected, actual,
                                         counterparty, status, opened_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)""", rows);
    }

    /** Points carried breaks at this run and refreshes their values; status, owner and notes are kept. */
    public void updateCarried(long runId, List<CarriedBreak> breaks) {
        List<Object[]> rows = new ArrayList<>(breaks.size());
        for (CarriedBreak b : breaks) {
            rows.add(new Object[] {runId, b.brk().expected(), b.brk().actual(), b.counterparty(), b.id()});
        }
        // version + 1 so a concurrent PATCH holding the old version gets a 409, not a silent overwrite.
        jdbc.batchUpdate("""
                update recon_break set run_id = ?, expected = ?, actual = ?, counterparty = ?, version = version + 1
                where id = ?""", rows);
    }

    public void resolveAutomatically(List<Long> ids, Instant now, String reason) {
        List<Object[]> rows = ids.stream().map(id -> new Object[] {utc(now), reason, id}).toList();
        jdbc.batchUpdate("""
                update recon_break set status = 'RESOLVED', resolved_at = ?, resolution_reason = ?, version = version + 1
                where id = ?""", rows);
    }

    /** Ids of the breaks a key set refers to, in the order given. */
    public static Map<Break.Key, Long> idsByKey(List<OpenBreakRow> rows) {
        Map<Break.Key, Long> ids = new LinkedHashMap<>();
        rows.forEach(r -> ids.put(r.key(), r.id()));
        return ids;
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
