/**
 * Unit tests for MCP server handlers and tool definitions.
 *
 * FiGuardClient is injected as a mock — no running server required.
 */

import {
  handleCreateBudget,
  handleAuthorize,
  handleConfirm,
  handleFail,
  handleVoid,
  handleGetBudget,
  handleGetLedger,
  handleResumeBudget,
} from "../src/handlers";
import { TOOLS } from "../src/tools";

// ---------------------------------------------------------------------------
// Mock FiGuardClient factory
// ---------------------------------------------------------------------------

function makeClient(overrides: Record<string, unknown> = {}): any {
  return {
    createBudget: jest.fn(),
    getBudget: jest.fn(),
    resumeBudget: jest.fn(),
    authorize: jest.fn(),
    confirmEvent: jest.fn(),
    failEvent: jest.fn(),
    voidEvent: jest.fn(),
    getLedger: jest.fn(),
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Shared test data
// ---------------------------------------------------------------------------

const BUDGET_RESPONSE = {
  id: "bgt_abc123",
  userId: "user_1",
  totalLimit: 500,
  currency: "USD",
  unit: undefined,
  quantitySpent: 0,
  quantityReserved: 0,
  availableQuantity: 500,
  status: "ACTIVE",
  expiresAt: "2026-12-31T23:59:59Z",
  sessionToken: "st_abc_secret",
  sessionTokenPrefix: "st_abc",
  allocations: [],
  isActive: true,
  isPaused: false,
  isMonetary: true,
  intentContext: undefined,
  anomalyDetectionEnabled: false,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const AUTH_AUTHORIZED = {
  eventId: "evt_001",
  decision: "AUTHORIZED",
  approvedQuantity: 100,
  isAuthorized: true,
  denialReason: undefined,
  denialMessage: undefined,
  budgetSnapshot: { availableQuantity: 400 },
  allocationSnapshot: { availableQuantity: 300 },
};

const AUTH_DENIED = {
  eventId: "evt_denied",
  decision: "DENIED",
  approvedQuantity: 0,
  isAuthorized: false,
  denialReason: "INSUFFICIENT_FUNDS",
  denialMessage: "Only $50 remaining",
  budgetSnapshot: { availableQuantity: 50 },
  allocationSnapshot: undefined,
};

const EVENT_RESPONSE = {
  id: "evt_001",
  decision: "CONFIRMED",
  agentId: "booking-agent",
  actionType: "PURCHASE",
  description: "Buy item",
  requestedQuantity: 100,
  confirmedQuantity: 97.5,
  claimedCategory: "flights",
  denialReason: undefined,
  createdAt: "2026-01-01T00:00:00Z",
};

const VOIDED_RESPONSE = {
  isVoided: true,
  event: { id: "evt_001", decision: "VOIDED" },
};

const LEDGER_PAGE = {
  events: [EVENT_RESPONSE],
  totalElements: 1,
  page: 0,
  totalPages: 1,
  hasNext: false,
};

// ---------------------------------------------------------------------------
// handleCreateBudget
// ---------------------------------------------------------------------------

describe("handleCreateBudget", () => {
  it("returns correct shape and note", async () => {
    const client = makeClient({ createBudget: jest.fn().mockResolvedValue(BUDGET_RESPONSE) });
    const result: any = await handleCreateBudget(client, {
      user_id: "user_1",
      total_limit: 500,
      currency: "USD",
    });

    expect(result.budget_id).toBe("bgt_abc123");
    expect(result.session_token).toBe("st_abc_secret");
    expect(result.status).toBe("ACTIVE");
    expect(result.total_limit).toBe(500);
    expect(result.available_quantity).toBe(500);
    expect(result.note).toBe("Store session_token securely — it is never returned again.");
  });

  it("passes allocations to client", async () => {
    const client = makeClient({
      createBudget: jest.fn().mockResolvedValue({
        ...BUDGET_RESPONSE,
        allocations: [{ category: "flights", limit: 300, availableQuantity: 300, quantitySpent: 0, quantityReserved: 0, enforcementMode: "CATEGORY_CONSTRAINED", status: "ACTIVE" }],
      }),
    });

    const result: any = await handleCreateBudget(client, {
      user_id: "user_1",
      total_limit: 500,
      allocations: [{ category: "flights", limit: 300 }],
    });

    expect(result.allocations).toHaveLength(1);
    expect(result.allocations[0].category).toBe("flights");
    expect(result.allocations[0].limit).toBe(300);
  });

  it("throws when user_id is missing", async () => {
    const client = makeClient();
    await expect(handleCreateBudget(client, { total_limit: 500 })).rejects.toThrow("user_id");
  });

  it("throws when total_limit is missing", async () => {
    const client = makeClient();
    await expect(handleCreateBudget(client, { user_id: "u1" })).rejects.toThrow("total_limit");
  });
});

// ---------------------------------------------------------------------------
// handleAuthorize
// ---------------------------------------------------------------------------

describe("handleAuthorize", () => {
  const REQUIRED_ARGS = {
    session_token: "st_abc",
    agent_id: "booking-agent",
    action_type: "PURCHASE",
    description: "Buy flight NYC→LAX",
    requested_quantity: 337.50,
    idempotency_key: "idem_001",
  };

  it("AUTHORIZED path — returns event_id and next_step", async () => {
    const client = makeClient({ authorize: jest.fn().mockResolvedValue(AUTH_AUTHORIZED) });
    const result: any = await handleAuthorize(client, REQUIRED_ARGS);

    expect(result.decision).toBe("AUTHORIZED");
    expect(result.event_id).toBe("evt_001");
    expect(result.approved_quantity).toBe(100);
    expect(result.budget_available).toBe(400);
    expect(result.next_step).toMatch(/figuard_confirm/);
  });

  it("DENIED path — returns denial_reason and next_step", async () => {
    const client = makeClient({ authorize: jest.fn().mockResolvedValue(AUTH_DENIED) });
    const result: any = await handleAuthorize(client, REQUIRED_ARGS);

    expect(result.decision).toBe("DENIED");
    expect(result.denial_reason).toBe("INSUFFICIENT_FUNDS");
    expect(result.denial_message).toBe("Only $50 remaining");
    expect(result.next_step).toMatch(/Do not proceed/);
  });

  it("passes optional fields to client.authorize", async () => {
    const client = makeClient({ authorize: jest.fn().mockResolvedValue(AUTH_AUTHORIZED) });
    await handleAuthorize(client, {
      ...REQUIRED_ARGS,
      claimed_category: "flights",
      claimed_item_type: "economy_ticket",
      trace_id: "trace_abc",
      dry_run: true,
    });

    const callArgs = client.authorize.mock.calls[0][0];
    expect(callArgs.claimedCategory).toBe("flights");
    expect(callArgs.claimedItemType).toBe("economy_ticket");
    expect(callArgs.traceId).toBe("trace_abc");
    expect(callArgs.dryRun).toBe(true);
  });

  it("throws when session_token is missing", async () => {
    const client = makeClient();
    const args = { ...REQUIRED_ARGS };
    delete (args as any).session_token;
    await expect(handleAuthorize(client, args)).rejects.toThrow("session_token");
  });

  it("throws when idempotency_key is missing", async () => {
    const client = makeClient();
    const args = { ...REQUIRED_ARGS };
    delete (args as any).idempotency_key;
    await expect(handleAuthorize(client, args)).rejects.toThrow("idempotency_key");
  });
});

// ---------------------------------------------------------------------------
// handleConfirm
// ---------------------------------------------------------------------------

describe("handleConfirm", () => {
  it("returns confirmed shape with message", async () => {
    const client = makeClient({ confirmEvent: jest.fn().mockResolvedValue(EVENT_RESPONSE) });
    const result: any = await handleConfirm(client, { event_id: "evt_001", confirmed_quantity: 97.5 });

    expect(result.decision).toBe("CONFIRMED");
    expect(result.event_id).toBe("evt_001");
    expect(result.confirmed_quantity).toBe(97.5);
    expect(result.message).toMatch(/confirmed/i);
  });

  it("throws when event_id is missing", async () => {
    const client = makeClient();
    await expect(handleConfirm(client, { confirmed_quantity: 50 })).rejects.toThrow("event_id");
  });

  it("passes external_transaction_id when provided", async () => {
    const client = makeClient({ confirmEvent: jest.fn().mockResolvedValue(EVENT_RESPONSE) });
    await handleConfirm(client, {
      event_id: "evt_001",
      confirmed_quantity: 97.5,
      external_transaction_id: "ch_stripe_abc",
    });

    expect(client.confirmEvent.mock.calls[0][0].externalTransactionId).toBe("ch_stripe_abc");
  });
});

// ---------------------------------------------------------------------------
// handleFail
// ---------------------------------------------------------------------------

describe("handleFail", () => {
  const FAILED_EVENT = { ...EVENT_RESPONSE, decision: "FAILED" };

  it("returns failure shape with message", async () => {
    const client = makeClient({ failEvent: jest.fn().mockResolvedValue(FAILED_EVENT) });
    const result: any = await handleFail(client, { event_id: "evt_001", reason: "PAYMENT_DECLINED" });

    expect(result.decision).toBe("FAILED");
    expect(result.event_id).toBe("evt_001");
    expect(result.message).toMatch(/released/i);
  });

  it("throws when reason is missing", async () => {
    const client = makeClient();
    await expect(handleFail(client, { event_id: "evt_001" })).rejects.toThrow("reason");
  });

  it("passes error_message when provided", async () => {
    const client = makeClient({ failEvent: jest.fn().mockResolvedValue(FAILED_EVENT) });
    await handleFail(client, {
      event_id: "evt_001",
      reason: "API_ERROR",
      error_message: "gateway timeout",
    });

    expect(client.failEvent.mock.calls[0][0].errorMessage).toBe("gateway timeout");
  });
});

// ---------------------------------------------------------------------------
// handleVoid
// ---------------------------------------------------------------------------

describe("handleVoid", () => {
  it("returns voided shape with is_voided and message", async () => {
    const client = makeClient({ voidEvent: jest.fn().mockResolvedValue(VOIDED_RESPONSE) });
    const result: any = await handleVoid(client, { event_id: "evt_001", reason: "USER_CANCELLED" });

    expect(result.decision).toBe("VOIDED");
    expect(result.event_id).toBe("evt_001");
    expect(result.is_voided).toBe(true);
    expect(result.message).toMatch(/voided/i);
  });

  it("passes void_child_events when true", async () => {
    const client = makeClient({ voidEvent: jest.fn().mockResolvedValue(VOIDED_RESPONSE) });
    await handleVoid(client, { event_id: "evt_001", reason: "PLAN_CHANGED", void_child_events: true });

    expect(client.voidEvent.mock.calls[0][0].voidChildEvents).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// handleGetBudget
// ---------------------------------------------------------------------------

describe("handleGetBudget", () => {
  it("returns full budget shape including allocations", async () => {
    const client = makeClient({
      getBudget: jest.fn().mockResolvedValue({
        ...BUDGET_RESPONSE,
        quantitySpent: 100,
        quantityReserved: 50,
        availableQuantity: 350,
        allocations: [
          {
            category: "flights",
            limit: 300,
            quantitySpent: 100,
            quantityReserved: 50,
            availableQuantity: 150,
            status: "ACTIVE",
          },
        ],
      }),
    });

    const result: any = await handleGetBudget(client, { budget_id: "bgt_abc123" });

    expect(result.budget_id).toBe("bgt_abc123");
    expect(result.spent).toBe(100);
    expect(result.reserved).toBe(50);
    expect(result.available).toBe(350);
    expect(result.is_active).toBe(true);
    expect(result.is_paused).toBe(false);
    expect(result.allocations).toHaveLength(1);
    expect(result.allocations[0].category).toBe("flights");
    expect(result.allocations[0].available).toBe(150);
  });

  it("throws when budget_id is missing", async () => {
    const client = makeClient();
    await expect(handleGetBudget(client, {})).rejects.toThrow("budget_id");
  });
});

// ---------------------------------------------------------------------------
// handleGetLedger
// ---------------------------------------------------------------------------

describe("handleGetLedger", () => {
  it("returns mapped events and pagination fields", async () => {
    const client = makeClient({ getLedger: jest.fn().mockResolvedValue(LEDGER_PAGE) });
    const result: any = await handleGetLedger(client, { budget_id: "bgt_abc123" });

    expect(result.events).toHaveLength(1);
    expect(result.events[0].event_id).toBe("evt_001");
    expect(result.events[0].decision).toBe("CONFIRMED");
    expect(result.events[0].confirmed_quantity).toBe(97.5);
    expect(result.total_events).toBe(1);
    expect(result.total_pages).toBe(1);
    expect(result.has_next).toBe(false);
  });

  it("passes page and size to client", async () => {
    const client = makeClient({ getLedger: jest.fn().mockResolvedValue(LEDGER_PAGE) });
    await handleGetLedger(client, { budget_id: "bgt_abc123", page: 2, size: 50 });

    const callArgs = client.getLedger.mock.calls[0][0];
    expect(callArgs.page).toBe(2);
    expect(callArgs.size).toBe(50);
  });

  it("defaults to page 0, size 20 when not specified", async () => {
    const client = makeClient({ getLedger: jest.fn().mockResolvedValue(LEDGER_PAGE) });
    await handleGetLedger(client, { budget_id: "bgt_abc123" });

    const callArgs = client.getLedger.mock.calls[0][0];
    expect(callArgs.page).toBe(0);
    expect(callArgs.size).toBe(20);
  });
});

// ---------------------------------------------------------------------------
// handleResumeBudget
// ---------------------------------------------------------------------------

describe("handleResumeBudget", () => {
  it("returns resumed budget shape with message", async () => {
    const client = makeClient({
      resumeBudget: jest.fn().mockResolvedValue({ ...BUDGET_RESPONSE, status: "ACTIVE" }),
    });

    const result: any = await handleResumeBudget(client, {
      budget_id: "bgt_abc123",
      override_reason: "Reviewed — legitimate purchase",
    });

    expect(result.budget_id).toBe("bgt_abc123");
    expect(result.status).toBe("ACTIVE");
    expect(result.message).toMatch(/resumed/i);
  });

  it("throws when override_reason is missing", async () => {
    const client = makeClient();
    await expect(handleResumeBudget(client, { budget_id: "bgt_abc123" })).rejects.toThrow("override_reason");
  });

  it("passes override_by when provided", async () => {
    const client = makeClient({
      resumeBudget: jest.fn().mockResolvedValue({ ...BUDGET_RESPONSE, status: "ACTIVE" }),
    });

    await handleResumeBudget(client, {
      budget_id: "bgt_abc123",
      override_reason: "Reviewed",
      override_by: "ops-team",
    });

    expect(client.resumeBudget.mock.calls[0][0].overrideBy).toBe("ops-team");
  });
});

// ---------------------------------------------------------------------------
// TOOLS definitions
// ---------------------------------------------------------------------------

describe("TOOLS definitions", () => {
  const EXPECTED_TOOLS = [
    "figuard_create_budget",
    "figuard_authorize",
    "figuard_confirm",
    "figuard_fail",
    "figuard_void",
    "figuard_get_budget",
    "figuard_get_ledger",
    "figuard_resume_budget",
  ];

  it("exports exactly 8 tools", () => {
    expect(TOOLS).toHaveLength(8);
  });

  it("has all expected tool names", () => {
    const names = TOOLS.map((t) => t.name);
    for (const expected of EXPECTED_TOOLS) {
      expect(names).toContain(expected);
    }
  });

  it("every tool has a non-empty description", () => {
    for (const tool of TOOLS) {
      expect(tool.description.trim().length).toBeGreaterThan(0);
    }
  });

  it("every tool has an inputSchema with type object", () => {
    for (const tool of TOOLS) {
      expect((tool.inputSchema as any).type).toBe("object");
      expect((tool.inputSchema as any).properties).toBeDefined();
    }
  });

  it("figuard_create_budget requires user_id and total_limit", () => {
    const tool = TOOLS.find((t) => t.name === "figuard_create_budget")!;
    expect((tool.inputSchema as any).required).toContain("user_id");
    expect((tool.inputSchema as any).required).toContain("total_limit");
  });

  it("figuard_authorize requires session_token, agent_id, idempotency_key", () => {
    const tool = TOOLS.find((t) => t.name === "figuard_authorize")!;
    const required = (tool.inputSchema as any).required;
    expect(required).toContain("session_token");
    expect(required).toContain("agent_id");
    expect(required).toContain("idempotency_key");
  });

  it("figuard_confirm requires event_id and confirmed_quantity", () => {
    const tool = TOOLS.find((t) => t.name === "figuard_confirm")!;
    const required = (tool.inputSchema as any).required;
    expect(required).toContain("event_id");
    expect(required).toContain("confirmed_quantity");
  });

  it("figuard_resume_budget requires budget_id and override_reason", () => {
    const tool = TOOLS.find((t) => t.name === "figuard_resume_budget")!;
    const required = (tool.inputSchema as any).required;
    expect(required).toContain("budget_id");
    expect(required).toContain("override_reason");
  });
});
