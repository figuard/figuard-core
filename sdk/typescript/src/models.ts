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

export interface Budget {
  readonly id: string;
  readonly userId: string;
  readonly totalLimit: number;
  readonly quantitySpent: number;
  readonly quantityReserved: number;
  readonly availableQuantity: number;
  readonly status: string;
  readonly expiresAt: string;
  readonly sessionTokenPrefix: string;
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
  readonly allocations: AllocationResponse[];
  readonly cancelledAt?: string;
  readonly metadata?: Record<string, unknown>;
  /**
   * Only present immediately after createBudget(). Undefined on all subsequent reads.
   * Store this securely — it is never returned again.
   */
  readonly sessionToken?: string;
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
}

// ---------------------------------------------------------------------------
// Void
// ---------------------------------------------------------------------------

export interface VoidResult {
  readonly event: SpendEventResponse;
  readonly isVoided: boolean;
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
    sessionTokenPrefix: data["sessionTokenPrefix"] as string,
    intentContext: (data["intentContext"] as string | undefined) ?? undefined,
    intentTags: (data["intentTags"] as string[] | undefined) ?? undefined,
    externalReference: (data["externalReference"] as string | undefined) ?? undefined,
    softLimit: (data["softLimit"] as number | undefined) ?? undefined,
    maxTransactionQuantity: (data["maxTransactionQuantity"] as number | undefined) ?? undefined,
    authorizationExpirySeconds: (data["authorizationExpirySeconds"] as number | undefined) ?? undefined,
    allocations,
    cancelledAt: (data["cancelledAt"] as string | undefined) ?? undefined,
    metadata: (data["metadata"] as Record<string, unknown> | undefined) ?? undefined,
    sessionToken: (data["sessionToken"] as string | undefined) ?? undefined,
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

/** Coerce a Spring enum (string or {name, ordinal} object) to a plain string. */
function coerceString(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === "string") return value;
  if (typeof value === "object" && value !== null && "name" in value) {
    return (value as Record<string, unknown>)["name"] as string;
  }
  return String(value);
}
