// ------------------------------------------------------------
// Enum mirror — must stay in sync with server-side Java enums.
// ------------------------------------------------------------

export type SpendDecision =
  | "AUTHORIZED"
  | "CONFIRMED"
  | "FAILED"
  | "DENIED"
  | "VOIDED";

export type DenialCode =
  | "MISSING_SESSION_TOKEN"
  | "INVALID_SESSION_TOKEN"
  | "TENANT_MISMATCH"
  | "CURRENCY_MISMATCH"
  | "MISSING_CLAIMED_CATEGORY"
  | "NO_MATCHING_ALLOCATION"
  | "FORBIDDEN_ITEM_TYPE"
  | "INSUFFICIENT_FUNDS"
  | "ALLOCATION_EXHAUSTED"
  | "BUDGET_EXHAUSTED"
  | "BUDGET_PAUSED"
  | "BUDGET_EXPIRED"
  | "BUDGET_CANCELLED"
  | "DUPLICATE_REQUEST"
  | "INVALID_PARENT_EVENT"
  | "CAUSAL_CYCLE_DETECTED"
  | "CAUSAL_CHAIN_TOO_DEEP"
  | "EXCEEDS_QUANTITY_LIMIT"
  | "INTENT_SCOPE_VIOLATION"
  | "ANOMALY_DETECTED"
  | "ENTITY_ALREADY_AUTHORIZED";

export type BudgetStatus =
  | "ACTIVE"
  | "PAUSED"
  | "EXHAUSTED"
  | "CANCELLED"
  | "EXPIRED";

export type AllocationStatus = "ACTIVE" | "EXHAUSTED" | "PAUSED";

export type EnforcementMode = "OPEN" | "CATEGORY_CONSTRAINED" | "STRICT";

// ------------------------------------------------------------
// Response shapes — mirroring Java DTOs
// ------------------------------------------------------------

export interface AllocationResponse {
  id: string;
  category: string;
  allowedCategories: string[] | null;
  forbiddenItemTypes: string[] | null;
  enforcementMode: EnforcementMode;
  limit: number;
  quantitySpent: number;
  quantityReserved: number;
  availableQuantity: number;
  status: AllocationStatus;
}

export interface BudgetResponse {
  id: string;
  userId: string;
  externalReference: string | null;
  intentContext: string | null;
  intentTags: string[] | null;
  sessionToken: string | null;
  sessionTokenPrefix: string | null;
  totalLimit: number;
  maxTransactionQuantity: number | null;
  currency: string;
  unit: string | null;
  quantitySpent: number;
  quantityReserved: number;
  availableQuantity: number;
  softLimit: number | null;
  authorizationExpirySeconds: number | null;
  status: BudgetStatus;
  allocations: AllocationResponse[] | null;
  expiresAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
  metadata: Record<string, unknown> | null;
}

export interface SpendEventResponse {
  id: string;
  decision: SpendDecision;
  agentId: string;
  agentType: string | null;
  actionType: string;
  description: string | null;
  requestedQuantity: number;
  confirmedQuantity: number | null;
  currency: string;
  entityId: string | null;
  claimedCategory: string | null;
  claimedItemType: string | null;
  intentContext: string | null;
  idempotencyKey: string | null;
  denialReason: DenialCode | null;
  failureReason: string | null;
  parentEventId: string | null;
  traceId: string | null;
  createdAt: string;
  metadata: Record<string, unknown> | null;
}

// Spring Data Page response shape
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  numberOfElements: number;
}

export interface SpendTreeNodeResponse {
  id: string;
  decision: SpendDecision;
  agentId: string;
  agentType: string | null;
  actionType: string;
  description: string | null;
  requestedQuantity: number;
  confirmedQuantity: number | null;
  currency: string;
  entityId: string | null;
  claimedCategory: string | null;
  claimedItemType: string | null;
  intentContext: string | null;
  idempotencyKey: string | null;
  denialReason: DenialCode | null;
  failureReason: string | null;
  parentEventId: string | null;
  createdAt: string;
  metadata: Record<string, unknown> | null;
  children: SpendTreeNodeResponse[] | null;
}

export interface SpendTreeResponse {
  budgetId: string;
  totalAuthorized: number;
  totalConfirmed: number;
  totalEvents: number;
  roots: SpendTreeNodeResponse[];
}

// ------------------------------------------------------------
// Replay types
// ------------------------------------------------------------

export interface ReplayAllocationState {
  category: string;
  limit: number;
  quantitySpent: number;
  quantityReserved: number;
  available: number;
  enforcementMode: string;
}

export interface ReplayBudgetState {
  snapshotAt: string;
  eventIndex: number;
  triggeringEventId: string | null;
  totalLimit: number;
  quantitySpent: number;
  quantityReserved: number;
  available: number;
  budgetStatus: string;
  allocations: ReplayAllocationState[];
}

export interface ReplayEventDetail {
  eventId: string;
  agentId: string;
  actionType: string;
  description: string;
  requestedQuantity: number;
  confirmedQuantity: number | null;
  currency: string | null;
  claimedCategory: string | null;
  decision: SpendDecision;
  denialReason: string | null;
  parentEventId: string | null;
  delegatedTokenId: string | null;
  createdAt: string;
  confirmedAt: string | null;
  millisSincePrevious: number;
}

export interface ReplayFrame {
  eventIndex: number;
  event: ReplayEventDetail;
  stateAfter: ReplayBudgetState | null;
}

export interface ReplaySummary {
  totalEvents: number;
  authorizedCount: number;
  deniedCount: number;
  confirmedCount: number;
  failedCount: number;
  voidedCount: number;
  uniqueAgents: number;
  peakReservedQuantity: number;
  peakReservedAt: string | null;
}

export interface ReplayWindow {
  from: string;
  until: string;
  durationSeconds: number;
}

export interface BudgetReplayResponse {
  budgetId: string;
  replayWindow: ReplayWindow;
  summary: ReplaySummary;
  initialState: ReplayBudgetState;
  events: ReplayFrame[];
  finalState: ReplayBudgetState;
  nextPageToken: string | null;
}

export interface TimelineEventItem {
  eventIndex: number;
  eventId: string;
  agentId: string;
  decision: SpendDecision;
  requestedQuantity: number;
  claimedCategory: string | null;
  description: string;
  createdAt: string;
  millisSincePrevious: number;
}

export interface TimelineResponse {
  budgetId: string;
  totalEvents: number;
  timeline: TimelineEventItem[];
}

export interface CounterfactualDelta {
  eventId: string;
  actualDecision: string;
  hypotheticalDecision: string;
  hypotheticalDenialReason: string | null;
  requestedQuantity: number;
  agentId: string;
  description: string;
  claimedCategory: string | null;
}

export interface CounterfactualPolicySummary {
  authorizedCount: number;
  deniedCount: number;
  totalQuantitySpent: number;
  additionalDenials?: number;
}

export interface CounterfactualReplayResponse {
  budgetId: string;
  policySource: { type: string; manifestVersion: string | null };
  actualPolicySummary: CounterfactualPolicySummary;
  hypotheticalPolicySummary: CounterfactualPolicySummary;
  deltaEvents: CounterfactualDelta[];
}

// ------------------------------------------------------------
// App-level types
// ------------------------------------------------------------

export interface LedgerFilters {
  decision?: SpendDecision;
  traceId?: string;
}
