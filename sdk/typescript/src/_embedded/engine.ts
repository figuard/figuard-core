/**
 * Embedded enforcement kernel — pure TypeScript, no dependencies.
 *
 * A faithful port of the Python embedded engine (and therefore the Java server's rules),
 * held identical by the shared conformance suite. Node is single-threaded, so the critical
 * section is naturally atomic; money is integer-scaled (×10000) to match the server's
 * BigDecimal/Decimal(scale=4) exactly — no floating-point drift.
 */

import { FiGuardCapabilityError } from "../errors";
export { FiGuardCapabilityError };

const SCALE = 10_000;
const CONFIRMATION_TIMEOUT_SECONDS = 300;

/** Parse a money value (number or decimal-string) to a scaled integer (4 dp). */
function s(x: number | string): number {
  return Math.round(Number(x) * SCALE);
}
/** Scaled integer back to a plain number. */
function n(scaled: number): number {
  return scaled / SCALE;
}
function nowIso(): string {
  return new Date().toISOString();
}
function uuid(): string {
  // RFC-4122 v4, dependency-free
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export class InvalidParentError extends Error {
  constructor(detail = "") {
    super(detail ? `INVALID_PARENT_EVENT: ${detail}` : "INVALID_PARENT_EVENT");
    this.name = "InvalidParentError";
  }
}
export class EventStateError extends Error {}
export class NotFoundError extends Error {}

export interface BudgetRow {
  id: string;
  userId?: string;
  total: number;            // scaled
  unit?: string;
  currency?: string;
  maxTxn?: number;          // scaled
  intentTags?: string[];
  entityDedup: boolean;
  velMin?: number;
  velAmtHour?: number;      // scaled
  velDay?: number;
  status: string;
  expiresAt?: string;
  spent: number;            // scaled
  reserved: number;         // scaled
  createdAt: string;
}
export interface EventRow {
  id: string;
  budgetId: string;
  decision: string;
  denialReason?: string;
  requested: number;        // scaled
  confirmed?: number;       // scaled
  currency?: string;
  claimedCategory?: string;
  idempotencyKey?: string;
  entityId?: string;
  agentId?: string;
  actionType?: string;
  description?: string;
  reserved: boolean;
  parentEventId?: string;
  chainRootEventId?: string;
  confirmationTimeoutAt?: string;
  createdAt: string;
}

export interface Snapshot {
  totalLimit: number;
  quantitySpent: number;
  quantityReserved: number;
  availableQuantity: number;
  status: string;
  // create-time metadata, so GET /budgets/{id} is faithful (rebuilt from here, not the request)
  userId?: string;
  unit?: string;
  currency?: string;
  maxTransactionQuantity?: number;
  intentTags?: string[];
  velocityMaxPerMinute?: number;
  velocityMaxAmountPerHour?: number;
  velocityMaxPerDay?: number;
  expiresAt?: string;
  createdAt?: string;
}
export interface AuthResult {
  decision: string;
  eventId?: string;
  approvedQuantity?: number;
  denialReason?: string;
  denialMessage?: string;
  originalEventId?: string;
  reserved?: boolean;
  duplicate?: boolean;
  snapshot: Snapshot;
}
export interface EventResult {
  decision: string;
  eventId: string;
  requestedQuantity: number;
  confirmedQuantity?: number;
  currency?: string;
  createdAt: string;
  snapshot: Snapshot;
}

export interface TreeNode {
  event: EventRow;
  children: TreeNode[];
}
export interface TreeResult {
  roots: TreeNode[];
  totalEvents: number;
}

export interface CreateBudgetInput {
  totalLimit: number | string;
  userId?: string;
  unit?: string;
  currency?: string;
  maxTransactionQuantity?: number | string | null;
  intentTags?: string[] | null;
  entityDedupEnabled?: boolean;
  velocityMaxPerMinute?: number | null;
  velocityMaxAmountPerHour?: number | string | null;
  velocityMaxPerDay?: number | null;
  status?: string;
  expiresAt?: string | null;
}
export interface AuthorizeInput {
  budgetId: string;
  amount: number | string;
  idempotencyKey?: string;
  entityId?: string;
  reserve?: boolean;
  currency?: string;
  claimedCategory?: string;
  parentEventId?: string;
  intentContext?: string;
  agentId?: string;
  actionType?: string;
  description?: string;
}

type State = { budgets: Record<string, BudgetRow>; events: EventRow[] };

export class LiteEngine {
  readonly backend = "embedded";
  private budgets = new Map<string, BudgetRow>();
  private events: EventRow[] = [];

  /** Export/import for the backend's optional JSON-file persistence. */
  dump(): State {
    return { budgets: Object.fromEntries(this.budgets), events: this.events };
  }
  load(state: State): void {
    this.budgets = new Map(Object.entries(state.budgets || {}));
    this.events = state.events || [];
  }

  // -- budgets --------------------------------------------------------------

  createBudget(b: CreateBudgetInput): string {
    const id = uuid();
    this.budgets.set(id, {
      id,
      userId: b.userId ?? undefined,
      total: s(b.totalLimit),
      unit: b.unit ?? undefined,
      currency: b.currency ?? undefined,
      maxTxn: b.maxTransactionQuantity != null ? s(b.maxTransactionQuantity) : undefined,
      intentTags: b.intentTags ?? undefined,
      entityDedup: !!b.entityDedupEnabled,
      velMin: b.velocityMaxPerMinute ?? undefined,
      velAmtHour: b.velocityMaxAmountPerHour != null ? s(b.velocityMaxAmountPerHour) : undefined,
      velDay: b.velocityMaxPerDay ?? undefined,
      status: b.status ?? "ACTIVE",
      expiresAt: b.expiresAt ?? undefined,
      spent: 0,
      reserved: 0,
      createdAt: nowIso(),
    });
    return id;
  }

  getSnapshot(budgetId: string): Snapshot {
    const b = this.budgets.get(budgetId);
    if (!b) throw new NotFoundError(`Budget not found: ${budgetId}`);
    return this.snap(b);
  }

  /**
   * Hierarchical view of the budget's events, built from parentEventId links.
   * Mirrors the server's GET /budgets/{id}/tree and the Python engine's get_tree: a forest
   * of {event, children} nodes in creation order, roots = events with no resolvable parent.
   */
  getTree(budgetId: string): TreeResult {
    if (!this.budgets.has(budgetId)) throw new NotFoundError(`Budget not found: ${budgetId}`);
    const rows = this.events.filter((e) => e.budgetId === budgetId);
    const nodes = new Map<string, TreeNode>();
    for (const e of rows) nodes.set(e.id, { event: e, children: [] });
    const roots: TreeNode[] = [];
    for (const e of rows) {
      const node = nodes.get(e.id)!;
      const parent = e.parentEventId ? nodes.get(e.parentEventId) : undefined;
      if (parent) parent.children.push(node);
      else roots.push(node);
    }
    return { roots, totalEvents: rows.length };
  }

  private snap(b: BudgetRow): Snapshot {
    return {
      totalLimit: n(b.total),
      quantitySpent: n(b.spent),
      quantityReserved: n(b.reserved),
      availableQuantity: n(b.total - b.spent - b.reserved),
      status: b.status,
      userId: b.userId,
      unit: b.unit,
      currency: b.currency,
      maxTransactionQuantity: b.maxTxn != null ? n(b.maxTxn) : undefined,
      intentTags: b.intentTags,
      velocityMaxPerMinute: b.velMin,
      velocityMaxAmountPerHour: b.velAmtHour != null ? n(b.velAmtHour) : undefined,
      velocityMaxPerDay: b.velDay,
      expiresAt: b.expiresAt,
      createdAt: b.createdAt,
    };
  }

  // -- authorize ------------------------------------------------------------

  authorize(req: AuthorizeInput): AuthResult {
    const b = this.budgets.get(req.budgetId);
    if (!b) throw new NotFoundError(`Budget not found: ${req.budgetId}`);
    const amount = s(req.amount);
    const reserve = req.reserve !== false;

    // idempotency replay
    if (req.idempotencyKey) {
      const prior = this.events.find(
        (e) => e.budgetId === b.id && e.idempotencyKey === req.idempotencyKey,
      );
      if (prior) return this.replay(b, prior);
    }

    // status
    const st = this.statusDenial(b);
    if (st) return this.deny(b, amount, st[0], st[1], req);

    // velocity
    const vd = this.velocityDenial(b, amount, req);
    if (vd) return vd;

    // currency
    if (req.currency && b.currency && req.currency !== b.currency)
      return this.deny(b, amount, "CURRENCY_MISMATCH",
        `requested currency ${req.currency} != budget currency ${b.currency}`, req);

    // entity dedup
    if (b.entityDedup && req.entityId) {
      const existing = this.events.find(
        (e) => e.budgetId === b.id && e.entityId === req.entityId &&
               (e.decision === "AUTHORIZED" || e.decision === "CONFIRMED"),
      );
      if (existing)
        return this.deny(b, amount, "ENTITY_ALREADY_AUTHORIZED",
          `entity ${req.entityId} already has a live event on this budget`, req, existing.id);
    }

    // max transaction quantity
    if (b.maxTxn != null && amount > b.maxTxn)
      return this.deny(b, amount, "EXCEEDS_QUANTITY_LIMIT",
        `requested ${n(amount)} exceeds maxTransactionQuantity ${n(b.maxTxn)}`, req);

    // causal-chain parent (a request error, not a denial — writes no event)
    let parentChainRoot: string | undefined;
    if (req.parentEventId != null) {
      const parent = this.events.find((e) => e.id === req.parentEventId);
      if (!parent || parent.budgetId !== b.id ||
          (parent.decision !== "AUTHORIZED" && parent.decision !== "CONFIRMED"))
        throw new InvalidParentError(`parent ${req.parentEventId}`);
      parentChainRoot = parent.chainRootEventId;
    }

    // intent scope (flat-budget gate)
    const id = this.intentDenial(b, amount, req);
    if (id) return id;

    // capacity (reserve=false holds nothing)
    const available = b.total - b.spent - b.reserved;
    if (reserve && amount > available)
      return this.deny(b, amount, "INSUFFICIENT_FUNDS", this.insufficientMsg(b, amount, available), req);

    return this.approve(b, amount, reserve, req, parentChainRoot);
  }

  private statusDenial(b: BudgetRow): [string, string] | null {
    if (b.status === "EXHAUSTED") return ["BUDGET_EXHAUSTED", "budget is exhausted"];
    if (b.status === "PAUSED") return ["BUDGET_PAUSED", "budget is paused"];
    if (b.status === "CANCELLED") return ["BUDGET_CANCELLED", "budget was cancelled"];
    if (b.status === "EXPIRED") return ["BUDGET_EXPIRED", "budget has expired"];
    if (b.expiresAt && new Date() > new Date(b.expiresAt))
      return ["BUDGET_EXPIRED", "budget has passed expiresAt"];
    return null;
  }

  private velocityDenial(b: BudgetRow, amount: number, req: AuthorizeInput): AuthResult | null {
    const countAfter = (cutoff: string) =>
      this.events.filter((e) => e.budgetId === b.id && e.createdAt > cutoff).length;
    const sumAfter = (cutoff: string) =>
      this.events.filter((e) => e.budgetId === b.id && e.createdAt > cutoff)
        .reduce((acc, e) => acc + e.requested, 0);
    const ago = (ms: number) => new Date(Date.now() - ms).toISOString();

    if (b.velMin != null && countAfter(ago(60_000)) >= b.velMin)
      return this.deny(b, amount, "VELOCITY_LIMIT_EXCEEDED", `maxPerMinute=${b.velMin}`, req);
    if (b.velAmtHour != null && sumAfter(ago(3_600_000)) + amount > b.velAmtHour)
      return this.deny(b, amount, "VELOCITY_LIMIT_EXCEEDED", `maxAmountPerHour=${n(b.velAmtHour)}`, req);
    if (b.velDay != null && countAfter(ago(86_400_000)) >= b.velDay)
      return this.deny(b, amount, "VELOCITY_LIMIT_EXCEEDED", `maxPerDay=${b.velDay}`, req);
    return null;
  }

  private intentDenial(b: BudgetRow, amount: number, req: AuthorizeInput): AuthResult | null {
    const tags = b.intentTags;
    if (!tags || tags.length === 0) return null;
    const ctx = req.intentContext;
    if (!ctx || !ctx.trim())
      return this.deny(b, amount, "INTENT_SCOPE_VIOLATION",
        `budget requires intentContext (intentTags: ${JSON.stringify(tags)})`, req);
    const lower = ctx.toLowerCase();
    if (!tags.some((t) => t && lower.includes(t.toLowerCase())))
      return this.deny(b, amount, "INTENT_SCOPE_VIOLATION",
        `intentContext '${ctx}' matches no intentTags ${JSON.stringify(tags)}`, req);
    return null;
  }

  private insufficientMsg(b: BudgetRow, amount: number, available: number): string {
    const base = `Budget has ${n(available)} available, requested ${n(amount)}`;
    if (b.reserved > 0 && b.spent < b.total)
      return `${base}. ${n(b.reserved)} is reserved by unconfirmed authorizations ` +
        `(only ${n(b.spent)} of ${n(b.total)} actually spent) — confirm or void them to free capacity`;
    return base;
  }

  private approve(
    b: BudgetRow, amount: number, reserve: boolean, req: AuthorizeInput, parentChainRoot?: string,
  ): AuthResult {
    const eid = uuid();
    const now = nowIso();
    if (reserve) b.reserved += amount;
    const ev: EventRow = {
      id: eid,
      budgetId: b.id,
      decision: "AUTHORIZED",
      requested: amount,
      currency: req.currency ?? b.currency,
      claimedCategory: req.claimedCategory,
      idempotencyKey: req.idempotencyKey,
      entityId: req.entityId,
      agentId: req.agentId,
      actionType: req.actionType,
      description: req.description,
      reserved: reserve,
      parentEventId: req.parentEventId,
      chainRootEventId: parentChainRoot ?? eid,
      confirmationTimeoutAt: reserve
        ? new Date(Date.now() + CONFIRMATION_TIMEOUT_SECONDS * 1000).toISOString()
        : undefined,
      createdAt: now,
    };
    this.events.push(ev);
    return {
      decision: "AUTHORIZED",
      eventId: eid,
      approvedQuantity: n(amount),
      reserved: reserve,
      snapshot: this.snap(b),
    };
  }

  private deny(
    b: BudgetRow, amount: number, code: string, message: string, req: AuthorizeInput,
    originalEventId?: string,
  ): AuthResult {
    const eid = uuid();
    this.events.push({
      id: eid, budgetId: b.id, decision: "DENIED", denialReason: code,
      requested: amount, idempotencyKey: req.idempotencyKey, entityId: req.entityId,
      reserved: false, createdAt: nowIso(),
    });
    return {
      decision: "DENIED", eventId: eid, denialReason: code, denialMessage: message,
      originalEventId, snapshot: this.snap(b),
    };
  }

  private replay(b: BudgetRow, prior: EventRow): AuthResult {
    if (prior.decision === "DENIED")
      return { decision: "DENIED", eventId: prior.id, denialReason: prior.denialReason,
               duplicate: true, snapshot: this.snap(b) };
    return { decision: prior.decision, eventId: prior.id, approvedQuantity: n(prior.requested),
             duplicate: true, snapshot: this.snap(b) };
  }

  // -- lifecycle ------------------------------------------------------------

  confirm(eventId: string, confirmedQuantity: number | string): EventResult {
    const e = this.loadAuthorized(eventId);
    const b = this.budgets.get(e.budgetId)!;
    const confirmed = s(confirmedQuantity);
    if (e.reserved) b.reserved -= e.requested;   // release only a reservation that was held
    b.spent += confirmed;                          // always record the actual as spend
    e.decision = "CONFIRMED";
    e.confirmed = confirmed;
    return this.eventResult(e, b, "CONFIRMED");
  }

  fail(eventId: string): EventResult {
    return this.release(eventId, "FAILED");
  }
  void(eventId: string): EventResult {
    return this.release(eventId, "VOIDED");
  }

  private release(eventId: string, to: string): EventResult {
    const e = this.loadAuthorized(eventId);
    const b = this.budgets.get(e.budgetId)!;
    if (e.reserved) b.reserved -= e.requested;
    e.decision = to;
    return this.eventResult(e, b, to);
  }

  private loadAuthorized(eventId: string): EventRow {
    const e = this.events.find((x) => x.id === eventId);
    if (!e) throw new NotFoundError(`Event not found: ${eventId}`);
    if (e.decision !== "AUTHORIZED")
      throw new EventStateError(`Event is not in AUTHORIZED state (current: ${e.decision})`);
    return e;
  }

  private eventResult(e: EventRow, b: BudgetRow, decision: string): EventResult {
    return {
      decision, eventId: e.id, requestedQuantity: n(e.requested),
      confirmedQuantity: e.confirmed != null ? n(e.confirmed) : undefined,
      currency: e.currency, createdAt: e.createdAt, snapshot: this.snap(b),
    };
  }

  // -- capability boundary --------------------------------------------------

  createDelegationToken(): never { throw new FiGuardCapabilityError("Delegation tokens"); }
  createSubscription(): never { throw new FiGuardCapabilityError("Subscriptions & entitlements"); }
}
