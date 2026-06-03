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
    externalReference: optStr(args, "external_reference"),
    totalLimit: num(args, "total_limit"),
    currency: optStr(args, "currency"),
    unit: optStr(args, "unit"),
    expiresIn: optStr(args, "expires_in") ?? "24h",
    intentContext: optStr(args, "intent_context"),
    anomalyDetectionEnabled: optBool(args, "anomaly_detection_enabled"),
    autoPauseOnAnomaly: optBool(args, "auto_pause_on_anomaly"),
    velocityMaxPerMinute: optNum(args, "velocity_max_per_minute"),
    velocityMaxAmountPerHour: optNum(args, "velocity_max_amount_per_hour"),
    velocityMaxPerDay: optNum(args, "velocity_max_per_day"),
    allocations: (args["allocations"] as Array<Record<string, unknown>> | undefined)?.map(a => ({
      category: a["category"] as string,
      limit: a["limit"] as number,
      enforcementMode: (a["enforcement_mode"] ?? a["enforcementMode"]) as "OPEN" | "CATEGORY_CONSTRAINED" | "STRICT" | "SOFT" | undefined,
      allowedCategories: (a["allowed_categories"] ?? a["allowedCategories"]) as string[] | undefined,
      forbiddenItemTypes: (a["forbidden_item_types"] ?? a["forbiddenItemTypes"]) as string[] | undefined,
    })),
  });

  return {
    budget_id: budget.id,
    session_token: budget.tokens![0].sessionToken!,
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
    // Flat list of valid category strings — pass one of these as claimed_category in figuard_authorize.
    // Do not use synonyms or plural forms; the match must be exact (case-insensitive).
    allocation_categories: budget.allocations.map((a) => a.category),
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
    idempotencyKey: optStr(args, "idempotency_key"),
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
    const nonRetryableCodes = new Set([
      "ALLOCATION_EXHAUSTED", "BUDGET_CANCELLED", "BUDGET_EXPIRED", "BUDGET_EXHAUSTED",
      "INSUFFICIENT_FUNDS", "DELEGATE_CAP_EXCEEDED", "ENTITY_ALREADY_AUTHORIZED",
      "DELEGATION_TOKEN_REVOKED", "FORBIDDEN_ITEM_TYPE", "EXCEEDS_QUANTITY_LIMIT",
    ]);
    const retryable = !nonRetryableCodes.has(result.denialReason ?? "");
    return {
      decision: "DENIED",
      event_id: result.eventId,
      denial_reason: result.denialReason,
      denial_message: result.denialMessage,
      budget_available: result.budgetSnapshot?.availableQuantity,
      retryable,
      next_step: retryable
        ? "Fix the request (see denial_reason and denial_message) and retry."
        : "Do not retry with the same parameters — this denial is not recoverable. Inform the user of the denial reason.",
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

export async function handleExtendBudget(client: FiGuardClient, args: Args): Promise<unknown> {
  const budget = await client.extendBudget({
    budgetId: str(args, "budget_id"),
    expiresIn: optStr(args, "expires_in"),
    expiresAt: optStr(args, "expires_at"),
  });

  return {
    budget_id: budget.id,
    status: budget.status,
    expires_at: budget.expiresAt,
    available: budget.availableQuantity,
    message: "Budget expiry extended. Agent may continue spending until the new expires_at.",
  };
}

export async function handleCreateDelegationToken(client: FiGuardClient, args: Args): Promise<unknown> {
  const caps = args["caps"];
  if (!Array.isArray(caps)) throw new Error("caps is required and must be an array");

  const token = await client.createDelegationToken({
    budgetId: str(args, "budget_id"),
    label: str(args, "label"),
    caps: caps as Array<{ category: string; limit: number }>,
  });

  return {
    token_id: token.id,
    parent_budget_id: token.parentBudgetId,
    label: token.label,
    status: token.status,
    session_token: token.sessionToken,
    session_token_prefix: token.sessionTokenPrefix,
    caps: token.caps.map((c) => ({
      category: c.category,
      total_limit: c.totalLimit,
      available: c.availableQuantity,
    })),
    note: token.sessionToken
      ? "Store session_token securely — it is never returned again. Hand it to the sub-agent immediately."
      : undefined,
  };
}

export async function handleRevokeDelegationToken(client: FiGuardClient, args: Args): Promise<unknown> {
  const token = await client.revokeDelegationToken(str(args, "token_id"));

  return {
    token_id: token.id,
    status: token.status,
    label: token.label,
    revoked_at: token.revokedAt,
    message: "Delegation token revoked. Sub-agent can no longer authorize new spends.",
  };
}

export async function handleGetDelegationToken(client: FiGuardClient, args: Args): Promise<unknown> {
  const token = await client.getDelegationToken(str(args, "token_id"));

  return {
    token_id: token.id,
    parent_budget_id: token.parentBudgetId,
    label: token.label,
    status: token.status,
    session_token_prefix: token.sessionTokenPrefix,
    caps: token.caps.map((c) => ({
      category: c.category,
      total_limit: c.totalLimit,
      spent: c.quantitySpent,
      reserved: c.quantityReserved,
      available: c.availableQuantity,
    })),
    revoked_at: token.revokedAt,
  };
}

export async function handleCancelBatch(client: FiGuardClient, args: Args): Promise<unknown> {
  const budgetIds = args["budget_ids"];
  if (!Array.isArray(budgetIds)) throw new Error("budget_ids is required and must be an array");
  const budgets = await client.cancelBatch(budgetIds as string[]);

  return {
    cancelled: budgets.map((b) => ({ budget_id: b.id, status: b.status })),
    count: budgets.length,
    message: `${budgets.length} budget(s) processed. Already-terminal budgets are included without error.`,
  };
}

export async function handleFundBudget(client: FiGuardClient, args: Args): Promise<unknown> {
  const result = await client.fundBudget({
    budgetId: str(args, "budget_id"),
    operation: str(args, "operation") as "CREDIT" | "DEBIT" | "RESET" | "RESET_SPENT",
    amount: num(args, "amount"),
    reason: optStr(args, "reason"),
  });

  return {
    budget_id: result.budgetId,
    operation: result.operation,
    amount: result.amount,
    previous_total_limit: result.previousTotalLimit,
    total_limit: result.totalLimit,
    quantity_spent: result.quantitySpent,
    available_quantity: result.availableQuantity,
    status: result.status,
    reason: result.reason,
    message: `Budget ${result.operation} applied. New totalLimit: ${result.totalLimit}, available: ${result.availableQuantity}.`,
  };
}
