/**
 * MockFiGuardClient — in-memory FiGuard client for unit tests.
 *
 * No network calls. Enforces budget limits, tracks reservations and
 * confirmations, handles idempotency and entity dedup — same behavioral
 * contract as the real server for the happy path and common denial cases.
 *
 * @example
 * ```typescript
 * import { MockFiGuardClient } from "figuard/testing";
 * import { DenialReason } from "figuard";
 *
 * test("denies when budget exhausted", async () => {
 *   const client = new MockFiGuardClient({ totalLimit: 500, currency: "USD" });
 *
 *   const ok = await client.authorize({
 *     sessionToken: client.sandboxToken,
 *     agentId: "travel_agent",
 *     actionType: "PURCHASE",
 *     description: "NYC flight",
 *     requestedQuantity: 300,
 *     idempotencyKey: "txn-001",
 *   });
 *   expect(ok.isAuthorized).toBe(true);
 *
 *   await client.confirmEvent({ eventId: ok.eventId, confirmedQuantity: 300 });
 *
 *   const denied = await client.authorize({
 *     sessionToken: client.sandboxToken,
 *     agentId: "travel_agent",
 *     actionType: "PURCHASE",
 *     description: "Hotel",
 *     requestedQuantity: 300,
 *     idempotencyKey: "txn-002",
 *   });
 *   expect(denied.denialReason).toBe(DenialReason.BUDGET_EXHAUSTED);
 *
 *   client.assertAuthorized({ count: 1 });
 *   client.assertDenied({ reason: DenialReason.BUDGET_EXHAUSTED, count: 1 });
 *   client.assertSpent(300);
 * });
 * ```
 */

import { FiGuardDeniedException } from "./errors";
import { DenialReason } from "./models";
import type {
  AllocationResponse,
  AuthorizationResult,
  Budget,
  BudgetSnapshot,
  BudgetToken,
  LedgerPage,
  SpendEventResponse,
  VoidResult,
  VoidTreeResult,
} from "./models";

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function newId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 14)}`;
}

function now(): string {
  return new Date().toISOString();
}

interface AllocationState {
  category: string;
  limit: number;
  reserved: number;
  spent: number;
}

function allocAvailable(a: AllocationState): number {
  return Math.max(0, a.limit - a.reserved - a.spent);
}

interface EventEntry {
  event: SpendEventResponse;
  reserved: number;
}

// ---------------------------------------------------------------------------
// MockFiGuardClient options
// ---------------------------------------------------------------------------

export interface MockFiGuardClientOptions {
  /** Total spend limit. Default: 1000. */
  totalLimit?: number;
  /** ISO 4217 code for monetary budgets (e.g. "USD"). Default: "USD". */
  currency?: string;
  /** Unit name for resource budgets (e.g. "tokens"). Leave unset for monetary. */
  unit?: string;
  /** Optional per-category limits: `{ flights: 300, hotels: 200 }`. */
  allocations?: Record<string, number>;
}

// ---------------------------------------------------------------------------
// MockFiGuardClient
// ---------------------------------------------------------------------------

/**
 * In-memory FiGuard client for unit and integration tests.
 *
 * **What it simulates**
 * - Budget exhaustion (`BUDGET_EXHAUSTED`) — tracks reservations and confirmed spend
 * - Allocation enforcement (`ALLOCATION_EXHAUSTED`) — optional per-category limits
 * - Idempotency — same `idempotencyKey` returns the original result, no double-charge
 * - Entity dedup (`ENTITY_ALREADY_AUTHORIZED`) — same `entityId` is blocked
 * - `dryRun: true` — checks fire but no state mutation
 * - `confirmEvent` / `failEvent` / `voidEvent` / `voidTree` — correct state transitions
 *
 * **What it does NOT simulate**
 * - Velocity limits, anomaly detection, webhooks, token expiry
 */
export class MockFiGuardClient {
  private readonly _totalLimit: number;
  private readonly _currency?: string;
  private readonly _unit?: string;
  private readonly _budgetId: string;
  private readonly _token: string;

  private _reserved = 0;
  private _spent = 0;

  private readonly _store = new Map<string, EventEntry>();
  private readonly _order: string[] = [];
  private readonly _idem = new Map<string, string>(); // idempotency_key → event_id
  private readonly _entities = new Map<string, string>(); // entity_id → event_id
  private readonly _allocs = new Map<string, AllocationState>();

  constructor(options: MockFiGuardClientOptions = {}) {
    this._totalLimit = options.totalLimit ?? 1000;
    this._currency = options.currency ?? "USD";
    this._unit = options.unit;
    this._budgetId = newId("mock_bdg");
    this._token = newId("mock_tok");

    if (options.allocations) {
      for (const [cat, limit] of Object.entries(options.allocations)) {
        this._allocs.set(cat, { category: cat, limit, reserved: 0, spent: 0 });
      }
    }
  }

  // -----------------------------------------------------------------------
  // Properties
  // -----------------------------------------------------------------------

  /** Pre-created session token — use directly without calling `createBudget()`. */
  get sandboxToken(): string {
    return this._token;
  }

  /** All spend events in creation order. */
  get events(): SpendEventResponse[] {
    return this._order.map((id) => this._store.get(id)!.event);
  }

  /**
   * Events that were ever authorized — decision is `"AUTHORIZED"`, `"CONFIRMED"`,
   * `"FAILED"`, or `"VOIDED"` (all started as AUTHORIZED).
   */
  get authorizedEvents(): SpendEventResponse[] {
    return this.events.filter((e) =>
      e.decision === "AUTHORIZED" || e.decision === "CONFIRMED" ||
      e.decision === "FAILED" || e.decision === "VOIDED",
    );
  }

  /** Events with decision `"DENIED"`. */
  get deniedEvents(): SpendEventResponse[] {
    return this.events.filter((e) => e.decision === "DENIED");
  }

  /** Current available capacity (totalLimit − reserved − spent). */
  get availableQuantity(): number {
    return Math.max(0, this._totalLimit - this._reserved - this._spent);
  }

  // -----------------------------------------------------------------------
  // Budget stubs
  // -----------------------------------------------------------------------

  /** Return a fake `Budget` with the sandbox token embedded in `tokens`. */
  async createBudget(options: { userId?: string; [key: string]: unknown } = {}): Promise<Budget> {
    const token: BudgetToken = { category: "default", sessionToken: this._token };
    return {
      id: this._budgetId,
      userId: options["userId"] as string ?? "test_user",
      totalLimit: this._totalLimit,
      quantitySpent: this._spent,
      quantityReserved: this._reserved,
      availableQuantity: this.availableQuantity,
      status: "ACTIVE",
      expiresAt: "2099-12-31T23:59:59Z",
      currency: this._currency,
      unit: this._unit,
      allocations: this._allocationResponses(),
      tokens: [token],
      primaryToken: token,
      isActive: true,
      isPaused: false,
      isMonetary: typeof this._currency === "string" && this._currency.trim().length > 0,
    };
  }

  async getBudget(_budgetId: string): Promise<Budget> {
    return this.createBudget();
  }

  // -----------------------------------------------------------------------
  // Authorization
  // -----------------------------------------------------------------------

  /**
   * Authorize a spend request against the mock budget.
   *
   * Denial precedence:
   * 1. Idempotency key already used — return original result
   * 2. Entity ID already active — `ENTITY_ALREADY_AUTHORIZED`
   * 3. Category allocation exhausted — `ALLOCATION_EXHAUSTED`
   * 4. Budget exhausted — `BUDGET_EXHAUSTED`
   * 5. Authorized
   */
  async authorize(options: {
    sessionToken: string;
    agentId: string;
    actionType: string;
    description: string;
    requestedQuantity: number;
    idempotencyKey: string;
    entityId?: string;
    claimedCategory?: string;
    dryRun?: boolean;
    [key: string]: unknown;
  }): Promise<AuthorizationResult> {
    const {
      agentId,
      actionType,
      description,
      requestedQuantity,
      idempotencyKey,
      entityId,
      claimedCategory,
      dryRun = false,
    } = options;

    // 1. Idempotency
    if (this._idem.has(idempotencyKey)) {
      const originalId = this._idem.get(idempotencyKey)!;
      const original = this._store.get(originalId)!.event;
      return this._makeResult(originalId, original.decision, original.denialReason ?? undefined);
    }

    // 2. Entity dedup
    if (entityId && this._entities.has(entityId)) {
      const originalId = this._entities.get(entityId)!;
      return this._deny({
        agentId, actionType, description, requestedQuantity,
        idempotencyKey, entityId, claimedCategory,
        denialReason: DenialReason.ENTITY_ALREADY_AUTHORIZED,
        originalEventId: originalId,
        dryRun,
      });
    }

    // 3. Allocation check
    if (claimedCategory) {
      const alloc = this._allocs.get(claimedCategory);
      if (alloc && allocAvailable(alloc) < requestedQuantity) {
        return this._deny({
          agentId, actionType, description, requestedQuantity,
          idempotencyKey, entityId, claimedCategory,
          denialReason: DenialReason.ALLOCATION_EXHAUSTED,
          dryRun,
        });
      }
    }

    // 4. Budget check
    if (this.availableQuantity < requestedQuantity) {
      return this._deny({
        agentId, actionType, description, requestedQuantity,
        idempotencyKey, entityId, claimedCategory,
        denialReason: DenialReason.BUDGET_EXHAUSTED,
        dryRun,
      });
    }

    // 5. Authorize
    const eventId = newId("mock_evt");
    if (!dryRun) {
      this._reserved += requestedQuantity;
      if (claimedCategory) {
        const alloc = this._allocs.get(claimedCategory);
        if (alloc) alloc.reserved += requestedQuantity;
      }
      this._idem.set(idempotencyKey, eventId);
      if (entityId) this._entities.set(entityId, eventId);
    }

    this._record({
      eventId, agentId, actionType, description, requestedQuantity,
      idempotencyKey, decision: "AUTHORIZED", entityId, claimedCategory,
      reserved: dryRun ? 0 : requestedQuantity,
    });

    return this._makeResult(eventId, "AUTHORIZED");
  }

  // -----------------------------------------------------------------------
  // Event lifecycle
  // -----------------------------------------------------------------------

  async confirmEvent(options: {
    eventId: string;
    confirmedQuantity?: number;
    [key: string]: unknown;
  }): Promise<SpendEventResponse> {
    const entry = this._getEntry(options.eventId, "AUTHORIZED");
    const event = entry.event;
    const qty = options.confirmedQuantity ?? event.requestedQuantity;
    const reserved = entry.reserved;

    this._reserved -= reserved;
    this._spent += qty;
    const alloc = event.claimedCategory ? this._allocs.get(event.claimedCategory) : undefined;
    if (alloc) { alloc.reserved -= reserved; alloc.spent += qty; }

    entry.reserved = 0;
    const updated: SpendEventResponse = { ...event, decision: "CONFIRMED", confirmedQuantity: qty };
    entry.event = updated;
    return updated;
  }

  async failEvent(options: {
    eventId: string;
    failureReason?: string;
    [key: string]: unknown;
  }): Promise<SpendEventResponse> {
    const entry = this._getEntry(options.eventId, "AUTHORIZED");
    const event = entry.event;
    const reserved = entry.reserved;

    this._reserved -= reserved;
    const alloc = event.claimedCategory ? this._allocs.get(event.claimedCategory) : undefined;
    if (alloc) alloc.reserved -= reserved;

    entry.reserved = 0;
    const updated: SpendEventResponse = {
      ...event,
      decision: "FAILED",
      failureReason: options.failureReason ?? "PAYMENT_FAILED",
    };
    entry.event = updated;
    return updated;
  }

  async voidEvent(options: {
    eventId: string;
    reason?: string;
    [key: string]: unknown;
  }): Promise<VoidResult> {
    const entry = this._getEntry(options.eventId, "AUTHORIZED");
    const event = entry.event;
    const reserved = entry.reserved;

    this._reserved -= reserved;
    const alloc = event.claimedCategory ? this._allocs.get(event.claimedCategory) : undefined;
    if (alloc) alloc.reserved -= reserved;

    // Release entity lock so the same entityId can be re-authorized
    if (event.entityId) this._entities.delete(event.entityId);

    entry.reserved = 0;
    const updated: SpendEventResponse = {
      ...event,
      decision: "VOIDED",
      failureReason: options.reason ?? "VOIDED",
    };
    entry.event = updated;
    return { event: updated, isVoided: true };
  }

  async voidTree(options: {
    eventId: string;
    reason?: string;
    [key: string]: unknown;
  }): Promise<VoidTreeResult> {
    const reason = options.reason ?? "VOIDED";
    const toVoid = [options.eventId, ...this.events
      .filter((e) => e.parentEventId === options.eventId && e.decision === "AUTHORIZED")
      .map((e) => e.id),
    ];

    let totalReleased = 0;
    const voidedIds: string[] = [];
    for (const eid of toVoid) {
      const entry = this._store.get(eid);
      if (entry && entry.event.decision === "AUTHORIZED") {
        const result = await this.voidEvent({ eventId: eid, reason });
        totalReleased += result.event.requestedQuantity;
        voidedIds.push(eid);
      }
    }

    return {
      rootEventId: options.eventId,
      voidedCount: voidedIds.length,
      totalQuantityReleased: totalReleased,
      currency: this._currency,
      voidedEventIds: voidedIds,
      reason,
    };
  }

  // -----------------------------------------------------------------------
  // Ledger
  // -----------------------------------------------------------------------

  async getLedger(options: {
    budgetId: string;
    page?: number;
    size?: number;
    decision?: string;
    [key: string]: unknown;
  }): Promise<LedgerPage> {
    const page = options.page ?? 0;
    const size = options.size ?? 20;
    const allEvents = options.decision
      ? this.events.filter((e) => e.decision === options.decision)
      : this.events;
    const total = allEvents.length;
    const totalPages = Math.max(1, Math.ceil(total / size));
    const pageEvents = allEvents.slice(page * size, (page + 1) * size);
    return {
      events: pageEvents,
      totalElements: total,
      totalPages,
      page,
      size,
      hasNext: page < totalPages - 1,
    };
  }

  async *iterEvents(options: {
    budgetId: string;
    decision?: string;
    [key: string]: unknown;
  }): AsyncGenerator<SpendEventResponse> {
    for (const event of this.events) {
      if (!options.decision || event.decision === options.decision) {
        yield event;
      }
    }
  }

  // -----------------------------------------------------------------------
  // Test helpers
  // -----------------------------------------------------------------------

  /**
   * Assert that at least one (or exactly `count`) AUTHORIZED events exist.
   * @throws if the condition is not met.
   */
  assertAuthorized(options: { count?: number } = {}): void {
    const authorized = this.authorizedEvents;
    if (options.count !== undefined) {
      if (authorized.length !== options.count) {
        throw new Error(
          `MockFiGuardClient: expected ${options.count} authorized event(s), got ${authorized.length}`,
        );
      }
    } else if (authorized.length === 0) {
      throw new Error("MockFiGuardClient: expected at least one authorized event, got none");
    }
  }

  /**
   * Assert that at least one (or exactly `count`) DENIED events exist,
   * optionally matching a specific `DenialReason`.
   * @throws if the condition is not met.
   */
  assertDenied(options: { reason?: string; count?: number } = {}): void {
    let denied = this.deniedEvents;
    if (options.reason) denied = denied.filter((e) => e.denialReason === options.reason);
    const label = options.reason ? ` with reason=${options.reason}` : "";
    if (options.count !== undefined) {
      if (denied.length !== options.count) {
        throw new Error(
          `MockFiGuardClient: expected ${options.count} denied event(s)${label}, got ${denied.length}`,
        );
      }
    } else if (denied.length === 0) {
      throw new Error(`MockFiGuardClient: expected at least one denied event${label}, got none`);
    }
  }

  /**
   * Assert that confirmed spend equals `amount` within `tolerance`.
   * @throws if the condition is not met.
   */
  assertSpent(amount: number, tolerance = 0.01): void {
    if (Math.abs(this._spent - amount) > tolerance) {
      throw new Error(
        `MockFiGuardClient: expected ${amount} spent, got ${this._spent}`,
      );
    }
  }

  /**
   * Assert that outstanding reservations equal `amount` within `tolerance`.
   * @throws if the condition is not met.
   */
  assertReserved(amount: number, tolerance = 0.01): void {
    if (Math.abs(this._reserved - amount) > tolerance) {
      throw new Error(
        `MockFiGuardClient: expected ${amount} reserved, got ${this._reserved}`,
      );
    }
  }

  /**
   * Assert that available capacity equals `amount` within `tolerance`.
   * @throws if the condition is not met.
   */
  assertAvailable(amount: number, tolerance = 0.01): void {
    if (Math.abs(this.availableQuantity - amount) > tolerance) {
      throw new Error(
        `MockFiGuardClient: expected ${amount} available, got ${this.availableQuantity}`,
      );
    }
  }

  /**
   * Clear all state. Call between test cases that share a client instance.
   * (Creating a new `MockFiGuardClient` per test is also fine.)
   */
  reset(): void {
    this._reserved = 0;
    this._spent = 0;
    this._store.clear();
    this._order.length = 0;
    this._idem.clear();
    this._entities.clear();
    this._allocs.forEach((a) => { a.reserved = 0; a.spent = 0; });
  }

  // -----------------------------------------------------------------------
  // Internals
  // -----------------------------------------------------------------------

  private _getEntry(eventId: string, requiredDecision: string): EventEntry {
    const entry = this._store.get(eventId);
    if (!entry) throw new Error(`MockFiGuardClient: event ${eventId} not found`);
    if (entry.event.decision !== requiredDecision) {
      throw new Error(
        `MockFiGuardClient: event ${eventId} is ${entry.event.decision}, expected ${requiredDecision}`,
      );
    }
    return entry;
  }

  private _record(params: {
    eventId: string;
    agentId: string;
    actionType: string;
    description: string;
    requestedQuantity: number;
    idempotencyKey: string;
    decision: string;
    denialReason?: string;
    entityId?: string;
    claimedCategory?: string;
    reserved: number;
  }): SpendEventResponse {
    const event: SpendEventResponse = {
      id: params.eventId,
      decision: params.decision,
      requestedQuantity: params.requestedQuantity,
      createdAt: now(),
      agentId: params.agentId,
      actionType: params.actionType,
      description: params.description,
      idempotencyKey: params.idempotencyKey,
      denialReason: params.denialReason,
      entityId: params.entityId,
      claimedCategory: params.claimedCategory,
      currency: this._currency,
    };
    this._store.set(params.eventId, { event, reserved: params.reserved });
    this._order.push(params.eventId);
    return event;
  }

  private _deny(params: {
    agentId: string;
    actionType: string;
    description: string;
    requestedQuantity: number;
    idempotencyKey: string;
    denialReason: string;
    entityId?: string;
    claimedCategory?: string;
    originalEventId?: string;
    dryRun: boolean;
  }): AuthorizationResult {
    const eventId = newId("mock_evt");
    this._record({
      eventId,
      agentId: params.agentId,
      actionType: params.actionType,
      description: params.description,
      requestedQuantity: params.requestedQuantity,
      idempotencyKey: params.idempotencyKey,
      decision: "DENIED",
      denialReason: params.denialReason,
      entityId: params.entityId,
      claimedCategory: params.claimedCategory,
      reserved: 0,
    });
    if (!params.dryRun) this._idem.set(params.idempotencyKey, eventId);
    return this._makeResult(eventId, "DENIED", params.denialReason, params.originalEventId);
  }

  private _makeResult(
    eventId: string,
    decision: string,
    denialReason?: string,
    originalEventId?: string,
  ): AuthorizationResult {
    const snapshot: BudgetSnapshot = {
      totalLimit: this._totalLimit,
      quantitySpent: this._spent,
      quantityReserved: this._reserved,
      availableQuantity: this.availableQuantity,
      status: "ACTIVE",
    };
    const result: AuthorizationResult = {
      eventId,
      decision,
      budgetSnapshot: snapshot,
      denialReason,
      originalEventId,
      isAuthorized: decision === "AUTHORIZED",
      raiseIfDenied() {
        if (decision !== "AUTHORIZED") {
          throw new FiGuardDeniedException(
            denialReason ?? "UNKNOWN",
            undefined,
            originalEventId,
          );
        }
        return result;
      },
    };
    return result;
  }

  private _allocationResponses(): AllocationResponse[] {
    return Array.from(this._allocs.values()).map((a) => ({
      id: `mock_alloc_${a.category}`,
      category: a.category,
      allowedCategories: [a.category],
      limit: a.limit,
      quantitySpent: a.spent,
      quantityReserved: a.reserved,
      availableQuantity: allocAvailable(a),
      status: "ACTIVE",
      enforcementMode: "CATEGORY_CONSTRAINED",
    }));
  }
}
