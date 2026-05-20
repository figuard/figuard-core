/**
 * Unit tests for FiGuardClient.
 * All HTTP calls are intercepted via global fetch mock — no server required.
 */

import { FiGuardClient, resolveExpiresAt } from "../src/client";
import { FiGuardApiError, FiGuardConnectionError, FiGuardDeniedException } from "../src/errors";

// ---------------------------------------------------------------------------
// fetch mock helpers
// ---------------------------------------------------------------------------

function mockFetch(status: number, body: unknown): jest.Mock {
  const mock = jest.fn().mockResolvedValue({
    status,
    ok: status < 400,
    headers: { get: () => null },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(String(body)),
  });
  global.fetch = mock as unknown as typeof fetch;
  return mock;
}

function mockFetchNetworkError(message = "Network error"): jest.Mock {
  const mock = jest.fn().mockRejectedValue(new Error(message));
  global.fetch = mock as unknown as typeof fetch;
  return mock;
}

// ---------------------------------------------------------------------------
// Test data
// ---------------------------------------------------------------------------

const BUDGET_RESPONSE = {
  id: "bgt_abc123",
  userId: "user_1",
  totalLimit: 500,
  currency: "USD",
  quantitySpent: 0,
  quantityReserved: 0,
  availableQuantity: 500,
  status: "ACTIVE",
  expiresAt: "2026-12-31T23:59:59Z",
  tokens: [
    {
      category: "default",
      sessionToken: "st_abc_secret",
      sessionTokenPrefix: "st_abc",
      unit: null,
      currency: "USD",
    },
  ],
  allocations: [],
};

const AUTH_AUTHORIZED = {
  eventId: "evt_001",
  decision: "AUTHORIZED",
  approvedQuantity: 299,
  authorizedAt: "2026-05-10T10:00:00Z",
  budgetSnapshot: {
    totalLimit: 500,
    quantitySpent: 0,
    quantityReserved: 299,
    availableQuantity: 201,
    status: "ACTIVE",
  },
};

const AUTH_DENIED = {
  eventId: "evt_002",
  decision: "DENIED",
  denialReason: "INSUFFICIENT_FUNDS",
  denialMessage: "Not enough budget remaining",
};

const SPEND_EVENT = {
  id: "evt_001",
  decision: "CONFIRMED",
  requestedQuantity: 299,
  confirmedQuantity: 297,
  createdAt: "2026-05-10T10:00:00Z",
};

// ---------------------------------------------------------------------------
// createBudget
// ---------------------------------------------------------------------------

describe("createBudget", () => {
  const client = new FiGuardClient({ apiKey: "fg_live_test" });

  it("returns a Budget with isActive and tokens", async () => {
    mockFetch(200, BUDGET_RESPONSE);
    const budget = await client.createBudget({
      userId: "user_1",
      totalLimit: 500,
      expiresIn: "24h",
      currency: "USD",
    });

    expect(budget.id).toBe("bgt_abc123");
    expect(budget.isActive).toBe(true);
    expect(budget.isPaused).toBe(false);
    expect(budget.isMonetary).toBe(true);
    expect(budget.tokens?.[0]?.sessionToken).toBe("st_abc_secret");
    expect(budget.tokens?.[0]?.category).toBe("default");
  });

  it("sends correct request body", async () => {
    const mock = mockFetch(200, BUDGET_RESPONSE);
    await client.createBudget({
      userId: "user_1",
      totalLimit: 500,
      expiresIn: "24h",
      currency: "USD",
      anomalyDetectionEnabled: true,
    });

    const body = JSON.parse((mock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.userId).toBe("user_1");
    expect(body.totalLimit).toBe(500);
    expect(body.currency).toBe("USD");
    expect(body.anomalyDetectionEnabled).toBe(true);
    expect(body.expiresAt).toBeDefined();
  });

  it("throws FiGuardApiError on 400", async () => {
    mockFetch(400, { message: "Invalid currency" });
    await expect(
      client.createBudget({ userId: "u", totalLimit: 100, expiresIn: "1h" }),
    ).rejects.toThrow(FiGuardApiError);
  });

  it("sends velocity params in request body when provided", async () => {
    const mock = mockFetch(200, BUDGET_RESPONSE);
    await client.createBudget({
      userId: "user_1",
      totalLimit: 500,
      expiresIn: "24h",
      currency: "USD",
      velocityMaxPerMinute: 10,
      velocityMaxAmountPerHour: 250.0,
      velocityMaxPerDay: 50,
    });

    const body = JSON.parse((mock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.velocityMaxPerMinute).toBe(10);
    expect(body.velocityMaxAmountPerHour).toBe(250.0);
    expect(body.velocityMaxPerDay).toBe(50);
  });

  it("parses velocity fields from response JSON", async () => {
    mockFetch(200, {
      ...BUDGET_RESPONSE,
      velocityMaxPerMinute: 5,
      velocityMaxAmountPerHour: 100.0,
      velocityMaxPerDay: 20,
    });
    const budget = await client.createBudget({
      userId: "user_1",
      totalLimit: 500,
      expiresIn: "24h",
      currency: "USD",
    });

    expect(budget.velocityMaxPerMinute).toBe(5);
    expect(budget.velocityMaxAmountPerHour).toBe(100.0);
    expect(budget.velocityMaxPerDay).toBe(20);
  });

  it("omits velocity fields from body when not provided", async () => {
    const mock = mockFetch(200, BUDGET_RESPONSE);
    await client.createBudget({
      userId: "user_1",
      totalLimit: 500,
      expiresIn: "24h",
      currency: "USD",
    });

    const body = JSON.parse((mock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.velocityMaxPerMinute).toBeUndefined();
    expect(body.velocityMaxAmountPerHour).toBeUndefined();
    expect(body.velocityMaxPerDay).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// authorize
// ---------------------------------------------------------------------------

describe("authorize", () => {
  const client = new FiGuardClient({ apiKey: "fg_live_test" });

  it("returns isAuthorized=true for AUTHORIZED decision", async () => {
    mockFetch(200, AUTH_AUTHORIZED);
    const result = await client.authorize({
      sessionToken: "st_abc_secret",
      agentId: "agent_1",
      actionType: "PURCHASE",
      description: "Flight",
      requestedQuantity: 299,
      idempotencyKey: "key-001",
    });

    expect(result.isAuthorized).toBe(true);
    expect(result.eventId).toBe("evt_001");
    expect(result.budgetSnapshot?.availableQuantity).toBe(201);
  });

  it("returns isAuthorized=false for DENIED decision", async () => {
    mockFetch(200, AUTH_DENIED);
    const result = await client.authorize({
      sessionToken: "st_abc_secret",
      agentId: "agent_1",
      actionType: "PURCHASE",
      description: "Flight",
      requestedQuantity: 999,
      idempotencyKey: "key-002",
    });

    expect(result.isAuthorized).toBe(false);
    expect(result.denialReason).toBe("INSUFFICIENT_FUNDS");
  });

  it("raiseIfDenied throws FiGuardDeniedException when denied", async () => {
    mockFetch(200, AUTH_DENIED);
    const result = await client.authorize({
      sessionToken: "st_abc_secret",
      agentId: "agent_1",
      actionType: "PURCHASE",
      description: "Flight",
      requestedQuantity: 999,
      idempotencyKey: "key-003",
    });

    expect(() => result.raiseIfDenied()).toThrow(FiGuardDeniedException);
    expect(() => result.raiseIfDenied()).toThrow("INSUFFICIENT_FUNDS");
  });

  it("raiseIfDenied returns result when authorized (fluent chaining)", async () => {
    mockFetch(200, AUTH_AUTHORIZED);
    const result = await client.authorize({
      sessionToken: "st_abc_secret",
      agentId: "agent_1",
      actionType: "PURCHASE",
      description: "Flight",
      requestedQuantity: 299,
      idempotencyKey: "key-004",
    });

    expect(result.raiseIfDenied()).toBe(result);
  });

  it("auto-generates idempotencyKey when blank", async () => {
    mockFetch(200, AUTH_AUTHORIZED);
    const result = await client.authorize({
      sessionToken: "st_abc_secret",
      agentId: "agent_1",
      actionType: "PURCHASE",
      description: "Flight",
      requestedQuantity: 299,
      idempotencyKey: "   ", // blank — SDK should auto-generate a UUID
    });
    expect(result.isAuthorized).toBe(true);
  });

  it("auto-generates idempotencyKey when omitted", async () => {
    mockFetch(200, AUTH_AUTHORIZED);
    const result = await client.authorize({
      sessionToken: "st_abc_secret",
      agentId: "agent_1",
      actionType: "PURCHASE",
      description: "Flight",
      requestedQuantity: 299,
      // idempotencyKey intentionally omitted
    });
    expect(result.isAuthorized).toBe(true);
  });

  it("sends X-Session-Token header", async () => {
    const mock = mockFetch(200, AUTH_AUTHORIZED);
    await client.authorize({
      sessionToken: "st_abc_secret",
      agentId: "agent_1",
      actionType: "PURCHASE",
      description: "Flight",
      requestedQuantity: 299,
      idempotencyKey: "key-005",
    });

    const headers = (mock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(headers["X-Session-Token"]).toBe("st_abc_secret");
    expect(headers["X-Agent-Budget-Key"]).toBe("fg_live_test");
  });
});

// ---------------------------------------------------------------------------
// confirmEvent / failEvent / voidEvent
// ---------------------------------------------------------------------------

describe("payment lifecycle", () => {
  const client = new FiGuardClient({ apiKey: "fg_live_test" });

  it("confirmEvent returns SpendEventResponse", async () => {
    mockFetch(200, SPEND_EVENT);
    const event = await client.confirmEvent({ eventId: "evt_001", confirmedQuantity: 297 });
    expect(event.id).toBe("evt_001");
    expect(event.decision).toBe("CONFIRMED");
    expect(event.confirmedQuantity).toBe(297);
  });

  it("voidEvent returns isVoided=true", async () => {
    mockFetch(200, { ...SPEND_EVENT, decision: "VOIDED" });
    const result = await client.voidEvent({ eventId: "evt_001", reason: "USER_CANCELLED" });
    expect(result.isVoided).toBe(true);
  });

  it("failEvent returns SpendEventResponse", async () => {
    mockFetch(200, { ...SPEND_EVENT, decision: "FAILED" });
    const event = await client.failEvent({ eventId: "evt_001", reason: "PAYMENT_DECLINED" });
    expect(event.decision).toBe("FAILED");
  });
});

// ---------------------------------------------------------------------------
// getLedger
// ---------------------------------------------------------------------------

describe("getLedger", () => {
  const client = new FiGuardClient({ apiKey: "fg_live_test" });

  it("returns LedgerPage with hasNext computed correctly", async () => {
    mockFetch(200, {
      content: [SPEND_EVENT],
      totalElements: 5,
      totalPages: 3,
      number: 0,
      size: 2,
    });
    const page = await client.getLedger({ budgetId: "bgt_abc123" });
    expect(page.events.length).toBe(1);
    expect(page.totalPages).toBe(3);
    expect(page.hasNext).toBe(true); // page 0 < totalPages-1 (2)
  });

  it("hasNext is false on last page", async () => {
    mockFetch(200, {
      content: [],
      totalElements: 5,
      totalPages: 3,
      number: 2,
      size: 2,
    });
    const page = await client.getLedger({ budgetId: "bgt_abc123", page: 2 });
    expect(page.hasNext).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Retry logic
// ---------------------------------------------------------------------------

describe("retry behavior", () => {
  it("retries on 5xx and succeeds on second attempt", async () => {
    let call = 0;
    global.fetch = jest.fn().mockImplementation(() => {
      call++;
      if (call === 1) {
        return Promise.resolve({
          status: 503,
          ok: false,
          headers: { get: () => null },
          json: () => Promise.resolve({ message: "Service unavailable" }),
          text: () => Promise.resolve("Service unavailable"),
        });
      }
      return Promise.resolve({
        status: 200,
        ok: true,
        headers: { get: () => null },
        json: () => Promise.resolve(BUDGET_RESPONSE),
      });
    }) as unknown as typeof fetch;

    const client = new FiGuardClient({ apiKey: "fg_live_test" });
    const budget = await client.getBudget("bgt_abc123");
    expect(budget.id).toBe("bgt_abc123");
    expect(call).toBe(2);
  });

  it("throws FiGuardConnectionError after all retries exhausted", async () => {
    mockFetchNetworkError("ECONNREFUSED");
    const client = new FiGuardClient({ apiKey: "fg_live_test" });
    await expect(client.getBudget("bgt_abc123")).rejects.toThrow(FiGuardConnectionError);
  });

  it("does not retry on 4xx", async () => {
    const mock = mockFetch(404, { message: "Budget not found" });
    const client = new FiGuardClient({ apiKey: "fg_live_test" });
    await expect(client.getBudget("bgt_notfound")).rejects.toThrow(FiGuardApiError);
    expect(mock).toHaveBeenCalledTimes(1);
  });
});

// ---------------------------------------------------------------------------
// resolveExpiresAt
// ---------------------------------------------------------------------------

describe("resolveExpiresAt", () => {
  it("passes through expiresAt unchanged", () => {
    expect(resolveExpiresAt("2026-12-31T23:59:59Z")).toBe("2026-12-31T23:59:59Z");
  });

  it("resolves expiresIn in hours", () => {
    const before = Date.now();
    const result = resolveExpiresAt(undefined, "2h");
    const after = Date.now();
    const ts = new Date(result).getTime();
    // Allow 1s of rounding (ISO string strips milliseconds)
    expect(ts).toBeGreaterThanOrEqual(before + 2 * 3600 * 1000 - 1000);
    expect(ts).toBeLessThanOrEqual(after + 2 * 3600 * 1000 + 1000);
  });

  it("resolves expiresIn in days", () => {
    const result = resolveExpiresAt(undefined, "7d");
    const ts = new Date(result).getTime();
    expect(ts).toBeGreaterThan(Date.now() + 6 * 24 * 3600 * 1000);
  });

  it("resolves expiresIn in minutes", () => {
    const result = resolveExpiresAt(undefined, "30m");
    const ts = new Date(result).getTime();
    expect(ts).toBeGreaterThan(Date.now() + 29 * 60 * 1000);
  });

  it("resolves expiresIn as number (seconds)", () => {
    const result = resolveExpiresAt(undefined, 3600);
    const ts = new Date(result).getTime();
    expect(ts).toBeGreaterThan(Date.now() + 3599 * 1000);
  });

  it("throws when both expiresAt and expiresIn are provided", () => {
    expect(() => resolveExpiresAt("2026-12-31T00:00:00Z", "1h")).toThrow(
      "Pass either expiresAt or expiresIn, not both.",
    );
  });

  it("throws when neither expiresAt nor expiresIn are provided", () => {
    expect(() => resolveExpiresAt(undefined, undefined)).toThrow("Either expiresAt or expiresIn is required.");
  });

  it("throws on invalid expiresIn format", () => {
    expect(() => resolveExpiresAt(undefined, "2x")).toThrow('Invalid expiresIn: "2x"');
  });
});
