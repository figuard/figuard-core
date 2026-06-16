/**
 * Embedded backend — serves the FiGuard REST contract in-process, no server.
 *
 * Implements the same (method, path, body, headers) → server-shaped JSON contract the HTTP
 * transport provides, so FiGuardClient's methods work UNCHANGED on either backend. Optional
 * JSON-file persistence (Node fs) so budgets survive restarts. Fleet endpoints throw
 * FiGuardCapabilityError. Mirrors the Python embedded backend.
 */

import { FiGuardApiError, FiGuardCapabilityError } from "../errors";
import { EventResult, EventRow, InvalidParentError, LiteEngine, Snapshot, TreeNode, TreeResult } from "./engine";

function genToken(): string {
  return "st_" + Array.from({ length: 32 }, () => ((Math.random() * 16) | 0).toString(16)).join("");
}

// Session tokens are stored by SHA-256 hash (never raw), mirroring the server — a leaked
// JSON store yields no live tokens.
function hashToken(token: string): string {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  return require("crypto").createHash("sha256").update(token).digest("hex");
}

function featureFor(path: string): string {
  if (path.includes("delegation")) return "Delegation tokens";
  if (path.includes("subscription") || path.includes("entitlement")) return "Subscriptions & entitlements";
  if (path.includes("webhook")) return "Webhooks";
  if (path.includes("replay")) return "Spend replay";
  return `This operation (${path})`;
}

export class EmbeddedBackend {
  readonly backend = "embedded";
  private engine = new LiteEngine();
  private tokens = new Map<string, string>(); // sha256(sessionToken) -> budgetId; PERSISTED
  private dbPath?: string;

  constructor(database = ":memory:") {
    if (database && database !== ":memory:") {
      this.dbPath = database;
      this.loadFromDisk();
    }
  }

  // Same surface as the HTTP request path; returns a resolved Promise.
  async request(
    method: string,
    path: string,
    opts: { body?: Record<string, unknown>; headers?: Record<string, string> } = {},
  ): Promise<Record<string, unknown>> {
    const body = (opts.body || {}) as Record<string, unknown>;
    const headers = opts.headers || {};
    let g: RegExpMatchArray | null;

    if (method === "POST" && path === "/api/v1/budgets") return this.persisted(this.createBudget(body));
    if (method === "POST" && path === "/api/v1/authorize")
      return this.persisted(this.authorize(body, headers["X-Session-Token"]));
    if (method === "POST" && (g = path.match(/^\/api\/v1\/events\/([^/]+)\/confirm$/)))
      return this.persisted(this.eventJson(this.engine.confirm(g[1], Number(body["confirmedQuantity"]))));
    if (method === "POST" && (g = path.match(/^\/api\/v1\/events\/([^/]+)\/fail$/)))
      return this.persisted(this.eventJson(this.engine.fail(g[1])));
    if (method === "POST" && (g = path.match(/^\/api\/v1\/events\/([^/]+)\/void$/)))
      return this.persisted(this.eventJson(this.engine.void(g[1])));
    if (method === "GET" && (g = path.match(/^\/api\/v1\/budgets\/([^/]+)\/tree$/)))
      return this.treeJson(this.engine.getTree(g[1]));
    if (method === "GET" && (g = path.match(/^\/api\/v1\/budgets\/([^/]+)$/)))
      return this.budgetJson(g[1], {}, undefined, this.engine.getSnapshot(g[1]));

    throw new FiGuardCapabilityError(featureFor(path));
  }

  // Refuse create-time options embedded can't enforce — so a budget never *looks* configured
  // (category caps, shadow mode, anomaly) while silently enforcing nothing. Mirrors the runtime
  // capability boundary. (softLimit is advisory → allowed.)
  private rejectServerOnlyCreateOpts(body: Record<string, unknown>): void {
    const allocations = body["allocations"] as unknown[] | undefined;
    if (allocations && allocations.length) throw new FiGuardCapabilityError("Category allocations");
    const trustMode = body["trustMode"] as string | undefined;
    if (trustMode && trustMode.toUpperCase() !== "STRICT") throw new FiGuardCapabilityError("Shadow / trust modes");
    if (body["anomalyDetectionEnabled"]) throw new FiGuardCapabilityError("Anomaly detection");
  }

  private createBudget(body: Record<string, unknown>): Record<string, unknown> {
    this.rejectServerOnlyCreateOpts(body);
    const id = this.engine.createBudget({
      totalLimit: body["totalLimit"] as number,
      userId: body["userId"] as string | undefined,
      currency: body["currency"] as string | undefined,
      unit: (body["unit"] as string) ?? (body["currency"] ? undefined : "usd"),
      expiresAt: (body["expiresAt"] as string) ?? null,
      maxTransactionQuantity: (body["maxTransactionQuantity"] as number) ?? null,
      intentTags: (body["intentTags"] as string[]) ?? null,
      entityDedupEnabled: !!body["entityDedupEnabled"],
      velocityMaxPerMinute: (body["velocityMaxPerMinute"] as number) ?? null,
      velocityMaxAmountPerHour: (body["velocityMaxAmountPerHour"] as number) ?? null,
      velocityMaxPerDay: (body["velocityMaxPerDay"] as number) ?? null,
    });
    const token = genToken();
    this.tokens.set(hashToken(token), id);
    return this.budgetJson(id, body, token, this.engine.getSnapshot(id));
  }

  private authorize(body: Record<string, unknown>, sessionToken?: string): Record<string, unknown> {
    const budgetId = sessionToken ? this.tokens.get(hashToken(sessionToken)) : undefined;
    if (!budgetId) throw new FiGuardApiError(401, "INVALID_SESSION_TOKEN");
    let r;
    try {
      r = this.engine.authorize({
        budgetId,
        amount: body["requestedQuantity"] as number,
        idempotencyKey: body["idempotencyKey"] as string | undefined,
        entityId: body["entityId"] as string | undefined,
        reserve: body["reserve"] !== false,
        currency: body["currency"] as string | undefined,
        claimedCategory: body["claimedCategory"] as string | undefined,
        parentEventId: body["parentEventId"] as string | undefined,
        intentContext: body["intentContext"] as string | undefined,
        agentId: body["agentId"] as string | undefined,
        actionType: body["actionType"] as string | undefined,
        description: body["description"] as string | undefined,
      });
    } catch (e) {
      if (e instanceof InvalidParentError) throw new FiGuardApiError(400, String(e.message));
      throw e;
    }
    return {
      eventId: r.eventId ?? null,
      decision: r.decision,
      approvedQuantity: r.approvedQuantity ?? null,
      denialReason: r.denialReason ?? null,
      denialMessage: r.denialMessage ?? null,
      originalEventId: r.originalEventId ?? null,
      budgetSnapshot: r.snapshot,
    };
  }

  private eventJson(r: EventResult): Record<string, unknown> {
    return {
      id: r.eventId,
      decision: r.decision,
      requestedQuantity: r.requestedQuantity,
      confirmedQuantity: r.confirmedQuantity ?? null,
      currency: r.currency ?? null,
      createdAt: r.createdAt || "",
    };
  }

  // Reshape the engine's forest into the server's GET /tree JSON so makeSpendTreeNode consumes
  // either backend identically. Recurses depth-first; the engine guarantees a forest (no cycles).
  private treeJson(tree: TreeResult): Record<string, unknown> {
    return {
      roots: tree.roots.map((node) => this.treeNodeJson(node)),
      totalEvents: tree.totalEvents,
    };
  }
  private treeNodeJson(node: TreeNode): Record<string, unknown> {
    return {
      event: this.treeEventJson(node.event),
      children: node.children.map((c) => this.treeNodeJson(c)),
    };
  }
  private treeEventJson(e: EventRow): Record<string, unknown> {
    return {
      id: e.id,
      decision: e.decision,
      requestedQuantity: e.requested / 10_000,
      confirmedQuantity: e.confirmed != null ? e.confirmed / 10_000 : null,
      currency: e.currency ?? null,
      entityId: e.entityId ?? null,
      claimedCategory: e.claimedCategory ?? null,
      agentId: e.agentId ?? null,
      actionType: e.actionType ?? null,
      description: e.description ?? null,
      denialReason: e.denialReason ?? null,
      parentEventId: e.parentEventId ?? null,
      createdAt: e.createdAt || "",
    };
  }

  private budgetJson(
    id: string, createBody: Record<string, unknown>, token: string | undefined, snap: Snapshot,
  ): Record<string, unknown> {
    // Metadata is read from the snapshot (which now carries it) so a GET is identical to the
    // CREATE response; createBody only supplies userId (not persisted on the budget row).
    return {
      id,
      userId: snap.userId ?? (createBody["userId"] as string) ?? "embedded",
      totalLimit: snap.totalLimit,
      quantitySpent: snap.quantitySpent,
      quantityReserved: snap.quantityReserved,
      availableQuantity: snap.availableQuantity,
      status: snap.status,
      expiresAt: snap.expiresAt ?? "",
      currency: snap.currency ?? null,
      unit: snap.unit ?? (snap.currency ? null : "usd"),
      intentTags: snap.intentTags ?? null,
      maxTransactionQuantity: snap.maxTransactionQuantity ?? null,
      velocityMaxPerMinute: snap.velocityMaxPerMinute ?? null,
      velocityMaxAmountPerHour: snap.velocityMaxAmountPerHour ?? null,
      velocityMaxPerDay: snap.velocityMaxPerDay ?? null,
      tokens: token ? [{ sessionToken: token, category: null }] : null,
    };
  }

  // -- optional JSON-file persistence (Node) --------------------------------

  private persisted<T extends Record<string, unknown>>(v: T): T {
    if (this.dbPath) this.saveToDisk();
    return v;
  }
  private loadFromDisk(): void {
    try {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      const fs = require("fs");
      if (!fs.existsSync(this.dbPath)) return;
      const blob = JSON.parse(fs.readFileSync(this.dbPath, "utf8"));
      // Persist BOTH the engine state and the token map, so a restarted process can resolve
      // a budget's session token (without this, persisted budgets become unauthorizable).
      this.engine.load(blob.engine ?? blob);            // blob.engine (new) | bare state (legacy)
      this.tokens = new Map(Object.entries(blob.tokens ?? {}));
    } catch { /* fresh store */ }
  }
  private saveToDisk(): void {
    try {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      const fs = require("fs");
      const path = require("path");
      fs.mkdirSync(path.dirname(this.dbPath), { recursive: true });
      fs.writeFileSync(this.dbPath, JSON.stringify({
        engine: this.engine.dump(),
        tokens: Object.fromEntries(this.tokens),
      }));
    } catch { /* non-fatal */ }
  }
}
