package com.recon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import com.recon.datagen.output.DatasetReader;
import com.recon.datagen.output.Label;
import com.recon.service.run.RunView;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RunIT extends IntegrationTest {

    @Test
    void runReconcilesTheBusinessDateAndMatchesTheLabels() throws IOException {
        importDataset();
        List<Label> labels = DatasetReader.labels(DATASET);
        long labelledBreaks = labels.stream().filter(Label::isBreak).count();
        // A perfect matcher sends every trade of a clean case straight through.
        double idealStp = labels.stream().filter(l -> !l.isBreak()).mapToInt(l -> l.tradeRefs().size()).sum() / (double) TRADES;

        RunView run = runAndWait(BUSINESS_DATE);

        assertThat(run.counts().internalTrades()).isEqualTo(TRADES);
        assertThat(run.counts().breaks().found()).isEqualTo((int) labelledBreaks);
        assertThat(run.counts().breaks().newlyOpened()).isEqualTo((int) labelledBreaks);
        assertThat(run.stpRate()).isCloseTo(idealStp, offset(0.0001));
        assertThat(run.matchRate()).isGreaterThan(run.stpRate());
        assertThat(run.durationMs()).isNotNull();
        // Every match records the rule that made it.
        Map<String, Object> rules = jdbc.queryForMap("""
                select count(*) filter (where rule = 'EXACT') exact, count(*) filter (where rule = 'BLOCK') block,
                       count(*) filter (where rule = 'FUZZY') fuzzy from match_link where run_id = ?""", run.id());
        assertThat(((Number) rules.get("block")).intValue()).isPositive();
        assertThat(((Number) rules.get("fuzzy")).intValue()).isEqualTo(run.counts().matches().fuzzy());
    }

    @Test
    void rerunningADateIsIdempotent() {
        importDataset();
        RunView first = runAndWait(BUSINESS_DATE);
        List<Map<String, Object>> breaksBefore = openBreaks();

        RunView second = runAndWait(BUSINESS_DATE);

        assertThat(links(second.id())).isEqualTo(links(first.id()));
        assertThat(second.counts().breaks().found()).isEqualTo(first.counts().breaks().found());
        assertThat(second.counts().breaks().newlyOpened()).isZero();
        assertThat(second.counts().breaks().carriedOver()).isEqualTo(first.counts().breaks().found());
        assertThat(second.counts().breaks().autoResolved()).isZero();
        // Same breaks, same ids, same opened_at: re-running didn't reset anyone's ageing.
        assertThat(openBreaks()).usingRecursiveComparison().ignoringFields("run_id").isEqualTo(breaksBefore);
    }

    @Test
    void missingConfirmIsAutoResolvedWhenTheConfirmArrives() {
        importDataset();
        runAndWait(BUSINESS_DATE);
        Long tradeId = jdbc.queryForObject("select internal_id from recon_break where type = 'MISSING_CONFIRM' order by id limit 1", Long.class);
        Long breakId = jdbc.queryForObject("select id from recon_break where internal_id = ? and type = 'MISSING_CONFIRM'", Long.class, tradeId);
        OffsetDateTime openedAt = jdbc.queryForObject("select opened_at from recon_break where id = ?", OffsetDateTime.class, breakId);

        // The broker's late confirm: same economics as the booking, scheduled commission.
        jdbc.update("""
                insert into external_confirm (broker_ref, source, isin, side, quantity, price, commission, trade_date,
                                              settle_date, counterparty, net_amount)
                select 'LATE-1', 'BROKER', t.isin, t.side, t.quantity, t.price,
                       case when i.asset_class = 'EQUITY' then round(t.quantity * t.price * c.commission_bps / 10000, 2) else 0 end,
                       t.trade_date, t.settle_date, t.counterparty, t.net_amount
                from internal_trade t join instrument i on i.isin = t.isin join counterparty c on c.counterparty_id = t.counterparty
                where t.id = ?""", tradeId);

        RunView rerun = runAndWait(BUSINESS_DATE);

        assertThat(rerun.counts().breaks().autoResolved()).isEqualTo(1);
        Map<String, Object> resolved = jdbc.queryForMap("select status, resolution_reason, opened_at from recon_break where id = ?", breakId);
        assertThat(resolved.get("status")).isEqualTo("RESOLVED");
        assertThat((String) resolved.get("resolution_reason")).contains("run " + rerun.id());
        assertThat(((Timestamp) resolved.get("opened_at")).toInstant()).isEqualTo(openedAt.toInstant());
        assertThat(jdbc.queryForObject("select rule from match_link where run_id = ? and internal_id = ?", String.class, rerun.id(), tradeId))
                .isEqualTo("EXACT");
    }

    @Test
    void secondRunWhileOneIsInProgressIsRejected() {
        importDataset();
        jdbc.update("insert into recon_run (business_date, status, started_at) values (?, 'RUNNING', now())", BUSINESS_DATE);

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/runs?businessDate=" + BUSINESS_DATE, null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("detail").asText()).contains("already in progress");
    }

    @Test
    void dateWithoutDataIsUnprocessable() {
        importDataset();

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/runs?businessDate=2030-01-01", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("detail").asText()).contains("No trades or confirms for business date 2030-01-01");
    }

    @Test
    void badOrMissingBusinessDateIsAClearBadRequest() {
        ResponseEntity<JsonNode> bad = rest.postForEntity("/api/runs?businessDate=15/01/2026", null, JsonNode.class);
        ResponseEntity<JsonNode> missing = rest.postForEntity("/api/runs", null, JsonNode.class);

        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("detail").asText()).isEqualTo("businessDate must be an ISO date (yyyy-MM-dd), got '15/01/2026'");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("detail").asText()).contains("businessDate");
    }

    @Test
    void unknownRunIsNotFound() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/runs/999", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("detail").asText()).isEqualTo("Run 999 does not exist");
    }

    private List<Map<String, Object>> links(long runId) {
        return jdbc.queryForList("select internal_id, external_id, rule, score from match_link where run_id = ? order by internal_id", runId);
    }

    private List<Map<String, Object>> openBreaks() {
        return jdbc.queryForList("""
                select id, run_id, type, internal_id, external_id, field, expected, actual, status, opened_at
                from recon_break where status <> 'RESOLVED' order by id""");
    }
}
