package com.figuard.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.enums.BudgetStatus;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Java side of the embedded↔server drift gate.
 *
 * Reads the SAME language-neutral conformance scenarios that drive figuard-lite (Python) and
 * runs each one against the real Java core over the REST API. Both implementations must pass
 * the identical curated expectations — that is the parity guarantee. If the kernel changes
 * behavior, this test (and the Python runner) go red until the scenarios and both sides agree.
 *
 * Scenario location: -Dfiguard.conformance.scenarios=... else lite/conformance/scenarios (in-repo).
 * Scenarios that need a non-ACTIVE initial budget state (no direct create-time API) are skipped
 * here for now (handled via setup steps in a later slice).
 */
class KernelConformanceIT extends IntegrationTestBase {

    @Autowired ObjectMapper json;
    @Autowired AgentBudgetRepository budgetRepository;

    // When set, the suite runs in golden-vector mode: instead of asserting curated expectations,
    // it drives each (expectation-free) scenario through the REAL Java core and dumps the
    // observed outputs as authoritative golden vectors for the Python differential check.
    private static final String GOLDEN = System.getProperty("figuard.conformance.golden");

    // generic scenario field -> Java JSON field
    private static final Map<String, String> RESP_KEY = Map.of(
        "decision", "decision",
        "denial_reason", "denialReason",
        "approved_quantity", "approvedQuantity");
    private static final Map<String, String> STATE_KEY = Map.of(
        "available", "availableQuantity",
        "quantity_reserved", "quantityReserved",
        "quantity_spent", "quantitySpent");

    @TestFactory
    Stream<DynamicTest> kernelConformance() throws Exception {
        if (GOLDEN != null) return Stream.empty();   // golden-vector mode handled by dumpGoldenVectors
        return loadScenarios().stream().map(sc -> DynamicTest.dynamicTest(
            (String) sc.get("id"), () -> runScenario(sc)));
    }

    /** Golden-vector generation: run each scenario against the real core and record the
     *  authoritative outputs the Python side must reproduce. Active only when -Dfiguard.conformance.golden is set. */
    @Test
    void dumpGoldenVectors() throws Exception {
        if (GOLDEN == null) return;
        List<Map<String, Object>> golden = new ArrayList<>();
        for (Map<String, Object> sc : loadScenarios()) {
            golden.add(recordScenario(sc));
        }
        File out = new File(GOLDEN);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        json.writerWithDefaultPrettyPrinter().writeValue(out, golden);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordScenario(Map<String, Object> sc) throws Exception {
        Budget b = createBudget((Map<String, Object>) sc.get("budget"));
        List<Map<String, Object>> stepRecords = new ArrayList<>();
        List<Map<String, Object>> stepResponses = new ArrayList<>();
        for (Map<String, Object> step : (List<Map<String, Object>>) sc.getOrDefault("steps", List.of())) {
            Map<String, Object> request = resolveRefs(
                (Map<String, Object>) step.getOrDefault("request", Map.of()), stepResponses);
            Map<String, Object> resp = execute((String) step.get("op"), request, b);
            stepResponses.add(resp);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("decision", resp.get("decision"));
            if (resp.get("denialReason") != null) rec.put("denial_reason", resp.get("denialReason"));
            if (resp.get("approvedQuantity") != null) rec.put("approved_quantity", resp.get("approvedQuantity"));
            stepRecords.add(rec);
        }
        Map<String, Object> snap = getBudget(b.id);
        Map<String, Object> finalState = new LinkedHashMap<>();
        finalState.put("available", snap.get("availableQuantity"));
        finalState.put("quantity_reserved", snap.get("quantityReserved"));
        finalState.put("quantity_spent", snap.get("quantitySpent"));
        Map<String, Object> golden = new LinkedHashMap<>();
        golden.put("id", sc.get("id"));
        golden.put("steps", stepRecords);
        golden.put("final_state", finalState);
        return golden;
    }

    @SuppressWarnings("unchecked")
    private void runScenario(Map<String, Object> sc) throws Exception {
        Map<String, Object> budgetSpec = (Map<String, Object>) sc.get("budget");
        Budget b = createBudget(budgetSpec);
        List<Map<String, Object>> stepResponses = new ArrayList<>();

        List<Map<String, Object>> steps = (List<Map<String, Object>>) sc.getOrDefault("steps", List.of());
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            Map<String, Object> request = resolveRefs(
                (Map<String, Object>) step.getOrDefault("request", Map.of()), stepResponses);
            Map<String, Object> expect = (Map<String, Object>) step.get("expect");
            String where = sc.get("id") + " step[" + i + "]";

            // `error` expectation: the op must fail (4xx -> parse throws) with a matching message.
            if (expect != null && expect.containsKey("error")) {
                String want = expect.get("error").toString();
                Throwable caught = null;
                try { execute((String) step.get("op"), request, b); }
                catch (Throwable t) { caught = t; }
                if (caught == null)
                    throw new AssertionError(where + ": expected error containing '" + want + "' but op succeeded");
                if (!String.valueOf(caught.getMessage()).contains(want))
                    throw new AssertionError(where + ": expected error '" + want + "' but got: " + caught.getMessage());
                stepResponses.add(Map.of());
                continue;
            }

            Map<String, Object> resp = execute((String) step.get("op"), request, b);
            stepResponses.add(resp);
            if (expect != null) {
                final int idx = i;
                expect.forEach((k, exp) ->
                    assertEqualish(sc.get("id") + " step[" + idx + "] " + k,
                        exp, resp.get(RESP_KEY.getOrDefault(k, k))));
            }
        }

        Map<String, Object> finalState = (Map<String, Object>) sc.get("final_state");
        if (finalState != null) {
            Map<String, Object> snap = getBudget(b.id);
            finalState.forEach((k, exp) ->
                assertEqualish(sc.get("id") + " final_state " + k, exp, snap.get(STATE_KEY.get(k))));
        }
    }

    // -- ops -------------------------------------------------------------------

    private Map<String, Object> execute(String op, Map<String, Object> req, Budget b) throws Exception {
        switch (op) {
            case "authorize": {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("agentId", "conformance");
                body.put("actionType", "PURCHASE");
                body.put("description", "conformance");
                body.put("requestedQuantity", bd(req.get("amount")));
                if (req.containsKey("reserve")) body.put("reserve", req.get("reserve"));
                if (req.get("idempotency_key") != null) body.put("idempotencyKey", req.get("idempotency_key"));
                if (req.get("entity_id") != null) body.put("entityId", req.get("entity_id"));
                if (req.get("currency") != null) body.put("currency", req.get("currency"));
                if (req.get("claimed_category") != null) body.put("claimedCategory", req.get("claimed_category"));
                if (req.get("parent_event_id") != null) body.put("parentEventId", req.get("parent_event_id"));
                if (req.get("intent_context") != null) body.put("intentContext", req.get("intent_context"));
                MvcResult r = mockMvc.perform(post("/api/v1/authorize")
                    .header("X-Agent-Budget-Key", TEST_API_KEY)
                    .header("X-Session-Token", b.sessionToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))).andReturn();
                return parse(r);
            }
            case "confirm": {
                Map<String, Object> body = Map.of("confirmedQuantity", bd(req.get("confirmed_quantity")));
                return parse(mockMvc.perform(post("/api/v1/events/{id}/confirm", req.get("event_ref"))
                    .header("X-Agent-Budget-Key", TEST_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))).andReturn());
            }
            case "fail":
                return parse(mockMvc.perform(post("/api/v1/events/{id}/fail", req.get("event_ref"))
                    .header("X-Agent-Budget-Key", TEST_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("reason",
                        req.getOrDefault("reason", "conformance"))))).andReturn());
            case "void":
                return parse(mockMvc.perform(post("/api/v1/events/{id}/void", req.get("event_ref"))
                    .header("X-Agent-Budget-Key", TEST_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("reason",
                        req.getOrDefault("reason", "conformance"))))).andReturn());
            default:
                throw new IllegalArgumentException("unknown op: " + op);
        }
    }

    private Budget createBudget(Map<String, Object> spec) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", "conformance");
        body.put("totalLimit", bd(spec.get("total_limit")));
        // expiresAt is required by the create API and capped at 24h ahead; well within any
        // scenario's runtime so it never affects a result.
        body.put("expiresAt", OffsetDateTime.now().plusHours(23).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (spec.get("currency") != null) body.put("currency", spec.get("currency"));
        else body.put("unit", spec.getOrDefault("unit", "usd"));
        if (spec.get("max_transaction_quantity") != null)
            body.put("maxTransactionQuantity", bd(spec.get("max_transaction_quantity")));
        if (Boolean.TRUE.equals(spec.get("entity_dedup_enabled")))
            body.put("entityDedupEnabled", true);
        if (spec.get("velocity_max_per_minute") != null)
            body.put("velocityMaxPerMinute", spec.get("velocity_max_per_minute"));
        if (spec.get("velocity_max_amount_per_hour") != null)
            body.put("velocityMaxAmountPerHour", bd(spec.get("velocity_max_amount_per_hour")));
        if (spec.get("velocity_max_per_day") != null)
            body.put("velocityMaxPerDay", spec.get("velocity_max_per_day"));
        if (spec.get("intent_tags") != null)
            body.put("intentTags", spec.get("intent_tags"));

        MvcResult r = mockMvc.perform(post("/api/v1/budgets")
            .header("X-Agent-Budget-Key", TEST_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andReturn();
        Map<String, Object> resp = parse(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tokens = (List<Map<String, Object>>) resp.get("tokens");
        if (tokens == null) {
            throw new AssertionError("create-budget failed: HTTP " + r.getResponse().getStatus()
                + " body=" + r.getResponse().getContentAsString());
        }
        String id = (String) resp.get("id");
        // The create API only makes ACTIVE budgets; for a scenario that needs a non-ACTIVE
        // initial state (e.g. PAUSED), set it directly — we're testing authorize-on-that-state,
        // not the transition that produced it.
        Object status = spec.get("status");
        if (status != null && !"ACTIVE".equals(status)) {
            AgentBudget budget = budgetRepository.findById(java.util.UUID.fromString(id)).orElseThrow();
            budget.setStatus(BudgetStatus.valueOf(status.toString()));
            budgetRepository.save(budget);
        }
        return new Budget(id, (String) tokens.get(0).get("sessionToken"));
    }

    private Map<String, Object> getBudget(String id) throws Exception {
        return parse(mockMvc.perform(get("/api/v1/budgets/{id}", id)
            .header("X-Agent-Budget-Key", TEST_API_KEY)).andReturn());
    }

    // -- helpers ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRefs(Map<String, Object> req, List<Map<String, Object>> prior) {
        Map<String, Object> out = new LinkedHashMap<>();
        req.forEach((k, v) -> {
            if (v instanceof String s && s.startsWith("$steps[")) {
                int idx = Integer.parseInt(s.substring(s.indexOf('[') + 1, s.indexOf(']')));
                out.put(k, prior.get(idx).get("eventId"));   // only event_id refs are used
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private Map<String, Object> parse(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        // Denials are HTTP 200 with decision=DENIED; only a true error is >=400. Surface it
        // loudly so a scenario failure can never be silently read as an absent field.
        if (r.getResponse().getStatus() >= 400) {
            throw new AssertionError("HTTP " + r.getResponse().getStatus() + " body=" + body);
        }
        if (body == null || body.isBlank()) return Map.of();
        return json.readValue(body, Map.class);
    }

    private static BigDecimal bd(Object o) {
        return new BigDecimal(o.toString());
    }

    private void assertEqualish(String where, Object expected, Object actual) {
        if (actual == null) {
            throw new AssertionError(where + ": expected " + expected + " but field was absent/null");
        }
        try {
            if (new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString())) == 0) return;
            throw new AssertionError(where + ": expected " + expected + " got " + actual);
        } catch (NumberFormatException notNumeric) {
            if (!expected.toString().equals(actual.toString())) {
                throw new AssertionError(where + ": expected " + expected + " got " + actual);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadScenarios() throws Exception {
        String dir = System.getProperty("figuard.conformance.scenarios",
            "lite/conformance/scenarios");
        List<Map<String, Object>> all = new ArrayList<>();
        try (var paths = Files.list(Path.of(dir))) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".yaml")).sorted().toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    List<Map<String, Object>> list = new Yaml().load(in);
                    if (list != null) all.addAll(list);
                }
            }
        }
        return all;
    }

    private record Budget(String id, String sessionToken) {}
}
