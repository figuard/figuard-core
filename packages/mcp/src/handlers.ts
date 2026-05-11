/**
 * Tool handlers — each function maps one MCP tool call to one FiGuardClient method.
 * Returns a plain object that index.ts wraps in MCP content blocks.
 */

import { FiGuardClient } from "figuard";

// ---------------------------------------------------------------------------
// Argument types (loosely typed — MCP passes Record<string,unknown>)
// ---------------------------------------------------------------------------

type Args = Record<string, unknown>;

function str(args: Args, key: string): string {
  const v = args[key];
  if (typeof v !== "string" || !v.trim()) throw new Error(`${key} is required and must be a non-empty string`);
  return v;
}

function num(args: Args, key: string): number {
  const v = args[key];
  if (typeof v !== "number") throw new Error(`${key} is required and must be a number`);
  return v;
}

function optStr(args: Args, key: string): string | undefined {
  const v = args[key];
  return typeof v === "string" && v.trim() ? v : undefined;
}

function optNum(args: Args, key: string): number | undefined {
  const v = args[key];
  return typeof v === "number" ? v : undefined;
}

function optBool(args: Args, key: string): boolean | undefined {
  const v = args[key];
  return typeof v === "boolean" ? v : undefined;
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

export async function handleCreateBudget(client: FiGuardClient, args: Args): Promise<unknown> {
  const budget = await client.createBudget({
    userId: str(args, "user_id"),
    totalLimit: num(args, "total_limit"),
    currency: optStr(args, "currency"),
    unit: optStr(args, "unit"),
    expiresIn: optStr(args, "expires_in") ?? "24h",
    intentContext: optStr(args, "intent_context"),
    anomalyDetectionEnabled: optBool(args, "anomaly_detection_enabled"),
    allocations: args["allocations"] as Array<{
      category: string;
      limit: number;
      enforcementMode?: "OPEN" | "CATEGORY_CONSTRAINED" | "STRICT" | "SOFT";
      allowedCategories?: string[];
      forbiddenItemTypes?: string[];
    }> | undefined,
  });

  return {
    budget_id: budget.id,
    session_token: budget.sessionToken,
    status: budget.status,
    total_limit: budget.totalLimit,
    currency: budget.currency,
    unit: budget.unit,
    available_quantity: budget.availableQuantity,
    expires_at: budget.expiresAt,
    allocations: budget.allocations.map((a) => ({
      category: a.category,
      limit: a.limit,
      available: a.availableQuantity,
      enforcement_mode: a.enforcementMode,
    })),
    note: "Store session_token securely — it is never returned again.",
  };
}

export async function handleAuthorize(client: FiGuardClient, args: Args): Promise<unknown> {
  const result = await client.authorize({
    sessionToken: str(args, "session_token"),
    agentId: str(args, "agent_id"),
    actionType: str(args, "action_type"),
    description: str(args, "description"),
    requestedQuantity: num(args, "requested_quantity"),
    idempotencyKey: str(args, "idempotency_key"),
    claimedCategory: optStr(args, "claimed_category"),
    claimedItemType: optStr(args, "claimed_item_type"),
    parentEventId: optStr(args, "parent_event_id"),
    traceId: optStr(args, "trace_id"),
    dryRun: optBool(args, "dry_run"),
  });

  if (result.isAuthorized) {
    return {
      decision: "AUTHORIZED",
      event_id: result.eventId,
      approved_quantity: result.approvedQuantity,
      budget_available: result.budgetSnapshot?.availableQuantity,
      allocation_available: result.allocationSnapshot?.availableQuantity,
      next_step: "Proceed with the action, then call figuard_confirm with the actual amount.",
    };
  } else {
    return {
      decision: "DENIED",
      event_id: result.eventId,
      denial_reason: result.denialReason,
      denial_message: result.denialMessage,
      budget_available: result.budgetSnapshot?.availableQuantity,
      next_step: "Do not proceed with the action. Inform the user of the denial reason.",
    };
  }
}

export async function handleConfirm(client: FiGuardClient, args: Args): Promise<unknown> {
  const event = await client.confirmEvent({
    eventId: str(args, "event_id"),
    confirmedQuantity: num(args, "confirmed_quantity"),
    externalTransactionId: optStr(args, "external_transaction_id"),
  });

  return {
    decision: event.decision,
    event_id: event.id,
    confirmed_quantity: event.confirmedQuantity,
    message: "Spend confirmed. Budget updated with actual amount.",
  };
}

export async function handleFail(client: FiGuardClient, args: Args): Promise<unknown> {
  const event = await client.failEvent({
    eventId: str(args, "event_id"),
    reason: str(args, "reason"),
    errorMessage: optStr(args, "error_message"),
  });

  return {
    decision: event.decision,
    event_id: event.id,
    message: "Spend marked as failed. Reserved funds released back to budget.",
  };
}

export async function handleVoid(client: FiGuardClient, args: Args): Promise<unknown> {
  const result = await client.voidEvent({
    eventId: str(args, "event_id"),
    reason: str(args, "reason"),
    voidChildEvents: optBool(args, "void_child_events"),
  });

  return {
    decision: result.event.decision,
    event_id: result.event.id,
    is_voided: result.isVoided,
    message: "Reservation voided. Reserved funds released back to budget.",
  };
}

export async function handleGetBudget(client: FiGuardClient, args: Args): Promise<unknown> {
  const budget = await client.getBudget(str(args, "budget_id"));

  return {
    budget_id: budget.id,
    status: budget.status,
    total_limit: budget.totalLimit,
    spent: budget.quantitySpent,
    reserved: budget.quantityReserved,
    available: budget.availableQuantity,
    currency: budget.currency,
    unit: budget.unit,
    expires_at: budget.expiresAt,
    is_active: budget.isActive,
    is_paused: budget.isPaused,
    allocations: budget.allocations.map((a) => ({
      category: a.category,
      limit: a.limit,
      spent: a.quantitySpent,
      reserved: a.quantityReserved,
      available: a.availableQuantity,
      status: a.status,
    })),
  };
}

export async function handleGetLedger(client: FiGuardClient, args: Args): Promise<unknown> {
  const page = await client.getLedger({
    budgetId: str(args, "budget_id"),
    page: optNum(args, "page") ?? 0,
    size: optNum(args, "size") ?? 20,
    decision: optStr(args, "decision"),
    traceId: optStr(args, "trace_id"),
  });

  return {
    events: page.events.map((e) => ({
      event_id: e.id,
      decision: e.decision,
      agent_id: e.agentId,
      action_type: e.actionType,
      description: e.description,
      requested_quantity: e.requestedQuantity,
      confirmed_quantity: e.confirmedQuantity,
      claimed_category: e.claimedCategory,
      denial_reason: e.denialReason,
      created_at: e.createdAt,
    })),
    total_events: page.totalElements,
    page: page.page,
    total_pages: page.totalPages,
    has_next: page.hasNext,
  };
}

export async function handleResumeBudget(client: FiGuardClient, args: Args): Promise<unknown> {
  const budget = await client.resumeBudget({
    budgetId: str(args, "budget_id"),
    overrideReason: str(args, "override_reason"),
    overrideBy: optStr(args, "override_by"),
  });

  return {
    budget_id: budget.id,
    status: budget.status,
    available: budget.availableQuantity,
    message: "Budget resumed. Agent may now authorize spends again.",
  };
}
