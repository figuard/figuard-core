package scripts;

/**
 * FiGuard Java SDK smoke test / demo scenario.
 *
 * Runs a complete authorize → confirm → fail → void → ledger → receipt flow
 * against a live FiGuard instance. Requires the service to be running.
 *
 * Compile and run:
 *   cd sdk/java
 *   mvn package -q
 *   javac -cp target/figuard-java-0.1.0.jar:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
 *         ../../scripts/DemoScenario.java -d /tmp/demo
 *   java -cp /tmp/demo:target/figuard-java-0.1.0.jar:... scripts.DemoScenario [baseUrl] [apiKey]
 *
 * Or simply run the Python demo (demo.py) which is easier for local smoke testing.
 */

import io.figuard.FiGuardClient;
import io.figuard.exception.FiGuardDeniedException;
import io.figuard.model.*;

import java.math.BigDecimal;
import java.util.UUID;

public class DemoScenario {

    static void section(String title) {
        System.out.println("\n" + "─".repeat(60));
        System.out.println("  " + title);
        System.out.println("─".repeat(60));
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        String apiKey  = args.length > 1 ? args[1] : "ab_live_integrationtest";

        FiGuardClient client = FiGuardClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        // ── 1. Create budget ──────────────────────────────────────────────────
        section("1. Create budget ($500 for travel agent)");
        Budget budget = client.createBudget("user_demo_java_001", new BigDecimal("500.00"),
                "2027-12-31T23:59:59Z");
        System.out.printf("  Budget ID     : %s%n", budget.id());
        System.out.printf("  Status        : %s%n", budget.status());
        System.out.printf("  Available     : %s %s%n", budget.currency(), budget.availableAmount());
        System.out.printf("  Session token : %s... (truncated)%n",
                budget.sessionToken().substring(0, 12));

        String sessionToken = budget.sessionToken();
        String budgetId = budget.id();

        // ── 2. Four authorizations ────────────────────────────────────────────
        section("2. Authorize four spend events");

        AuthorizationResult flight = authorize(client, sessionToken,
                "NYC round-trip flight (JFK–SFO)", "289.00");
        AuthorizationResult hotel = authorize(client, sessionToken,
                "3 nights Marriott Midtown", "210.00");
        AuthorizationResult taxi = authorize(client, sessionToken,
                "JFK airport taxi", "42.00");
        authorize(client, sessionToken,
                "Michelin dinner (over budget)", "180.00");  // will be denied

        // ── 3. Confirm the flight ─────────────────────────────────────────────
        section("3. Confirm flight at final price");
        if (flight != null && flight.isAuthorized()) {
            SpendEventResponse evt = client.confirmEvent(flight.eventId(), new BigDecimal("285.50"));
            System.out.printf("  Confirmed  eventId=%s  amount=$285.50%n", evt.id());
        }

        // ── 4. Confirm the hotel ──────────────────────────────────────────────
        section("4. Confirm hotel at final price");
        if (hotel != null && hotel.isAuthorized()) {
            SpendEventResponse evt = client.confirmEvent(hotel.eventId(), new BigDecimal("210.00"));
            System.out.printf("  Confirmed  eventId=%s  amount=$210.00%n", evt.id());
        }

        // ── 5. Fail the taxi ─────────────────────────────────────────────────
        section("5. Fail taxi authorization (payment gateway error)");
        if (taxi != null && taxi.isAuthorized()) {
            SpendEventResponse evt = client.failEvent(taxi.eventId(),
                    "PAYMENT_GATEWAY_ERROR", "Stripe returned HTTP 402");
            System.out.printf("  Failed     eventId=%s  reason=%s%n", evt.id(), evt.failureReason());
        }

        // ── 6. Re-authorize and void the taxi ────────────────────────────────
        section("6. Re-authorize taxi, then void (agent cancelled trip)");
        AuthorizationResult taxi2 = authorize(client, sessionToken, "JFK airport taxi (retry)", "42.00");
        if (taxi2 != null && taxi2.isAuthorized()) {
            VoidResult result = client.voidEvent(taxi2.eventId(), "TASK_CANCELLED");
            System.out.printf("  Voided     eventId=%s  isVoided=%s%n",
                    result.event().id(), result.isVoided());
        }

        // ── 7. Ledger ─────────────────────────────────────────────────────────
        section("7. Ledger (all events, newest first)");
        LedgerPage page = client.getLedger(budgetId, 0, 10, null);
        System.out.printf("  Total events : %d%n", page.totalElements());
        for (SpendEventResponse e : page.events()) {
            System.out.printf("  %-12s  $%10s  %s%n",
                    e.decision(),
                    e.requestedAmount(),
                    truncate(e.description(), 40));
        }

        // ── 8. Budget state ───────────────────────────────────────────────────
        section("8. Budget state after scenario");
        Budget b = client.getBudget(budgetId);
        System.out.printf("  Status    : %s%n", b.status());
        System.out.printf("  Spent     : %s %s%n", b.currency(), b.amountSpent());
        System.out.printf("  Reserved  : %s %s%n", b.currency(), b.amountReserved());
        System.out.printf("  Available : %s %s%n", b.currency(), b.availableAmount());

        // ── 9. Receipt ────────────────────────────────────────────────────────
        section("9. Shareable receipt URL");
        String receiptUrl = client.getReceiptUrl(budgetId);
        System.out.printf("  Receipt: %s%n", receiptUrl);

        section("✓ Demo complete — all steps passed");
    }

    private static AuthorizationResult authorize(FiGuardClient client,
                                                  String sessionToken,
                                                  String description,
                                                  String amount) {
        try {
            AuthorizationResult result = client.authorize(AuthorizeRequest.builder()
                    .sessionToken(sessionToken)
                    .agentId("agent_travel_bot_java")
                    .actionType("PURCHASE")
                    .description(description)
                    .requestedAmount(new BigDecimal(amount))
                    .idempotencyKey(UUID.randomUUID().toString())
                    .build());

            String status = result.isAuthorized()
                    ? "✓ AUTHORIZED"
                    : "✗ DENIED (" + result.denialReason() + ")";
            System.out.printf("  %-30s  $%8s  %s%n", status, amount, description);
            return result;
        } catch (FiGuardDeniedException e) {
            System.out.printf("  ✗ DENIED (%s)  $%8s  %s%n", e.getDenialReason(), amount, description);
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
