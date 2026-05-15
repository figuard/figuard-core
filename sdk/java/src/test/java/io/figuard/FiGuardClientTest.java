package io.figuard;

import io.figuard.exception.FiGuardApiException;
import io.figuard.exception.FiGuardDeniedException;
import io.figuard.model.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link FiGuardClient} using OkHttp's {@link MockWebServer}.
 *
 * <p>Each nested class starts its own server so tests are fully isolated.
 */
class FiGuardClientTest {

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static final String API_KEY = "ab_test_1234567890";

    static FiGuardClient clientFor(MockWebServer server) {
        OkHttpClient fast = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(5))
                .build();
        return FiGuardClient.builder()
                .apiKey(API_KEY)
                .baseUrl(server.url("/").toString())
                .httpClient(fast)
                .build();
    }

    static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    static final String BUDGET_ACTIVE = """
            {
              "id": "bud-1",
              "userId": "user-42",
              "totalLimit": 500.00,
              "currency": "USD",
              "quantitySpent": 0.00,
              "quantityReserved": 0.00,
              "availableQuantity": 500.00,
              "status": "ACTIVE",
              "expiresAt": "2024-12-31T23:59:59Z",
              "createdAt": "2024-01-01T00:00:00Z",
              "sessionTokenPrefix": "ab_sess_1",
              "sessionToken": "ab_sess_1234567890abcdef",
              "allocations": []
            }
            """;

    static final String BUDGET_PAUSED = """
            {
              "id": "bud-3",
              "userId": "user-9",
              "totalLimit": 300.00,
              "currency": "USD",
              "quantitySpent": 0.00,
              "quantityReserved": 0.00,
              "availableQuantity": 300.00,
              "status": "ACTIVE",
              "expiresAt": "2025-06-01T00:00:00Z",
              "createdAt": "2024-01-01T00:00:00Z",
              "allocations": []
            }
            """;

    // -------------------------------------------------------------------------
    // Budget management
    // -------------------------------------------------------------------------

    @Nested
    class BudgetManagement {

        MockWebServer server = new MockWebServer();
        FiGuardClient client;

        @BeforeEach void setUp() throws Exception {
            server.start();
            client = clientFor(server);
        }

        @AfterEach void tearDown() throws IOException { server.shutdown(); }

        @Test
        void create_budget_returns_budget_with_session_token() throws Exception {
            server.enqueue(json(BUDGET_ACTIVE));

            Budget budget = client.createBudget(CreateBudgetRequest.builder()
                    .userId("user-42")
                    .totalLimit(new BigDecimal("500.00"))
                    .currency("USD")
                    .expiresAt("2024-12-31T23:59:59Z")
                    .build());

            assertThat(budget.id()).isEqualTo("bud-1");
            assertThat(budget.sessionToken()).isEqualTo("ab_sess_1234567890abcdef");
            assertThat(budget.totalLimit()).isEqualByComparingTo("500.00");
            assertThat(budget.isActive()).isTrue();
            assertThat(budget.isPaused()).isFalse();
            assertThat(budget.isMonetary()).isTrue();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/api/v1/budgets");
            assertThat(req.getHeader("X-Agent-Budget-Key")).isEqualTo(API_KEY);
        }

        @Test
        void create_budget_with_expires_in() throws Exception {
            server.enqueue(json(BUDGET_ACTIVE));

            Budget budget = client.createBudget(CreateBudgetRequest.builder()
                    .userId("user-42")
                    .totalLimit(new BigDecimal("500.00"))
                    .currency("USD")
                    .expiresIn("24h")
                    .intentContext("travel booking")
                    .build());

            assertThat(budget.id()).isEqualTo("bud-1");

            RecordedRequest req = server.takeRequest();
            String body = req.getBody().readUtf8();
            assertThat(body).contains("\"expiresIn\":\"24h\"");
            assertThat(body).contains("\"intentContext\":\"travel booking\"");
        }

        @Test
        void create_token_budget_sends_unit_field() throws Exception {
            server.enqueue(json("""
                    {
                      "id": "bud-token-1",
                      "userId": "user-42",
                      "totalLimit": 100000,
                      "unit": "tokens",
                      "quantitySpent": 0,
                      "quantityReserved": 0,
                      "availableQuantity": 100000,
                      "status": "ACTIVE",
                      "expiresAt": "2024-12-31T23:59:59Z",
                      "sessionTokenPrefix": "ab_sess_t",
                      "sessionToken": "ab_sess_token1234",
                      "allocations": []
                    }
                    """));

            Budget budget = client.createBudget(CreateBudgetRequest.builder()
                    .userId("user-42")
                    .totalLimit(new BigDecimal("100000"))
                    .unit("tokens")
                    .expiresIn("24h")
                    .build());

            assertThat(budget.unit()).isEqualTo("tokens");
            assertThat(budget.isMonetary()).isFalse();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getBody().readUtf8()).contains("\"unit\":\"tokens\"");
        }

        @Test
        void get_budget_returns_current_state() throws Exception {
            server.enqueue(json("""
                    {
                      "id": "bud-2",
                      "userId": "user-5",
                      "totalLimit": 1000.00,
                      "currency": "USD",
                      "quantitySpent": 250.00,
                      "quantityReserved": 100.00,
                      "availableQuantity": 650.00,
                      "status": "ACTIVE",
                      "expiresAt": "2025-01-01T00:00:00Z",
                      "createdAt": "2024-06-01T00:00:00Z",
                      "allocations": []
                    }
                    """));

            Budget budget = client.getBudget("bud-2");

            assertThat(budget.id()).isEqualTo("bud-2");
            assertThat(budget.quantitySpent()).isEqualByComparingTo("250.00");
            assertThat(budget.availableQuantity()).isEqualByComparingTo("650.00");

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("GET");
            assertThat(req.getPath()).isEqualTo("/api/v1/budgets/bud-2");
        }

        @Test
        void resume_budget_returns_active_status() throws Exception {
            server.enqueue(json(BUDGET_PAUSED));

            Budget resumed = client.resumeBudget("bud-3", "Manual override after review");

            assertThat(resumed.isActive()).isTrue();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/budgets/bud-3/resume");
            assertThat(req.getBody().readUtf8()).contains("Manual override after review");
        }
    }

    // -------------------------------------------------------------------------
    // Authorize
    // -------------------------------------------------------------------------

    @Nested
    class Authorize {

        MockWebServer server = new MockWebServer();
        FiGuardClient client;

        @BeforeEach void setUp() throws Exception {
            server.start();
            client = clientFor(server);
        }

        @AfterEach void tearDown() throws IOException { server.shutdown(); }

        AuthorizeRequest validRequest() {
            return AuthorizeRequest.builder()
                    .agentId("agent-007")
                    .actionType("PURCHASE")
                    .description("Cloud GPU credits")
                    .requestedQuantity(new BigDecimal("49.99"))
                    .idempotencyKey(UUID.randomUUID().toString())
                    .sessionToken("ab_sess_abcdef1234567890")
                    .build();
        }

        @Test
        void throws_when_idempotency_key_is_null() {
            AuthorizeRequest req = AuthorizeRequest.builder()
                    .agentId("agent-007")
                    .actionType("PURCHASE")
                    .requestedQuantity(new BigDecimal("10.00"))
                    .sessionToken("ab_sess_xyz")
                    .build(); // idempotencyKey defaults to null

            assertThatThrownBy(() -> client.authorize(req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("idempotencyKey");
        }

        @Test
        void throws_when_idempotency_key_is_blank() {
            AuthorizeRequest req = AuthorizeRequest.builder()
                    .agentId("agent-007")
                    .actionType("PURCHASE")
                    .requestedQuantity(new BigDecimal("10.00"))
                    .idempotencyKey("   ")
                    .sessionToken("ab_sess_xyz")
                    .build();

            assertThatThrownBy(() -> client.authorize(req))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void authorized_result_is_authorized_true() throws Exception {
            server.enqueue(json("""
                    {
                      "eventId": "evt-001",
                      "decision": "AUTHORIZED",
                      "approvedQuantity": 49.99,
                      "authorizedAt": "2024-06-01T10:00:00Z"
                    }
                    """));

            AuthorizationResult result = client.authorize(validRequest());

            assertThat(result.isAuthorized()).isTrue();
            assertThat(result.eventId()).isEqualTo("evt-001");
            assertThat(result.approvedQuantity()).isEqualByComparingTo("49.99");

            RecordedRequest req = server.takeRequest();
            assertThat(req.getHeader("X-Session-Token")).isEqualTo("ab_sess_abcdef1234567890");
            assertThat(req.getPath()).isEqualTo("/api/v1/authorize");
            assertThat(req.getBody().readUtf8()).contains("\"requestedQuantity\"");
        }

        @Test
        void denied_result_is_authorized_false() throws Exception {
            server.enqueue(json("""
                    {
                      "eventId": "evt-002",
                      "decision": "DENIED",
                      "denialReason": "BUDGET_EXHAUSTED",
                      "denialMessage": "Insufficient budget balance"
                    }
                    """));

            AuthorizationResult result = client.authorize(validRequest());

            assertThat(result.isAuthorized()).isFalse();
            assertThat(result.denialReason()).isEqualTo("BUDGET_EXHAUSTED");
        }

        @Test
        void raise_if_denied_throws_figuard_denied_exception() throws Exception {
            server.enqueue(json("""
                    {
                      "eventId": "evt-003",
                      "decision": "DENIED",
                      "denialReason": "POLICY_VIOLATION",
                      "denialMessage": "Category not permitted"
                    }
                    """));

            assertThatThrownBy(() -> client.authorize(validRequest()).raiseIfDenied())
                    .isInstanceOf(FiGuardDeniedException.class)
                    .satisfies(ex -> {
                        FiGuardDeniedException denied = (FiGuardDeniedException) ex;
                        assertThat(denied.getDenialReason()).isEqualTo("POLICY_VIOLATION");
                        assertThat(denied.getMessage()).contains("Category not permitted");
                    });
        }

        @Test
        void raise_if_denied_passes_through_on_authorized() throws Exception {
            server.enqueue(json("""
                    {
                      "eventId": "evt-004",
                      "decision": "AUTHORIZED",
                      "approvedQuantity": 49.99
                    }
                    """));

            AuthorizationResult result = client.authorize(validRequest()).raiseIfDenied();

            assertThat(result.isAuthorized()).isTrue();
            assertThat(result.eventId()).isEqualTo("evt-004");
        }
    }

    // -------------------------------------------------------------------------
    // Event lifecycle
    // -------------------------------------------------------------------------

    @Nested
    class EventLifecycle {

        MockWebServer server = new MockWebServer();
        FiGuardClient client;

        @BeforeEach void setUp() throws Exception {
            server.start();
            client = clientFor(server);
        }

        @AfterEach void tearDown() throws IOException { server.shutdown(); }

        @Test
        void confirm_event_posts_to_confirm_path() throws Exception {
            server.enqueue(json("""
                    {
                      "id": "evt-100",
                      "decision": "AUTHORIZED",
                      "requestedQuantity": 99.00,
                      "confirmedQuantity": 95.50,
                      "currency": "USD"
                    }
                    """));

            SpendEventResponse event = client.confirmEvent("evt-100", new BigDecimal("95.50"));

            assertThat(event.id()).isEqualTo("evt-100");
            assertThat(event.confirmedQuantity()).isEqualByComparingTo("95.50");

            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/events/evt-100/confirm");
            assertThat(req.getBody().readUtf8()).contains("confirmedQuantity");
        }

        @Test
        void fail_event_posts_to_fail_path() throws Exception {
            server.enqueue(json("""
                    {
                      "id": "evt-101",
                      "decision": "AUTHORIZED",
                      "requestedQuantity": 50.00,
                      "failureReason": "PAYMENT_GATEWAY_ERROR"
                    }
                    """));

            SpendEventResponse event = client.failEvent("evt-101", "PAYMENT_GATEWAY_ERROR",
                    "Stripe returned 402");

            assertThat(event.failureReason()).isEqualTo("PAYMENT_GATEWAY_ERROR");

            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/events/evt-101/fail");
        }

        @Test
        void void_event_returns_voided_result() throws Exception {
            server.enqueue(json("""
                    {
                      "id": "evt-102",
                      "decision": "VOIDED",
                      "requestedQuantity": 30.00
                    }
                    """));

            VoidResult result = client.voidEvent("evt-102", "TASK_CANCELLED");

            assertThat(result.isVoided()).isTrue();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/events/evt-102/void");
            assertThat(req.getBody().readUtf8()).contains("TASK_CANCELLED");
        }
    }

    // -------------------------------------------------------------------------
    // Retry behaviour
    // -------------------------------------------------------------------------

    @Nested
    class RetryBehaviour {

        MockWebServer server = new MockWebServer();
        FiGuardClient client;

        @BeforeEach void setUp() throws Exception {
            server.start();
            client = clientFor(server);
        }

        @AfterEach void tearDown() throws IOException { server.shutdown(); }

        @Test
        void retries_on_500_then_succeeds() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"oops\"}"));
            server.enqueue(json(BUDGET_ACTIVE));

            Budget budget = client.createBudget(CreateBudgetRequest.builder()
                    .userId("user-42")
                    .totalLimit(new BigDecimal("500.00"))
                    .currency("USD")
                    .expiresAt("2024-12-31T23:59:59Z")
                    .build());

            assertThat(budget.id()).isEqualTo("bud-1");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }

        @Test
        void does_not_retry_on_4xx() throws Exception {
            server.enqueue(new MockResponse()
                    .setResponseCode(403)
                    .setBody("{\"message\":\"Forbidden\"}"));

            assertThatThrownBy(() -> client.getBudget("bud-missing"))
                    .isInstanceOf(FiGuardApiException.class)
                    .satisfies(ex -> assertThat(((FiGuardApiException) ex).getStatusCode()).isEqualTo(403));

            assertThat(server.getRequestCount()).isEqualTo(1);
        }

        @Test
        void exhausts_all_retries_on_persistent_500() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"err\"}"));
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"err\"}"));
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"err\"}"));

            assertThatThrownBy(() -> client.getBudget("bud-bad"))
                    .isInstanceOf(FiGuardApiException.class)
                    .satisfies(ex -> assertThat(((FiGuardApiException) ex).getStatusCode()).isEqualTo(500));

            assertThat(server.getRequestCount()).isEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // Ledger
    // -------------------------------------------------------------------------

    @Nested
    class Ledger {

        MockWebServer server = new MockWebServer();
        FiGuardClient client;

        @BeforeEach void setUp() throws Exception {
            server.start();
            client = clientFor(server);
        }

        @AfterEach void tearDown() throws IOException { server.shutdown(); }

        @Test
        void get_ledger_returns_paged_events() throws Exception {
            server.enqueue(json("""
                    {
                      "content": [
                        {"id": "evt-200", "decision": "AUTHORIZED", "requestedQuantity": 10.00, "currency": "USD"},
                        {"id": "evt-201", "decision": "VOIDED",     "requestedQuantity":  5.00, "currency": "USD"}
                      ],
                      "totalElements": 2,
                      "totalPages": 1,
                      "number": 0,
                      "size": 20
                    }
                    """));

            LedgerPage page = client.getLedger("bud-ledger");

            assertThat(page.events()).hasSize(2);
            assertThat(page.events().get(0).id()).isEqualTo("evt-200");
            assertThat(page.totalElements()).isEqualTo(2L);
            assertThat(page.hasNext()).isFalse();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).contains("/api/v1/budgets/bud-ledger/ledger");
            assertThat(req.getPath()).contains("page=0");
            assertThat(req.getPath()).contains("size=20");
        }

        @Test
        void has_next_true_when_more_pages_exist() throws Exception {
            server.enqueue(json("""
                    {
                      "content": [],
                      "totalElements": 100,
                      "totalPages": 5,
                      "number": 0,
                      "size": 20
                    }
                    """));

            LedgerPage page = client.getLedger("bud-multi");

            assertThat(page.hasNext()).isTrue();
            assertThat(page.totalPages()).isEqualTo(5);
        }
    }

    // -------------------------------------------------------------------------
    // Session token rotation
    // -------------------------------------------------------------------------

    @Nested
    class SessionToken {

        MockWebServer server = new MockWebServer();
        FiGuardClient client;

        @BeforeEach void setUp() throws Exception {
            server.start();
            client = clientFor(server);
        }

        @AfterEach void tearDown() throws IOException { server.shutdown(); }

        @Test
        void rotate_returns_new_token() throws Exception {
            server.enqueue(json("{\"sessionToken\": \"ab_sess_newtoken9999\"}"));

            String newToken = client.rotateSessionToken("bud-rotate");

            assertThat(newToken).isEqualTo("ab_sess_newtoken9999");

            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/budgets/bud-rotate/rotate-token");
            assertThat(req.getMethod()).isEqualTo("POST");
        }
    }

    // -------------------------------------------------------------------------
    // AuthorizeRequest builder validation
    // -------------------------------------------------------------------------

    @Nested
    class AuthorizeRequestBuilderValidation {

        @Test
        void throws_when_agent_id_missing() {
            assertThatThrownBy(() ->
                    AuthorizeRequest.builder()
                            .actionType("PURCHASE")
                            .requestedQuantity(new BigDecimal("10.00"))
                            .build()
            ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agentId");
        }

        @Test
        void throws_when_action_type_missing() {
            assertThatThrownBy(() ->
                    AuthorizeRequest.builder()
                            .agentId("agent-1")
                            .requestedQuantity(new BigDecimal("10.00"))
                            .build()
            ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actionType");
        }

        @Test
        void throws_when_requested_quantity_missing() {
            assertThatThrownBy(() ->
                    AuthorizeRequest.builder()
                            .agentId("agent-1")
                            .actionType("PURCHASE")
                            .build()
            ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestedQuantity");
        }

        @Test
        void optional_fields_are_null_when_not_set() {
            AuthorizeRequest req = AuthorizeRequest.builder()
                    .agentId("agent-1")
                    .actionType("PURCHASE")
                    .requestedQuantity(new BigDecimal("10.00"))
                    .build();

            assertThat(req.sessionToken()).isNull();
            assertThat(req.entityId()).isNull();
            assertThat(req.intentContext()).isNull();
            assertThat(req.parentEventId()).isNull();
            assertThat(req.currency()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // CreateBudgetRequest builder validation
    // -------------------------------------------------------------------------

    @Nested
    class CreateBudgetRequestBuilderValidation {

        @Test
        void throws_when_user_id_missing() {
            assertThatThrownBy(() ->
                    CreateBudgetRequest.builder()
                            .totalLimit(new BigDecimal("500"))
                            .currency("USD")
                            .expiresIn("24h")
                            .build()
            ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userId");
        }

        @Test
        void throws_when_neither_currency_nor_unit() {
            assertThatThrownBy(() ->
                    CreateBudgetRequest.builder()
                            .userId("u1")
                            .totalLimit(new BigDecimal("500"))
                            .expiresIn("24h")
                            .build()
            ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
        }

        @Test
        void throws_when_both_currency_and_unit() {
            assertThatThrownBy(() ->
                    CreateBudgetRequest.builder()
                            .userId("u1")
                            .totalLimit(new BigDecimal("500"))
                            .currency("USD")
                            .unit("tokens")
                            .expiresIn("24h")
                            .build()
            ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mutually exclusive");
        }

        @Test
        void throws_when_neither_expires_at_nor_expires_in() {
            assertThatThrownBy(() ->
                    CreateBudgetRequest.builder()
                            .userId("u1")
                            .totalLimit(new BigDecimal("500"))
                            .currency("USD")
                            .build()
            ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expiresAt");
        }
    }
}
