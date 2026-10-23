package com.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class ImportAndOpsIT extends IntegrationTest {

    @Test
    void importLoadsEveryTableAndIsIdempotent() {
        JsonNode first = rest.postForObject("/api/imports", null, JsonNode.class);
        JsonNode second = rest.postForObject("/api/imports", null, JsonNode.class);

        assertThat(first.at("/tables/internal_trade/inserted").asInt()).isEqualTo(TRADES);
        assertThat(first.at("/tables/instrument/inserted").asInt()).isEqualTo(200);
        assertThat(first.at("/tables/counterparty/inserted").asInt()).isEqualTo(30);
        assertThat(second.at("/tables/internal_trade/inserted").asInt()).isZero();
        assertThat(second.at("/tables/internal_trade/skipped").asInt()).isEqualTo(TRADES);
        assertThat(jdbc.queryForObject("select count(*) from counterparty_alias", Integer.class)).isEqualTo(90);
        // Raw values survive the import untouched; normalizing is the matcher's job.
        assertThat(jdbc.queryForObject("select count(*) from external_confirm where isin <> upper(trim(isin))", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from internal_trade where block_ref is null", Integer.class)).isPositive();
    }

    @Test
    void prometheusExposesRunMetrics() {
        importDataset();
        runAndWait(BUSINESS_DATE);

        String metrics = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics).contains("recon_runs_total{status=\"COMPLETED\"}")
                .contains("recon_matches_total{rule=\"BLOCK\"}")
                .contains("recon_breaks_found_total{type=\"SIDE_MISMATCH\"}")
                .contains("recon_breaks_open ")
                .contains("recon_run_duration_seconds_count");
    }

    @Test
    void openApiDocumentsTheEndpoints() {
        JsonNode docs = rest.getForObject("/v3/api-docs", JsonNode.class);

        assertThat(docs.at("/info/title").asText()).isEqualTo("Trade Reconciliation API");
        assertThat(docs.get("paths").has("/api/runs")).isTrue();
        assertThat(docs.get("paths").has("/api/breaks/{id}")).isTrue();
        assertThat(docs.get("paths").has("/api/breaks/summary")).isTrue();
        assertThat(rest.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void healthIsUp() {
        assertThat(rest.getForObject("/actuator/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
    }
}
