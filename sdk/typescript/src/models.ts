/**
 * Typed data-transfer objects returned by FiGuardClient.
 *
 * All models are plain interfaces — immutable, no ORM dependency.
 * Field names follow TypeScript camelCase convention.
 */

import { FiGuardDeniedException } from "./errors";

// ---------------------------------------------------------------------------
// Budget
// ---------------------------------------------------------------------------

export interface AllocationResponse {
  readonly id: string;
  readonly category: string;
  readonly allowedCategories: string[];
  readonly limit: number;
  readonly quantitySpent: number;
  readonly quantityReserved: number;
  readonly availableQuantity: number;
  readonly status: string;
  readonly enforcementMode: string;
  readonly forbiddenItemTypes?: string[];
}

/**
 * A single session token entry within the Budget.tokens list.
 *
 * For simple budgets, category is "default". For entitlement-backed budgets
 * there is one entry per entitlement item (future).
 *
 * Convenience pattern for the common single-token case:
 *   const token = budget.tokens?.[0];  // primaryToken equivalent
 */
export interface BudgetToken {
  readonly category: string;
  readonly sessionToken?: string;
  readonly sessionTokenPrefix?: string;
  readonly unit?: string;
  readonly currency?: string;
}

export interface Budget {
  readonly id: string;
  readonly userId: string;
  readonly totalLimit: number;
  readonly quantitySpent: number;
  readonly quantityReserved: number;
  readonly availableQuantity: number;
  readonly status: string;
  readonly expiresAt: string;
  /** Set for monetary budgets (ISO 4217 currency code). */
  readonly currency?: string;
  /** Set for resource budgets (e.g. "tokens", "api_calls"). */
  readonly unit?: string;
  readonly createdAt?: string;
  readonly intentContext?: string;
  readonly intentTags?: string[];
  readonly externalReference?: string;
  readonly softLimit?: number;
  readonly maxTransactionQuantity?: number;
  readonly authorizationExpirySeconds?: number;
  readonly velocityMaxPerMinute?: number;
  readonly velocityMaxAmountPerHour?: number;
  readonly velocityMaxPerDay?: number;
  readonly allocations: AllocationResponse[];
  readonly cancelledAt?: string;
  readonly metadata?: Record<string, unknown>;
  /**
   * Only present immediately after createBudget(). Null/undefined on all subsequent reads.
   * For simple budgets: one entry with category="default".
   * For entitlement-backed budgets: one entry per entitlement item (future).
   *
   * Use `primaryToken` for the common single-token case.
   */
  readonly tokens?: BudgetToken[];
  /**
   * Shortcut for `tokens?.[0]` — the session token to hand to an agent after `createBudget()`.
   * Undefined on all reads after the initial create response (tokens are one-time secrets).
   *
   * ```typescript
   * const budget = await client.createBudget({ ... });
   * const token = budget.primaryToken?.sessionToken;
   * ```
   */
  readonly primaryToken?: BudgetToken;
  /** True when status === "ACTIVE". */
  readonly isActive: boolean;
  /** True when status === "PAUSED". */
  readonly isPaused: boolean;
  /** True for currency-based budgets; false for resource budgets. */
  readonly isMonetary: boolean;
}

// ---------------------------------------------------------------------------
// Authorization
// ---------------------------------------------------------------------------

export interface BudgetSnapshot {
  readonly totalLimit: number;
  readonly quantitySpent: number;
  readonly quantityReserved: number;
  readonly availableQuantity: number;
  readonly status: string;
}

export interface AllocationSnapshot {
  readonly category: string;
  readonly limit: number;
  readonly quantitySpent: number;
  readonly quantityReserved: number;
  readonly availableQuantity: number;
  readonly status: string;
}

export interface AuthorizationResult {
  readonly eventId: string;
  readonly decision: string;
  readonly budgetSnapshot?: BudgetSnapshot;
  readonly allocationSnapshot?: AllocationSnapshot;
  readonly approvedQuantity?: number;
  readonly authorizedAt?: string;
  readonly denialReason?: string;
  readonly denialMessage?: string;
  /** Set when denialReason === "ENTITY_ALREADY_AUTHORIZED". */
  readonly originalEventId?: string;
  readonly originalEventStatus?: string;
  /** True when decision is AUTHORIZED. */
  readonly isAuthorized: boolean;
  /**
   * True when FiGuard was unreachable and `failOpen: true` caused a synthetic approval.
   * No ledger entry was created. `confirmEvent/failEvent/voidEvent` are no-ops for this eventId.
   */
  readonly isFallback?: boolean;
  /**
   * Throw FiGuardDeniedException if denied.
   * Returns this if authorized, enabling fluent chaining:
   *   const result = (await client.authorize(...)).raiseIfDenied();
   */
  raiseIfDenied(): AuthorizationResult;
}

// ---------------------------------------------------------------------------
// Spend events
// ---------------------------------------------------------------------------

export interface SpendEventResponse {
  readonly id: string;
  readonly decision: string;
  readonly requestedQuantity: number;
  readonly createdAt: string;
  readonly agentId?: string;
  readonly agentType?: string;
  readonly actionType?: string;
  readonly description?: string;
  readonly confirmedQuantity?: number;
  readonly currency?: string;
  readonly entityId?: string;
  readonly claimedCategory?: string;
  readonly claimedItemType?: string;
  readonly intentContext?: string;
  readonly idempotencyKey?: string;
  readonly denialReason?: string;
  readonly failureReason?: string;
  readonly parentEventId?: string;
  readonly traceId?: string;
  readonly metadata?: Record<string, unknown>;
  /** Set only on external events recorded via recordExternalEvent(). Undefined for standard events. */
  readonly eventSource?: string;
  /** When the action actually occurred. Set only on external events. */
  readonly occurredAt?: string;
}

// ---------------------------------------------------------------------------
// Void
// ---------------------------------------------------------------------------

export interface VoidResult {
  readonly event: SpendEventResponse;
  readonly isVoided: boolean;
}

// ---------------------------------------------------------------------------
// Void tree
// ---------------------------------------------------------------------------

export interface VoidTreeResult {
  readonly rootEventId: string;
  /** Total events voided: root + all authorized descendants. */
  readonly voidedCount: number;
  /** Sum of requestedQuantity across all voided events. */
  readonly totalQuantityReleased: number;
  /** Undefined for unit-based (resource) budgets. */
  readonly currency?: string;
  /** Root first, then descendants in BFS order. */
  readonly voidedEventIds: string[];
  readonly reason: string;
}

export function makeVoidTreeResult(data: Record<string, unknown>): VoidTreeResult {
  return {
    rootEventId: data["rootEventId"] as string,
    voidedCount: data["voidedCount"] as number,
    totalQuantityReleased: data["totalQuantityReleased"] as number,
    currency: data["currency"] as string | undefined,
    voidedEventIds: (data["voidedEventIds"] as string[]) ?? [],
    reason: data["reason"] as string,
  };
}

// ---------------------------------------------------------------------------
// Ledger
// ---------------------------------------------------------------------------

export interface LedgerPage {
  readonly events: SpendEventResponse[];
  readonly totalElements: number;
  readonly totalPages: number;
  readonly page: number;
  readonly size: number;
  readonly hasNext: boolean;
}

// ---------------------------------------------------------------------------
// Spend tree
// ---------------------------------------------------------------------------

export interface SpendTreeNode {
  readonly event: SpendEventResponse;
  readonly children: SpendTreeNode[];
}

export interface SpendTree {
  readonly budgetId: string;
  readonly roots: SpendTreeNode[];
  readonly totalEvents: number;
}

// ---------------------------------------------------------------------------
// Delegation tokens
// ---------------------------------------------------------------------------

export interface DelegationTokenAllocation {
  readonly id: string;
  readonly category: string;
  readonly totalLimit: number;
  readonly quantitySpent: number;
  readonly quantityReserved: number;
  readonly availableQuantity: number;
}

export interface DelegationToken {
  readonly id: string;
  readonly parentBudgetId: string;
  readonly label: string;
  readonly status: string;
  readonly sessionTokenPrefix: string;
  readonly caps: DelegationTokenAllocation[];
  /**
   * Only present immediately after createDelegationToken(). Undefined on all subsequent reads.
   * Hand this to the sub-agent immediately; it is never returned again.
   */
  readonly sessionToken?: string;
  readonly revokedAt?: string;
  readonly createdAt?: string;
  /** True when status === "ACTIVE". */
  readonly isActive: boolean;
  /** True when status === "REVOKED". */
  readonly isRevoked: boolean;
}

// ---------------------------------------------------------------------------
// Internal factory functions (build model objects with computed properties)
// ---------------------------------------------------------------------------

export function makeBudget(data: Record<string, unknown>): Budget {
  const allocations = ((data["allocations"] as Record<string, unknown>[] | undefined) ?? []).map(
    (a) =>
      ({
        id: a["id"] as string,
        category: a["category"] as string,
        allowedCategories: (a["allowedCategories"] as string[] | undefined) ?? [],
        limit: a["limit"] as number,
        quantitySpent: a["quantitySpent"] as number,
        quantityReserved: a["quantityReserved"] as number,
        availableQuantity: a["availableQuantity"] as number,
        status: a["status"] as string,
        enforcementMode: (a["enforcementMode"] as string | undefined) ?? "CATEGORY_CONSTRAINED",
        forbiddenItemTypes: a["forbiddenItemTypes"] as string[] | undefined,
      }) as AllocationResponse,
  );

  const currency = (data["currency"] as string | undefined) ?? undefined;
  const status = data["status"] as string;

  const rawTokens = data["tokens"] as Record<string, unknown>[] | undefined | null;
  const tokens: BudgetToken[] | undefined = rawTokens
    ? rawTokens.map((t) => ({
        category: t["category"] as string,
        sessionToken: (t["sessionToken"] as string | undefined) ?? undefined,
        sessionTokenPrefix: (t["sessionTokenPrefix"] as string | undefined) ?? undefined,
        unit: (t["unit"] as string | undefined) ?? undefined,
        currency: (t["currency"] as string | undefined) ?? undefined,
      }))
    : undefined;

  return {
    id: data["id"] as string,
    userId: data["userId"] as string,
    totalLimit: data["totalLimit"] as number,
    currency,
    unit: (data["unit"] as string | undefined) ?? undefined,
    quantitySpent: data["quantitySpent"] as number,
    quantityReserved: data["quantityReserved"] as number,
    availableQuantity: data["availableQuantity"] as number,
    status,
    expiresAt: data["expiresAt"] as string,
    createdAt: (data["createdAt"] as string | undefined) ?? undefined,
    intentContext: (data["intentContext"] as string | undefined) ?? undefined,
    intentTags: (data["intentTags"] as string[] | undefined) ?? undefined,
    externalReference: (data["externalReference"] as string | undefined) ?? undefined,
    softLimit: (data["softLimit"] as number | undefined) ?? undefined,
    maxTransactionQuantity: (data["maxTransactionQuantity"] as number | undefined) ?? undefined,
    authorizationExpirySeconds: (data["authorizationExpirySeconds"] as number | undefined) ?? undefined,
    velocityMaxPerMinute: (data["velocityMaxPerMinute"] as number | undefined) ?? undefined,
    velocityMaxAmountPerHour: (data["velocityMaxAmountPerHour"] as number | undefined) ?? undefined,
    velocityMaxPerDay: (data["velocityMaxPerDay"] as number | undefined) ?? undefined,
    allocations,
    cancelledAt: (data["cancelledAt"] as string | undefined) ?? undefined,
    metadata: (data["metadata"] as Record<string, unknown> | undefined) ?? undefined,
    tokens,
    primaryToken: tokens?.[0],
    isActive: status === "ACTIVE",
    isPaused: status === "PAUSED",
    isMonetary: typeof currency === "string" && currency.trim().length > 0,
  };
}

export function makeAuthorizationResult(data: Record<string, unknown>): AuthorizationResult {
  const snap = data["budgetSnapshot"] as Record<string, unknown> | undefined;
  const budgetSnapshot: BudgetSnapshot | undefined = snap
    ? {
        totalLimit: snap["totalLimit"] as number,
        quantitySpent: snap["quantitySpent"] as number,
        quantityReserved: snap["quantityReserved"] as number,
        availableQuantity: snap["availableQuantity"] as number,
        status: snap["status"] as string,
      }
    : undefined;

  const asnap = data["allocationSnapshot"] as Record<string, unknown> | undefined;
  const allocationSnapshot: AllocationSnapshot | undefined = asnap
    ? {
        category: asnap["category"] as string,
        limit: asnap["limit"] as number,
        quantitySpent: asnap["quantitySpent"] as number,
        quantityReserved: asnap["quantityReserved"] as number,
        availableQuantity: asnap["availableQuantity"] as number,
        status: asnap["status"] as string,
      }
    : undefined;

  const decision = data["decision"] as string;
  const denialReason = coerceString(data["denialReason"]);
  const denialMessage = (data["denialMessage"] as string | undefined) ?? undefined;
  const originalEventId = (data["originalEventId"] as string | undefined) ?? undefined;
  const originalEventStatus = (data["originalEventStatus"] as string | undefined) ?? undefined;
  const eventId = data["eventId"] as string;
  const approvedQuantity = (data["approvedQuantity"] as number | undefined) ?? undefined;
  const authorizedAt = (data["authorizedAt"] as string | undefined) ?? undefined;

  const result: AuthorizationResult = {
    eventId,
    decision,
    budgetSnapshot,
    allocationSnapshot,
    approvedQuantity,
    authorizedAt,
    denialReason,
    denialMessage,
    originalEventId,
    originalEventStatus,
    isAuthorized: decision === "AUTHORIZED",
    raiseIfDenied() {
      if (decision !== "AUTHORIZED") {
        throw new FiGuardDeniedException(
          denialReason ?? "UNKNOWN",
          denialMessage,
          originalEventId,
        );
      }
      return result;
    },
  };

  return result;
}

export function makeSpendEvent(data: Record<string, unknown>): SpendEventResponse {
  return {
    id: data["id"] as string,
    decision: data["decision"] as string,
    requestedQuantity: data["requestedQuantity"] as number,
    createdAt: data["createdAt"] as string,
    agentId: (data["agentId"] as string | undefined) ?? undefined,
    agentType: (data["agentType"] as string | undefined) ?? undefined,
    actionType: (data["actionType"] as string | undefined) ?? undefined,
    description: (data["description"] as string | undefined) ?? undefined,
    confirmedQuantity: (data["confirmedQuantity"] as number | undefined) ?? undefined,
    currency: (data["currency"] as string | undefined) ?? undefined,
    entityId: (data["entityId"] as string | undefined) ?? undefined,
    claimedCategory: (data["claimedCategory"] as string | undefined) ?? undefined,
    claimedItemType: (data["claimedItemType"] as string | undefined) ?? undefined,
    intentContext: (data["intentContext"] as string | undefined) ?? undefined,
    idempotencyKey: (data["idempotencyKey"] as string | undefined) ?? undefined,
    denialReason: coerceString(data["denialReason"]),
    failureReason: (data["failureReason"] as string | undefined) ?? undefined,
    parentEventId: (data["parentEventId"] as string | undefined) ?? undefined,
    traceId: (data["traceId"] as string | undefined) ?? undefined,
    eventSource: (data["eventSource"] as string | undefined) ?? undefined,
    occurredAt: (data["occurredAt"] as string | undefined) ?? undefined,
    metadata: (data["metadata"] as Record<string, unknown> | undefined) ?? undefined,
  };
}

export function makeSpendTreeNode(data: Record<string, unknown>): SpendTreeNode {
  if ("event" in data) {
    return {
      event: makeSpendEvent(data["event"] as Record<string, unknown>),
      children: ((data["children"] as Record<string, unknown>[] | undefined) ?? []).map(makeSpendTreeNode),
    };
  }
  return {
    event: makeSpendEvent(data),
    children: ((data["children"] as Record<string, unknown>[] | undefined) ?? []).map(makeSpendTreeNode),
  };
}

export function makeDelegationToken(data: Record<string, unknown>): DelegationToken {
  const caps = ((data["caps"] as Record<string, unknown>[] | undefined) ?? []).map(
    (c) => ({
      id: c["id"] as string,
      category: c["category"] as string,
      totalLimit: c["totalLimit"] as number,
      quantitySpent: c["quantitySpent"] as number,
      quantityReserved: c["quantityReserved"] as number,
      availableQuantity: c["availableQuantity"] as number,
    }) as DelegationTokenAllocation,
  );

  const status = data["status"] as string;
  return {
    id: data["id"] as string,
    parentBudgetId: data["parentBudgetId"] as string,
    label: data["label"] as string,
    status,
    sessionTokenPrefix: data["sessionTokenPrefix"] as string,
    caps,
    sessionToken: (data["sessionToken"] as string | undefined) ?? undefined,
    revokedAt: (data["revokedAt"] as string | undefined) ?? undefined,
    createdAt: (data["createdAt"] as string | undefined) ?? undefined,
    isActive: status === "ACTIVE",
    isRevoked: status === "REVOKED",
  };
}

// ---------------------------------------------------------------------------
// Fund budget
// ---------------------------------------------------------------------------

export interface BudgetFundingResult {
  readonly budgetId: string;
  /** CREDIT | DEBIT | RESET | RESET_SPENT */
  readonly operation: string;
  readonly amount: number;
  readonly previousTotalLimit: number;
  readonly totalLimit: number;
  readonly quantitySpent: number;
  readonly quantityReserved: number;
  readonly availableQuantity: number;
  readonly status: string;
  readonly reason?: string;
  readonly updatedAt?: string;
  readonly traceId?: string;
}

export function makeBudgetFundingResult(data: Record<string, unknown>): BudgetFundingResult {
  return {
    budgetId: data["budgetId"] as string,
    operation: data["operation"] as string,
    amount: data["amount"] as number,
    previousTotalLimit: data["previousTotalLimit"] as number,
    totalLimit: data["totalLimit"] as number,
    quantitySpent: data["quantitySpent"] as number,
    quantityReserved: data["quantityReserved"] as number,
    availableQuantity: data["availableQuantity"] as number,
    status: data["status"] as string,
    reason: data["reason"] as string | undefined,
    updatedAt: data["updatedAt"] as string | undefined,
    traceId: data["traceId"] as string | undefined,
  };
}

// ---------------------------------------------------------------------------
// API keys
// ---------------------------------------------------------------------------

export interface ApiKey {
  readonly id: string;
  readonly keyPrefix: string;
  readonly active: boolean;
  readonly description?: string;
  readonly createdAt?: string;
  readonly lastUsedAt?: string;
  /** fg_live_... — returned ONCE at creation/rotation. undefined on all subsequent reads. */
  readonly rawKey?: string;
}

export function makeApiKey(data: Record<string, unknown>): ApiKey {
  return {
    id: data["id"] as string,
    keyPrefix: data["keyPrefix"] as string,
    active: data["active"] as boolean,
    description: data["description"] as string | undefined,
    createdAt: data["createdAt"] as string | undefined,
    lastUsedAt: data["lastUsedAt"] as string | undefined,
    rawKey: data["rawKey"] as string | undefined,
  };
}

// ---------------------------------------------------------------------------
// Subscriptions & Entitlements
// ---------------------------------------------------------------------------

export interface EntitlementItem {
  readonly id: string;
  readonly category: string;
  readonly periodLimit: number;
  /** BLOCK | WARN_ONLY */
  readonly overagePolicy: string;
  /** MONTHLY | QUARTERLY | ANNUALLY */
  readonly renewalPeriod: string;
  readonly currentPeriodConsumed: number;
  readonly currentPeriodReserved: number;
  /** NORMAL | APPROACHING | LIMIT_REACHED */
  readonly state: string;
  readonly warnAtPercentage?: number;
  readonly nextRenewalAt?: string;
  readonly createdAt?: string;
}

export interface Subscription {
  readonly id: string;
  readonly externalSubscriberId: string;
  readonly plan: string;
  /** ACTIVE | PAUSED | CANCELLED */
  readonly status: string;
  readonly renewalPeriod: string;
  readonly entitlements: EntitlementItem[];
  readonly startsAt?: string;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

export function makeEntitlementItem(data: Record<string, unknown>): EntitlementItem {
  return {
    id: data["id"] as string,
    category: data["category"] as string,
    periodLimit: data["periodLimit"] as number,
    overagePolicy: data["overagePolicy"] as string,
    renewalPeriod: data["renewalPeriod"] as string,
    currentPeriodConsumed: (data["currentPeriodConsumed"] as number) ?? 0,
    currentPeriodReserved: (data["currentPeriodReserved"] as number) ?? 0,
    state: (data["state"] as string) ?? "NORMAL",
    warnAtPercentage: data["warnAtPercentage"] as number | undefined,
    nextRenewalAt: data["nextRenewalAt"] as string | undefined,
    createdAt: data["createdAt"] as string | undefined,
  };
}

export function makeSubscription(data: Record<string, unknown>): Subscription {
  const entitlements = ((data["entitlements"] as Record<string, unknown>[]) ?? []).map(
    makeEntitlementItem,
  );
  return {
    id: data["id"] as string,
    externalSubscriberId: data["externalSubscriberId"] as string,
    plan: data["plan"] as string,
    status: data["status"] as string,
    renewalPeriod: data["renewalPeriod"] as string,
    entitlements,
    startsAt: data["startsAt"] as string | undefined,
    createdAt: data["createdAt"] as string | undefined,
    updatedAt: data["updatedAt"] as string | undefined,
  };
}

// ---------------------------------------------------------------------------
// Denial reason constants
// ---------------------------------------------------------------------------

/**
 * String constants for every denial reason code returned by FiGuard.
 *
 * Use instead of raw strings for IDE autocomplete and typo protection:
 *
 * ```typescript
 * import { DenialReason } from "figuard";
 *
 * if (result.denialReason === DenialReason.BUDGET_EXHAUSTED) { ... }
 * ```
 *
 * All values match the literal strings in `AuthorizationResult.denialReason`
 * and `FiGuardDeniedException.denialReason`.
 */
export const DenialReason = {
  // Budget-level
  /** Total budget has no remaining capacity. `availableQuantity` is 0. */
  BUDGET_EXHAUSTED: "BUDGET_EXHAUSTED",
  /** Budget has passed its expiry time. Create a new budget to continue. */
  BUDGET_EXPIRED: "BUDGET_EXPIRED",
  /** Budget was manually paused or paused by anomaly detection. Resume with `resumeBudget()`. */
  BUDGET_PAUSED: "BUDGET_PAUSED",
  /** Budget was cancelled. Create a new budget to continue. */
  BUDGET_CANCELLED: "BUDGET_CANCELLED",

  // Category / allocation
  /** A specific category allocation has no remaining capacity. Total budget may still have funds. */
  ALLOCATION_EXHAUSTED: "ALLOCATION_EXHAUSTED",
  /** `claimedCategory` required (STRICT mode) but not provided or didn't match any allocation. */
  MISSING_CLAIMED_CATEGORY: "MISSING_CLAIMED_CATEGORY",

  // Velocity
  /** Too many requests within the configured velocity window. Retry after the window resets. */
  VELOCITY_LIMIT_EXCEEDED: "VELOCITY_LIMIT_EXCEEDED",

  // Idempotency / entity
  /**
   * The `entityId` supplied already has an active reservation.
   * Check `result.originalEventId` to confirm or void the existing event.
   */
  ENTITY_ALREADY_AUTHORIZED: "ENTITY_ALREADY_AUTHORIZED",

  // Session / token
  /** Token doesn't exist, has expired, or belongs to a different tenant. */
  INVALID_SESSION_TOKEN: "INVALID_SESSION_TOKEN",

  // Causal chain
  /** Causal chain total exceeded the `maxSubtreeQuantity` ceiling on the root event. */
  SUBTREE_CAP_EXCEEDED: "SUBTREE_CAP_EXCEEDED",

  // Subscription
  /** The subscription linked to this budget is paused. Resume the subscription to continue. */
  SUBSCRIPTION_PAUSED: "SUBSCRIPTION_PAUSED",
} as const;

/** Union type of all valid denial reason strings. */
export type DenialReasonCode = typeof DenialReason[keyof typeof DenialReason];

/** Coerce a Spring enum (string or {name, ordinal} object) to a plain string. */
function coerceString(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === "string") return value;
  if (typeof value === "object" && value !== null && "name" in value) {
    return (value as Record<string, unknown>)["name"] as string;
  }
  return String(value);
}
