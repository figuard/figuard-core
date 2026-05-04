package io.figuard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.figuard.exception.FiGuardApiException;
import io.figuard.exception.FiGuardConnectionException;
import io.figuard.model.*;
import okhttp3.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;

/**
 * Synchronous FiGuard API client.
 *
 * <p>Thread-safe: the underlying {@link OkHttpClient} manages a connection pool
 * shared across all calls. Do not create a new instance per request.
 *
 * <pre>{@code
 * FiGuardClient client = FiGuardClient.builder()
 *     .apiKey("ab_live_...")
 *     .build();
 *
 * Budget budget = client.createBudget("user_123", new BigDecimal("500.00"),
 *     "2024-12-31T23:59:59Z");
 *
 * AuthorizationResult result = client.authorize(AuthorizeRequest.builder()
 *     .sessionToken(budget.sessionToken())
 *     .agentId("agent_001")
 *     .actionType("PURCHASE")
 *     .description("NYC flight")
 *     .requestedAmount(new BigDecimal("299.00"))
 *     .idempotencyKey("txn-abc-001")
 *     .build()).raiseIfDenied();
 *
 * client.confirmEvent(result.eventId(), new BigDecimal("299.00"));
 * }</pre>
 */
public final class FiGuardClient {

    private static final Logger log = Logger.getLogger(FiGuardClient.class.getName());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_BASE_MS = 1_000L;

    private final String apiKey;
    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    private FiGuardClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl.replaceAll("/$", "");
        this.http = builder.httpClient != null ? builder.httpClient : new OkHttpClient();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiKey;
        private String baseUrl = "https://api.figuard.io";
        private OkHttpClient httpClient;

        public Builder apiKey(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
            return this;
        }

        /** Override the underlying OkHttpClient (useful for tests with MockWebServer). */
        public Builder httpClient(OkHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public FiGuardClient build() {
            Objects.requireNonNull(apiKey, "apiKey is required");
            return new FiGuardClient(this);
        }
    }

    // -------------------------------------------------------------------------
    // Budget management
    // -------------------------------------------------------------------------

    /**
     * Create a new agent budget.
     *
     * @return {@link Budget} with {@code sessionToken} populated — store it securely.
     *         It is never returned again.
     */
    public Budget createBudget(String userId, BigDecimal totalLimit, String expiresAt) {
        return createBudget(userId, totalLimit, expiresAt, new HashMap<>());
    }

    public Budget createBudget(String userId, BigDecimal totalLimit, String expiresAt,
                               Map<String, Object> extras) {
        Map<String, Object> body = new LinkedHashMap<>(extras);
        body.put("userId", userId);
        body.put("totalLimit", totalLimit);
        body.put("expiresAt", expiresAt);
        body.putIfAbsent("currency", "USD");

        Map<String, Object> data = request("POST", "/api/v1/budgets", body, null, null, true);
        return parseBudget(data);
    }

    /** Fetch the current state of a budget. */
    public Budget getBudget(String budgetId) {
        Map<String, Object> data = request("GET", "/api/v1/budgets/" + budgetId,
                null, null, null, true);
        return parseBudget(data);
    }

    /**
     * Resume a PAUSED budget after anomaly review.
     *
     * @throws FiGuardApiException HTTP 409 if budget is not currently PAUSED.
     */
    public Budget resumeBudget(String budgetId, String overrideReason) {
        return resumeBudget(budgetId, overrideReason, null);
    }

    public Budget resumeBudget(String budgetId, String overrideReason, String overrideBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overrideReason", overrideReason);
        if (overrideBy != null) body.put("overrideBy", overrideBy);

        Map<String, Object> data = request("POST", "/api/v1/budgets/" + budgetId + "/resume",
                body, null, null, true);
        return parseBudget(data);
    }

    /**
     * Issue a new session token. The old token remains valid for a short grace period
     * so in-flight agents finish cleanly.
     *
     * @return The new raw session token.
     */
    public String rotateSessionToken(String budgetId) {
        Map<String, Object> data = request("POST",
                "/api/v1/budgets/" + budgetId + "/rotate-token",
                null, null, null, true);
        return (String) data.get("sessionToken");
    }

    /**
     * Get a shareable public receipt URL for a budget session.
     * The URL requires no authentication and is valid for 90 days.
     * Calling this method multiple times for the same budget returns the same URL.
     */
    public String getReceiptUrl(String budgetId) {
        Map<String, Object> data = request("GET",
                "/api/v1/budgets/" + budgetId + "/receipt",
                null, null, null, true);
        return str(data.get("receiptUrl"));
    }

    // -------------------------------------------------------------------------
    // Authorization
    // -------------------------------------------------------------------------

    /**
     * Pre-flight spend authorization.
     *
     * @param request Authorization parameters. {@code idempotencyKey} is required —
     *                 throws {@link IllegalArgumentException} if omitted or blank.
     * @return {@link AuthorizationResult} — call {@code .raiseIfDenied()} for
     *         exception-driven flow.
     */
    public AuthorizationResult authorize(AuthorizeRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey is required for authorize(). " +
                    "Generate one per logical spend intent and reuse it on retries.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", request.agentId());
        body.put("actionType", request.actionType());
        body.put("description", request.description());
        body.put("requestedAmount", request.requestedAmount());
        body.put("currency", request.currency() != null ? request.currency() : "USD");
        body.put("idempotencyKey", request.idempotencyKey());
        if (request.agentType() != null)      body.put("agentType", request.agentType());
        if (request.intentContext() != null)  body.put("intentContext", request.intentContext());
        if (request.entityId() != null)       body.put("entityId", request.entityId());
        if (request.claimedCategory() != null) body.put("claimedCategory", request.claimedCategory());
        if (request.claimedItemType() != null) body.put("claimedItemType", request.claimedItemType());
        if (request.parentEventId() != null)  body.put("parentEventId", request.parentEventId());

        String token = request.sessionToken();
        String prefix = token.length() >= 8 ? token.substring(0, 8) : "???";
        log.fine(() -> "authorize: agentId=" + request.agentId()
                + " amount=" + request.requestedAmount()
                + " key=" + request.idempotencyKey()
                + " token_prefix=" + prefix);

        Map<String, String> headers = Map.of("X-Session-Token", token);
        Map<String, Object> data = request("POST", "/api/v1/authorize",
                body, headers, null, true);
        return parseAuthorizationResult(data);
    }

    // -------------------------------------------------------------------------
    // Payment lifecycle
    // -------------------------------------------------------------------------

    /** Confirm a previously authorized event — finalizes the spend. */
    public SpendEventResponse confirmEvent(String eventId, BigDecimal confirmedAmount) {
        return confirmEvent(eventId, confirmedAmount, null);
    }

    public SpendEventResponse confirmEvent(String eventId, BigDecimal confirmedAmount,
                                           String externalTransactionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("confirmedAmount", confirmedAmount);
        if (externalTransactionId != null) body.put("externalTransactionId", externalTransactionId);

        Map<String, Object> data = request("POST", "/api/v1/events/" + eventId + "/confirm",
                body, null, null, true);
        return parseSpendEvent(data);
    }

    /** Mark an authorized event as failed — releases the reservation. */
    public SpendEventResponse failEvent(String eventId, String reason) {
        return failEvent(eventId, reason, null);
    }

    public SpendEventResponse failEvent(String eventId, String reason, String errorMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        if (errorMessage != null) body.put("errorMessage", errorMessage);

        Map<String, Object> data = request("POST", "/api/v1/events/" + eventId + "/fail",
                body, null, null, true);
        return parseSpendEvent(data);
    }

    /** Void an authorized event that was never confirmed. */
    public VoidResult voidEvent(String eventId, String reason) {
        return voidEvent(eventId, reason, false);
    }

    public VoidResult voidEvent(String eventId, String reason, boolean voidChildEvents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("voidChildEvents", voidChildEvents);

        Map<String, Object> data = request("POST", "/api/v1/events/" + eventId + "/void",
                body, null, null, true);
        return new VoidResult(parseSpendEvent(data));
    }

    // -------------------------------------------------------------------------
    // Ledger & reporting
    // -------------------------------------------------------------------------

    /** Paginated spend event ledger, newest first. */
    public LedgerPage getLedger(String budgetId) {
        return getLedger(budgetId, 0, 20, null);
    }

    public LedgerPage getLedger(String budgetId, int page, int size, String decision) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", String.valueOf(page));
        params.put("size", String.valueOf(size));
        if (decision != null) params.put("decision", decision);

        Map<String, Object> data = request("GET",
                "/api/v1/budgets/" + budgetId + "/ledger",
                null, null, params, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) data.getOrDefault("content", List.of());
        List<SpendEventResponse> events = content.stream().map(this::parseSpendEvent).toList();

        return new LedgerPage(
                events,
                toLong(data.get("totalElements")),
                toInt(data.get("totalPages")),
                toInt(data.getOrDefault("number", page)),
                toInt(data.getOrDefault("size", size))
        );
    }

    // -------------------------------------------------------------------------
    // Internal HTTP layer
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, String path,
                                         Map<String, Object> jsonBody,
                                         Map<String, String> extraHeaders,
                                         Map<String, String> queryParams,
                                         boolean retryable) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + path).newBuilder();
        if (queryParams != null) {
            queryParams.forEach(urlBuilder::addQueryParameter);
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(urlBuilder.build())
                .header("X-Agent-Budget-Key", apiKey)
                .header("Accept", "application/json");

        if (extraHeaders != null) {
            extraHeaders.forEach(reqBuilder::header);
        }

        RequestBody body = null;
        if (jsonBody != null) {
            try {
                body = RequestBody.create(mapper.writeValueAsString(jsonBody), JSON);
            } catch (Exception e) {
                throw new FiGuardConnectionException("Failed to serialize request body", e);
            }
        }

        switch (method) {
            case "GET"  -> reqBuilder.get();
            case "POST" -> reqBuilder.post(body != null ? body : RequestBody.create(new byte[0], null));
            case "PATCH" -> reqBuilder.patch(body != null ? body : RequestBody.create(new byte[0], null));
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }

        Request req = reqBuilder.build();
        int attempts = retryable ? MAX_RETRIES : 1;
        Exception lastException = null;

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_BACKOFF_BASE_MS * (1L << (attempt - 1)); // 1s, 2s, 4s
                final int attemptCapture = attempt;
                log.fine(() -> "Retry " + attemptCapture + "/" + (attempts - 1)
                        + " for " + method + " " + path + " in " + delay + "ms");
                try { Thread.sleep(delay); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new FiGuardConnectionException("Interrupted during retry backoff", ie);
                }
            }

            try (Response resp = http.newCall(req).execute()) {
                if (resp.code() >= 500 && attempt < attempts - 1) {
                    log.warning("Server error " + resp.code()
                            + " on " + method + " " + path
                            + " (attempt " + (attempt + 1) + "), will retry");
                    lastException = null;
                    continue;
                }
                return handleResponse(resp);

            } catch (IOException e) {
                lastException = e;
                log.warning("Connection error on " + method + " " + path
                        + " (attempt " + (attempt + 1) + "): " + e.getMessage());
            }
        }

        throw new FiGuardConnectionException(
                "All " + attempts + " attempts failed for " + method + " " + path,
                lastException);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleResponse(Response resp) throws IOException {
        String bodyStr = resp.body() != null ? resp.body().string() : "";

        if (resp.code() >= 400) {
            Map<String, Object> raw = null;
            String message = bodyStr;
            try {
                raw = mapper.readValue(bodyStr, Map.class);
                message = (String) raw.getOrDefault("message",
                          raw.getOrDefault("error", bodyStr));
            } catch (Exception ignored) {}
            throw new FiGuardApiException(resp.code(), message, raw);
        }

        if (resp.code() == 204 || bodyStr.isBlank()) {
            return Map.of();
        }

        try {
            return mapper.readValue(bodyStr, Map.class);
        } catch (Exception e) {
            throw new FiGuardConnectionException("Failed to parse response body: " + bodyStr, e);
        }
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Budget parseBudget(Map<String, Object> d) {
        List<Map<String, Object>> rawAllocations =
                (List<Map<String, Object>>) d.getOrDefault("allocations", List.of());

        List<AllocationResponse> allocations = rawAllocations.stream()
                .map(a -> new AllocationResponse(
                        str(a.get("id")),
                        str(a.get("category")),
                        (List<String>) a.getOrDefault("allowedCategories", List.of()),
                        decimal(a.get("limit")),
                        decimal(a.get("amountSpent")),
                        decimal(a.get("amountReserved")),
                        decimal(a.get("availableAmount")),
                        str(a.get("status")),
                        str(a.getOrDefault("enforcementMode", "CATEGORY_CONSTRAINED")),
                        (List<String>) a.get("forbiddenItemTypes")
                ))
                .toList();

        return new Budget(
                str(d.get("id")),
                str(d.get("userId")),
                decimal(d.get("totalLimit")),
                str(d.get("currency")),
                decimal(d.get("amountSpent")),
                decimal(d.get("amountReserved")),
                decimal(d.get("availableAmount")),
                str(d.get("status")),
                str(d.get("expiresAt")),
                str(d.get("createdAt")),
                str(d.get("sessionTokenPrefix")),
                allocations,
                str(d.get("sessionToken"))
        );
    }

    @SuppressWarnings("unchecked")
    private AuthorizationResult parseAuthorizationResult(Map<String, Object> d) {
        BudgetSnapshot budgetSnapshot = null;
        Map<String, Object> bs = (Map<String, Object>) d.get("budgetSnapshot");
        if (bs != null) {
            budgetSnapshot = new BudgetSnapshot(
                    decimal(bs.get("totalLimit")),
                    decimal(bs.get("amountSpent")),
                    decimal(bs.get("amountReserved")),
                    decimal(bs.get("availableAmount")),
                    str(bs.get("status"))
            );
        }

        AllocationSnapshot allocSnapshot = null;
        Map<String, Object> as = (Map<String, Object>) d.get("allocationSnapshot");
        if (as != null) {
            allocSnapshot = new AllocationSnapshot(
                    str(as.get("category")),
                    decimal(as.get("limit")),
                    decimal(as.get("amountSpent")),
                    decimal(as.get("amountReserved")),
                    decimal(as.get("availableAmount")),
                    str(as.get("status"))
            );
        }

        return new AuthorizationResult(
                str(d.get("eventId")),
                str(d.get("decision")),
                decimal(d.get("approvedAmount")),
                str(d.get("authorizedAt")),
                budgetSnapshot,
                allocSnapshot,
                str(d.get("denialReason")),
                str(d.get("denialMessage")),
                str(d.get("originalEventId")),
                str(d.get("originalEventStatus"))
        );
    }

    private SpendEventResponse parseSpendEvent(Map<String, Object> d) {
        return new SpendEventResponse(
                str(d.get("id")),
                str(d.get("decision")),
                decimal(d.get("requestedAmount")),
                str(d.get("currency")),
                str(d.get("createdAt")),
                str(d.get("agentId")),
                str(d.get("agentType")),
                str(d.get("actionType")),
                str(d.get("description")),
                decimal(d.get("confirmedAmount")),
                str(d.get("entityId")),
                str(d.get("claimedCategory")),
                str(d.get("claimedItemType")),
                str(d.get("idempotencyKey")),
                str(d.get("denialReason")),
                str(d.get("failureReason")),
                str(d.get("parentEventId"))
        );
    }

    // -------------------------------------------------------------------------
    // Type coercion helpers
    // -------------------------------------------------------------------------

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return v != null ? Integer.parseInt(v.toString()) : 0;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return v != null ? Long.parseLong(v.toString()) : 0L;
    }
}
