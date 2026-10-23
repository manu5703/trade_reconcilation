package com.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class BreakApiIT extends IntegrationTest {

    private long breakId;
    private double runStpRate;

    @BeforeEach
    void runOnce() {
        importDataset();
        runStpRate = runAndWait(BUSINESS_DATE).stpRate();
        breakId = jdbc.queryForObject("select min(id) from recon_break", Long.class);
    }

    @Test
    void listsAllBreaksWithTheirTradeAndConfirm() {
        JsonNode page = get("/api/breaks?type=QUANTITY_MISMATCH&size=5");

        assertThat(page.get("totalItems").asLong()).isEqualTo(count("type = 'QUANTITY_MISMATCH'"));
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("field").asText()).isEqualTo("quantity");
        assertThat(first.get("expected").asText()).isEqualTo(first.get("trade").get("quantity").decimalValue().stripTrailingZeros().toPlainString());
        assertThat(first.get("actual").asText()).isEqualTo(first.get("confirm").get("quantity").decimalValue().stripTrailingZeros().toPlainString());
    }

    @Test
    void filtersByStatusCounterpartyAndAge() {
        String counterparty = jdbc.queryForObject("select counterparty from recon_break where id = ?", String.class, breakId);
        jdbc.update("update recon_break set opened_at = now() - interval '30 hours' where id = ?", breakId);

        assertThat(get("/api/breaks?status=OPEN").get("totalItems").asLong()).isEqualTo(count("status = 'OPEN'"));
        assertThat(get("/api/breaks?counterparty=" + counterparty.toLowerCase()).get("totalItems").asLong())
                .isEqualTo(count("counterparty = '" + counterparty + "'"));
        JsonNode old = get("/api/breaks?minAgeHours=24");
        assertThat(old.get("totalItems").asLong()).isEqualTo(1);
        assertThat(old.get("items").get(0).get("ageHours").asLong()).isEqualTo(30);
    }

    @Test
    void pagesAreStableAndSortable() {
        long total = count("true");
        JsonNode page0 = get("/api/breaks?size=10&page=0&sort=-openedAt");
        JsonNode page1 = get("/api/breaks?size=10&page=1&sort=-openedAt");

        assertThat(page0.get("totalPages").asInt()).isEqualTo((int) Math.ceil(total / 10.0));
        assertThat(page0.get("items").get(9).get("id").asLong()).isNotEqualTo(page1.get("items").get(0).get("id").asLong());
    }

    @Test
    void invalidQueryParametersExplainWhatIsAllowed() {
        assertThat(problem("/api/breaks?status=CLOSED")).isEqualTo("status must be one of OPEN, ASSIGNED, RESOLVED, got 'CLOSED'");
        assertThat(problem("/api/breaks?size=500")).isEqualTo("size must be less than or equal to 200");
        assertThat(problem("/api/breaks?sort=amount")).startsWith("sort must be one of businessDate, counterparty, openedAt, status, type");
    }

    @Test
    void assignNoteAndResolveLifecycle() {
        JsonNode assigned = patch(breakId, Map.of("owner", "j.smith", "note", "Chasing the broker"));
        assertThat(assigned.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.get("owner").asText()).isEqualTo("j.smith");
        assertThat(assigned.get("note").asText()).endsWith("] Chasing the broker");

        JsonNode resolved = patch(breakId, Map.of("status", "RESOLVED", "reason", "Broker re-sent a corrected confirm",
                "expectedVersion", assigned.get("version").asLong()));
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolutionReason").asText()).isEqualTo("Broker re-sent a corrected confirm");
        assertThat(resolved.get("resolvedAt").isNull()).isFalse();
    }

    @Test
    void resolvingNeedsAReason() {
        ResponseEntity<JsonNode> response = patchRaw(breakId, Map.of("status", "RESOLVED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("detail").asText()).isEqualTo("reason is required to resolve a break");
        assertThat(response.getBody().get("errors").get(0).get("field").asText()).isEqualTo("reason");
    }

    @Test
    void patchValidationReportsEveryProblem() {
        ResponseEntity<JsonNode> empty = patchRaw(breakId, Map.of());
        ResponseEntity<JsonNode> reopen = patchRaw(breakId, Map.of("status", "OPEN", "reason", "x"));
        ResponseEntity<JsonNode> badEnum = patchRaw(breakId, Map.of("status", "DONE"));

        assertThat(empty.getBody().get("detail").asText()).isEqualTo("Provide at least one of owner, note or status");
        assertThat(reopen.getBody().get("errors")).hasSize(2);
        assertThat(badEnum.getBody().get("detail").asText()).isEqualTo("status must be one of OPEN, ASSIGNED, RESOLVED, got 'DONE'");
    }

    @Test
    void resolvedBreaksAreFrozenAndStaleEditsAreRejected() {
        patch(breakId, Map.of("status", "RESOLVED", "reason", "Booking error, amended"));

        ResponseEntity<JsonNode> afterResolve = patchRaw(breakId, Map.of("note", "one more thing"));
        assertThat(afterResolve.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        long other = breakId + 1;
        ResponseEntity<JsonNode> stale = patchRaw(other, Map.of("owner", "a.jones", "expectedVersion", 99));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("detail").asText()).contains("changed since you loaded it");
    }

    @Test
    void assignmentSurvivesARerun() {
        patch(breakId, Map.of("owner", "j.smith"));

        runAndWait(BUSINESS_DATE);

        JsonNode afterRerun = get("/api/breaks/" + breakId);
        assertThat(afterRerun.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(afterRerun.get("owner").asText()).isEqualTo("j.smith");
    }

    @Test
    void summaryAgreesWithTheQueue() {
        jdbc.update("update recon_break set opened_at = now() - interval '4 days' where id = ?", breakId);
        patch(breakId + 1, Map.of("owner", "j.smith"));

        JsonNode summary = get("/api/breaks/summary");

        long live = count("status <> 'RESOLVED'");
        assertThat(summary.get("open").asLong() + summary.get("assigned").asLong()).isEqualTo(live);
        assertThat(summary.get("assigned").asLong()).isEqualTo(1);
        assertThat(summary.get("byType").get("DUPLICATE_CONFIRM").asLong()).isEqualTo(count("type = 'DUPLICATE_CONFIRM'"));
        long byAge = 0;
        for (JsonNode bucket : summary.get("byAge")) {
            byAge += bucket.get("count").asLong();
        }
        assertThat(byAge).isEqualTo(live);
        assertThat(summary.get("byAge").get(2).get("count").asLong()).isEqualTo(1);   // 3-7d
        assertThat(summary.get("oldestAgeHours").asLong()).isEqualTo(96);
        assertThat(summary.get("latestRun").get("stpRate").asDouble()).isEqualTo(runStpRate);
    }

    private long count(String where) {
        return jdbc.queryForObject("select count(*) from recon_break where " + where, Long.class);
    }

    private JsonNode get(String url) {
        ResponseEntity<JsonNode> response = rest.getForEntity(url, JsonNode.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String problem(String url) {
        ResponseEntity<JsonNode> response = rest.getForEntity(url, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return response.getBody().get("detail").asText();
    }

    private JsonNode patch(long id, Map<String, Object> body) {
        ResponseEntity<JsonNode> response = patchRaw(id, body);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> patchRaw(long id, Map<String, Object> body) {
        return rest.exchange("/api/breaks/" + id, HttpMethod.PATCH, new HttpEntity<>(body), JsonNode.class);
    }
}
