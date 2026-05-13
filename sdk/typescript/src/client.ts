/**
 * FiGuard TypeScript client.
 *
 * Usage:
 *   import { FiGuardClient } from "figuard";
 *
 *   const client = new FiGuardClient({ apiKey: "ab_live_..." });
 *
 *   const budget = await client.createBudget({
 *     userId: "user_123",
 *     totalLimit: 500,
 *     expiresIn: "24h",
 *     currency: "USD",
 *   });
 *
 *   const result = await client.authorize({
 *     sessionToken: budget.sessionToken!,
 *     agentId: "agent_flight_booker",
 *     actionType: "PURCHASE",
 *     description: "Book NYC flight",
 *     requestedQuantity: 299,
 *     idempotencyKey: "txn-abc-001",
 *   });
 *
 *   if (result.isAuthorized) {
 *     // ... execute the transaction ...
 *     await client.confirmEvent({ eventId: result.eventId, confirmedQuantity: 299 });
 *   }
 */

import { FiGuardApiError, FiGuardConnectionError } from "./errors";
import {
  AllocationResponse,
  AuthorizationResult,
  Budget,
  DelegationToken,
  LedgerPage,
  SpendEventResponse,
  SpendTree,
  VoidResult,
  makeBudget,
  makeAuthorizationResult,
  makeDelegationToken,
  makeSpendEvent,
  makeSpendTreeNode,
} from "./models";

const MAX_RETRIES = 3;
const RETRY_BACKOFF_BASE_MS = 1000;

// ---------------------------------------------------------------------------
// Public option types
// ---------------------------------------------------------------------------

export interface FiGuardClientOptions {
  apiKey: string;
  baseUrl?: string;
  /** Per-request timeout in milliseconds (default: 30_000). */
  timeoutMs?: number;
}

export interface AllocationInput {
  category: string;
  limit: number;
  /** Defaults to CATEGORY_CONSTRAINED if omitted. */
  enforcementMode?: "OPEN" | "CATEGORY_CONSTRAINED" | "STRICT" | "SOFT";
  allowedCategories?: string[];
  forbiddenItemTypes?: string[];
}

export interface CreateBudgetOptions {
  userId: string;
  totalLimit: number;
  /** Absolute ISO 8601 expiry. Mutually exclusive with expiresIn. */
  expiresAt?: string;
  /**
   * Relative duration from now.
   * Accepts "24h", "7d", "30m", or a number of seconds.
   * Mutually exclusive with expiresAt.
   */
  expiresIn?: string | number;
  currency?: string;
  unit?: string;
  intentContext?: string;
  intentTags?: string[];
  externalReference?: string;
  softLimit?: number;
  maxTransactionQuantity?: number;
  authorizationExpirySeconds?: number;
  anomalyDetectionEnabled?: boolean;
  /**
   * When false, anomaly detection fires ANOMALY_DETECTED webhook and denies the
   * request but does NOT pause the budget (advisory mode). Default: true.
   * Only meaningful when anomalyDetectionEnabled is true.
   */
  autoPauseOnAnomaly?: boolean;
  entityDedupEnabled?: boolean;
  allocations?: AllocationInput[];
  metadata?: Record<string, unknown>;
}

export interface DelegationCapInput {
  category: string;
  limit: number;
}

export interface CreateDelegationTokenOptions {
  budgetId: string;
  label: string;
  /**
   * Per-category spend caps for the sub-agent.
   * Only listed categories are cap-enforced at the delegation level.
   * Categories not listed pass through to the fleet allocation only.
   */
  caps: DelegationCapInput[];
}

export interface ExtendBudgetOptions {
  budgetId: string;
  /** Absolute ISO 8601 expiry. Mutually exclusive with expiresIn. */
  expiresAt?: string;
  /**
   * Relative duration from now (e.g. "2h", "30m").
   * Mutually exclusive with expiresAt.
   */
  expiresIn?: string | number;
}

export interface AuthorizeOptions {
  sessionToken: string;
  agentId: string;
  actionType: string;
  description: string;
  requestedQuantity: number;
  /**
   * Optional. A unique key for this request so retries are safe and never
   * double-spend. When omitted, a UUID v4 is generated automatically. Pass an
   * explicit key when you need idempotency across retries (e.g. store it before
   * the first attempt, reuse on retry).
   */
  idempotencyKey?: string;
  currency?: string;
  agentType?: string;
  intentContext?: string;
  entityId?: string;
  claimedCategory?: string;
  claimedItemType?: string;
  parentEventId?: string;
  traceId?: string;
  metadata?: Record<string, unknown>;
  /**
   * When true, all enforcement checks run and a full AUTHORIZED/DENIED result
   * is returned, but nothing is written to the ledger and no webhooks fire.
   * Use during integration testing.
   */
  dryRun?: boolean;
}

export interface ConfirmEventOptions {
  eventId: string;
  /** Actual quantity consumed — may differ from the authorized amount. */
  confirmedQuantity: number;
  externalTransactionId?: string;
}

export interface FailEventOptions {
  eventId: string;
  reason: string;
  errorMessage?: string;
}

export interface VoidEventOptions {
  eventId: string;
  reason: string;
  /** When true, also void child events in the causal chain. */
  voidChildEvents?: boolean;
}

export interface ResumeBudgetOptions {
  budgetId: string;
  /** Required human-readable reason for the override. */
  overrideReason: string;
  overrideBy?: string;
}

export interface GetLedgerOptions {
  budgetId: string;
  page?: number;
  size?: number;
  /** Filter by decision: AUTHORIZED, CONFIRMED, DENIED, VOIDED, FAILED. */
  decision?: string;
  traceId?: string;
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

export class FiGuardClient {
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly timeoutMs: number;

  constructor({ apiKey, baseUrl = "http://localhost:8080", timeoutMs = 30_000 }: FiGuardClientOptions) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.replace(/\/$/, "");
    this.timeoutMs = timeoutMs;
  }

  // -------------------------------------------------------------------------
  // Budget management
  // -------------------------------------------------------------------------

  async createBudget(options: CreateBudgetOptions): Promise<Budget> {
    const body: Record<string, unknown> = {
      userId: options.userId,
      totalLimit: options.totalLimit,
      expiresAt: resolveExpiresAt(options.expiresAt, options.expiresIn),
    };
    if (options.currency !== undefined) body["currency"] = options.currency;
    if (options.unit !== undefined) body["unit"] = options.unit;
    if (options.intentContext !== undefined) body["intentContext"] = options.intentContext;
    if (options.intentTags !== undefined) body["intentTags"] = options.intentTags;
    if (options.externalReference !== undefined) body["externalReference"] = options.externalReference;
    if (options.softLimit !== undefined) body["softLimit"] = options.softLimit;
    if (options.maxTransactionQuantity !== undefined) body["maxTransactionQuantity"] = options.maxTransactionQuantity;
    if (options.authorizationExpirySeconds !== undefined) body["authorizationExpirySeconds"] = options.authorizationExpirySeconds;
    if (options.anomalyDetectionEnabled) body["anomalyDetectionEnabled"] = true;
    if (options.autoPauseOnAnomaly === false) body["autoPauseOnAnomaly"] = false;
    if (options.entityDedupEnabled) body["entityDedupEnabled"] = true;
    if (options.allocations !== undefined) body["allocations"] = options.allocations;
    if (options.metadata !== undefined) body["metadata"] = options.metadata;

    const data = await this.request("POST", "/api/v1/budgets", { body, retryable: true });
    return makeBudget(data);
  }

  async getBudget(budgetId: string): Promise<Budget> {
    const data = await this.request("GET", `/api/v1/budgets/${budgetId}`, { retryable: true });
    return makeBudget(data);
  }

  async resumeBudget(options: ResumeBudgetOptions): Promise<Budget> {
    const body: Record<string, unknown> = { overrideReason: options.overrideReason };
    if (options.overrideBy !== undefined) body["overrideBy"] = options.overrideBy;
    const data = await this.request("POST", `/api/v1/budgets/${options.budgetId}/resume`, {
      body,
      retryable: true,
    });
    return makeBudget(data);
  }

  /**
   * Extend a budget's expiry window.
   *
   * The new expiresAt must be later than the current one and at most 24 hours
   * from now (the same cap as creation — extend can be called repeatedly).
   *
   * @throws FiGuardApiError HTTP 409 if budget is CANCELLED or EXHAUSTED.
   * @throws FiGuardApiError HTTP 400 if expiresAt is before the current one.
   */
  async extendBudget(options: ExtendBudgetOptions): Promise<Budget> {
    const body: Record<string, unknown> = {
      expiresAt: resolveExpiresAt(options.expiresAt, options.expiresIn),
    };
    const data = await this.request("POST", `/api/v1/budgets/${options.budgetId}/extend`, {
      body,
      retryable: false,
    });
    return makeBudget(data);
  }

  /**
   * Cancel up to 100 budgets in a single call.
   *
   * Already-terminal budgets (EXPIRED, CANCELLED, EXHAUSTED) are included in
   * the response without raising an error.
   *
   * @throws FiGuardApiError HTTP 400 if the list is empty or exceeds 100 items.
   */
  async cancelBatch(budgetIds: string[]): Promise<Budget[]> {
    const body: Record<string, unknown> = { budgetIds };
    const data = await this.request("POST", "/api/v1/budgets/cancel-batch", {
      body,
      retryable: false,
    });
    return (data as unknown as unknown[]).map((b) => makeBudget(b as Record<string, unknown>));
  }

  // -------------------------------------------------------------------------
  // Delegation tokens
  // -------------------------------------------------------------------------

  /**
   * Create a scoped delegation token for a fleet budget.
   *
   * The sub-agent calls authorize() with this token exactly as it would with a normal
   * session token. FiGuard resolves the parent budget and enforces both the per-token
   * caps and the fleet-level allocations transparently.
   *
   * The session_token is returned once — hand it to the sub-agent immediately.
   */
  async createDelegationToken(options: CreateDelegationTokenOptions): Promise<DelegationToken> {
    const body: Record<string, unknown> = {
      label: options.label,
      caps: options.caps,
    };
    const data = await this.request(
      "POST",
      `/api/v1/budgets/${options.budgetId}/delegation-tokens`,
      { body, retryable: false },
    );
    return makeDelegationToken(data);
  }

  async getDelegationToken(tokenId: string): Promise<DelegationToken> {
    const data = await this.request("GET", `/api/v1/delegation-tokens/${tokenId}`, {
      retryable: true,
    });
    return makeDelegationToken(data);
  }

  async listDelegationTokens(budgetId: string): Promise<DelegationToken[]> {
    const data = await this.request(
      "GET",
      `/api/v1/budgets/${budgetId}/delegation-tokens`,
      { retryable: true },
    );
    return (data as unknown as unknown[]).map((t) =>
      makeDelegationToken(t as Record<string, unknown>),
    );
  }

  /**
   * Revoke a delegation token immediately.
   *
   * Any subsequent authorize() call with this token returns INVALID_SESSION_TOKEN.
   * Already-authorized events continue their lifecycle normally.
   * Fires DELEGATION_TOKEN_REVOKED webhook. Idempotent.
   */
  async revokeDelegationToken(tokenId: string): Promise<DelegationToken> {
    const data = await this.request("DELETE", `/api/v1/delegation-tokens/${tokenId}`, {
      retryable: false,
    });
    return makeDelegationToken(data);
  }

  async rotateSessionToken(budgetId: string): Promise<string> {
    const data = await this.request("POST", `/api/v1/budgets/${budgetId}/rotate-token`, {
      retryable: true,
    });
    return data["sessionToken"] as string;
  }

  // -------------------------------------------------------------------------
  // Resource budget convenience methods
  // -------------------------------------------------------------------------

  /**
   * Create a resource budget for tracking token usage.
   *
   * Anomaly detection is disabled for token budgets — thresholds calibrated
   * for dollar amounts produce false positives on token counts.
   *
   * @param model      LLM model identifier stored in metadata (e.g. "gpt-4o").
   * @param maxTokens  Total token cap for the budget.
   * @param expiresIn  Relative duration (e.g. "2h", "30m"). Mutually exclusive with expiresAt.
   * @param expiresAt  Absolute ISO 8601 expiry. Mutually exclusive with expiresIn.
   *
   * @example
   * const budget = await client.createTokenBudget({ model: "gpt-4o", maxTokens: 50_000, expiresIn: "2h" });
   */
  async createTokenBudget(options: {
    model: string;
    maxTokens: number;
    expiresIn?: string | number;
    expiresAt?: string;
    userId?: string;
    intentContext?: string;
    externalReference?: string;
    allocations?: AllocationInput[];
    metadata?: Record<string, unknown>;
  }): Promise<Budget> {
    const meta: Record<string, unknown> = { model: options.model, ...options.metadata };
    return this.createBudget({
      userId: options.userId ?? "agent",
      totalLimit: options.maxTokens,
      expiresIn: options.expiresIn,
      expiresAt: options.expiresAt,
      unit: "tokens",
      intentContext: options.intentContext,
      externalReference: options.externalReference,
      anomalyDetectionEnabled: false,
      allocations: options.allocations,
      metadata: meta,
    });
  }

  /**
   * Pre-flight authorization for a token budget.
   *
   * Convenience wrapper around {@link authorize} for resource (token) budgets.
   * Omits `currency` so the currency-mismatch check is skipped server-side.
   *
   * After the LLM call completes, call {@link confirmTokens} with the actual count.
   *
   * @example
   * const auth = await client.authorizeTokens({
   *   sessionToken: budget.sessionToken!,
   *   agentId: "summarizer",
   *   estimatedTokens: 4_000,
   *   model: "gpt-4o",
   *   idempotencyKey: "run-abc-step-1",
   * });
   * if (auth.isAuthorized) {
   *   const resp = await openai.chat(...);
   *   await client.confirmTokens(auth.eventId, resp.usage.total_tokens);
   * }
   */
  async authorizeTokens(options: {
    sessionToken: string;
    agentId: string;
    estimatedTokens: number;
    model: string;
    idempotencyKey?: string;
    description?: string;
    claimedCategory?: string;
    traceId?: string;
    dryRun?: boolean;
  }): Promise<AuthorizationResult> {
    return this.authorize({
      sessionToken: options.sessionToken,
      agentId: options.agentId,
      actionType: "LLM_CALL",
      description: options.description ?? `LLM call via ${options.model} — estimated ${options.estimatedTokens.toLocaleString()} tokens`,
      requestedQuantity: options.estimatedTokens,
      idempotencyKey: options.idempotencyKey,
      claimedCategory: options.claimedCategory,
      traceId: options.traceId,
      dryRun: options.dryRun,
    });
  }

  /**
   * Confirm a token authorization with the actual token count.
   *
   * @param eventId      The eventId from authorizeTokens.
   * @param actualTokens Real token count from the LLM response (e.g. response.usage.total_tokens).
   */
  async confirmTokens(eventId: string, actualTokens: number): Promise<SpendEventResponse> {
    return this.confirmEvent({ eventId, confirmedQuantity: actualTokens });
  }

  // -------------------------------------------------------------------------
  // Replay
  // -------------------------------------------------------------------------

  /**
   * Replay all events for a budget in chronological order.
   *
   * Returns each event with the projected budget state after it applied.
   * Pure read — does not affect any budget state.
   */
  async replayBudget(options: {
    budgetId: string;
    from?: string;
    until?: string;
    includeDenied?: boolean;
    includeStateSnapshots?: boolean;
    pageSize?: number;
    pageToken?: string;
  }): Promise<Record<string, unknown>> {
    const params: Record<string, string> = {
      includeDenied: String(options.includeDenied ?? true),
      includeStateSnapshots: String(options.includeStateSnapshots ?? true),
      pageSize: String(Math.min(options.pageSize ?? 100, 500)),
    };
    if (options.from)       params["from"]       = options.from;
    if (options.until)      params["until"]      = options.until;
    if (options.pageToken)  params["pageToken"]  = options.pageToken;

    const query = new URLSearchParams(params).toString();
    return await this.request("GET", `/api/v1/budgets/${options.budgetId}/replay?${query}`, {
      retryable: true,
    });
  }

  /**
   * Project the budget state to a specific point in time.
   *
   * @param at ISO 8601 timestamp to project state to.
   */
  async getBudgetStateAt(budgetId: string, at: string): Promise<Record<string, unknown>> {
    const query = new URLSearchParams({ at }).toString();
    return await this.request("GET", `/api/v1/budgets/${budgetId}/replay/state?${query}`, {
      retryable: true,
    });
  }

  /**
   * Return events in chronological order without state snapshots.
   * Lighter than replayBudget — use when you need timing but not projected state.
   */
  async getBudgetTimeline(options: {
    budgetId: string;
    from?: string;
    until?: string;
  }): Promise<Record<string, unknown>> {
    const params: Record<string, string> = {};
    if (options.from)  params["from"]  = options.from;
    if (options.until) params["until"] = options.until;
    const query = new URLSearchParams(params).toString();
    const url = query
      ? `/api/v1/budgets/${options.budgetId}/replay/timeline?${query}`
      : `/api/v1/budgets/${options.budgetId}/replay/timeline`;
    return await this.request("GET", url, { retryable: true });
  }

  /**
   * Replay actual authorized events against a hypothetical policy.
   *
   * Answers: "If I had configured the budget differently, how many
   * transactions would have been denied?"
   *
   * Provide exactly one of hypotheticalPolicy or manifestVersion.
   *
   * @example
   * const result = await client.replayCounterfactual({
   *   budgetId: "budget_abc",
   *   hypotheticalPolicy: { totalLimit: 4000, maxTransactionQuantity: 500 },
   * });
   * console.log(`${result.hypotheticalPolicySummary.additionalDenials} extra denials`);
   */
  async replayCounterfactual(options: {
    budgetId: string;
    hypotheticalPolicy?: {
      totalLimit?: number;
      allocations?: Array<{ category: string; limit: number; enforcementMode?: string }>;
      maxTransactionQuantity?: number;
      anomalyDetectionEnabled?: boolean;
    };
    manifestVersion?: string;
    from?: string;
    until?: string;
  }): Promise<Record<string, unknown>> {
    const body: Record<string, unknown> = {};
    if (options.hypotheticalPolicy) body["hypotheticalPolicy"] = options.hypotheticalPolicy;
    if (options.manifestVersion)    body["manifestVersion"]    = options.manifestVersion;
    if (options.from)               body["from"]               = options.from;
    if (options.until)              body["until"]              = options.until;

    return await this.request(
      "POST",
      `/api/v1/budgets/${options.budgetId}/replay/counterfactual`,
      { body, retryable: false },
    );
  }

  // -------------------------------------------------------------------------
  // Authorization
  // -------------------------------------------------------------------------

  async authorize(options: AuthorizeOptions): Promise<AuthorizationResult> {
    const idempotencyKey =
      options.idempotencyKey && options.idempotencyKey.trim()
        ? options.idempotencyKey
        : crypto.randomUUID();

    const body: Record<string, unknown> = {
      agentId: options.agentId,
      actionType: options.actionType,
      description: options.description,
      requestedQuantity: options.requestedQuantity,
      idempotencyKey,
    };
    if (options.currency !== undefined) body["currency"] = options.currency;
    if (options.agentType !== undefined) body["agentType"] = options.agentType;
    if (options.intentContext !== undefined) body["intentContext"] = options.intentContext;
    if (options.entityId !== undefined) body["entityId"] = options.entityId;
    if (options.claimedCategory !== undefined) body["claimedCategory"] = options.claimedCategory;
    if (options.claimedItemType !== undefined) body["claimedItemType"] = options.claimedItemType;
    if (options.parentEventId !== undefined) body["parentEventId"] = options.parentEventId;
    if (options.traceId !== undefined) body["traceId"] = options.traceId;
    if (options.metadata !== undefined) body["metadata"] = options.metadata;
    if (options.dryRun) body["dryRun"] = true;

    const data = await this.request("POST", "/api/v1/authorize", {
      body,
      headers: { "X-Session-Token": options.sessionToken },
      retryable: true,
    });
    return makeAuthorizationResult(data);
  }

  // -------------------------------------------------------------------------
  // Payment lifecycle
  // -------------------------------------------------------------------------

  async confirmEvent(options: ConfirmEventOptions): Promise<SpendEventResponse> {
    const body: Record<string, unknown> = { confirmedQuantity: options.confirmedQuantity };
    if (options.externalTransactionId !== undefined) body["externalTransactionId"] = options.externalTransactionId;
    const data = await this.request("POST", `/api/v1/events/${options.eventId}/confirm`, {
      body,
      retryable: true,
    });
    return makeSpendEvent(data);
  }

  async failEvent(options: FailEventOptions): Promise<SpendEventResponse> {
    const body: Record<string, unknown> = { reason: options.reason };
    if (options.errorMessage !== undefined) body["errorMessage"] = options.errorMessage;
    const data = await this.request("POST", `/api/v1/events/${options.eventId}/fail`, {
      body,
      retryable: true,
    });
    return makeSpendEvent(data);
  }

  async voidEvent(options: VoidEventOptions): Promise<VoidResult> {
    const body: Record<string, unknown> = {
      reason: options.reason,
      voidChildEvents: options.voidChildEvents ?? false,
    };
    const data = await this.request("POST", `/api/v1/events/${options.eventId}/void`, {
      body,
      retryable: true,
    });
    const event = makeSpendEvent(data);
    return { event, isVoided: event.decision === "VOIDED" };
  }

  // -------------------------------------------------------------------------
  // Ledger & reporting
  // -------------------------------------------------------------------------

  async getLedger(options: GetLedgerOptions): Promise<LedgerPage> {
    const params = new URLSearchParams();
    params.set("page", String(options.page ?? 0));
    params.set("size", String(options.size ?? 20));
    if (options.decision) params.set("decision", options.decision);
    if (options.traceId) params.set("traceId", options.traceId);

    const data = await this.request(
      "GET",
      `/api/v1/budgets/${options.budgetId}/ledger?${params.toString()}`,
      { retryable: true },
    );
    const events = ((data["content"] as Record<string, unknown>[] | undefined) ?? []).map(makeSpendEvent);
    const page = (data["number"] as number | undefined) ?? (options.page ?? 0);
    const totalPages = (data["totalPages"] as number | undefined) ?? 0;
    return {
      events,
      totalElements: (data["totalElements"] as number | undefined) ?? 0,
      totalPages,
      page,
      size: (data["size"] as number | undefined) ?? (options.size ?? 20),
      hasNext: page < totalPages - 1,
    };
  }

  async getSpendTree(budgetId: string): Promise<SpendTree> {
    const data = await this.request("GET", `/api/v1/budgets/${budgetId}/tree`, { retryable: true });
    const roots = ((data["roots"] as Record<string, unknown>[] | undefined) ?? []).map(makeSpendTreeNode);
    return {
      budgetId,
      roots,
      totalEvents: (data["totalEvents"] as number | undefined) ?? 0,
    };
  }

  async getReceiptUrl(budgetId: string): Promise<string> {
    const data = await this.request("GET", `/api/v1/budgets/${budgetId}/receipt`, {
      retryable: true,
    });
    return data["receiptUrl"] as string;
  }

  // -------------------------------------------------------------------------
  // Internal HTTP layer
  // -------------------------------------------------------------------------

  private async request(
    method: string,
    path: string,
    options: {
      body?: Record<string, unknown>;
      headers?: Record<string, string>;
      retryable?: boolean;
    } = {},
  ): Promise<Record<string, unknown>> {
    const url = `${this.baseUrl}${path}`;
    const attempts = options.retryable ? MAX_RETRIES : 1;
    let lastError: unknown;

    for (let attempt = 0; attempt < attempts; attempt++) {
      if (attempt > 0) {
        const delay = RETRY_BACKOFF_BASE_MS * Math.pow(2, attempt - 1);
        await sleep(delay);
      }

      let resp: Response;
      try {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), this.timeoutMs);
        try {
          resp = await fetch(url, {
            method,
            headers: {
              "Content-Type": "application/json",
              Accept: "application/json",
              "X-Agent-Budget-Key": this.apiKey,
              ...options.headers,
            },
            body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
            signal: controller.signal,
          });
        } finally {
          clearTimeout(timer);
        }
      } catch (err) {
        lastError = err;
        const msg = err instanceof Error ? err.message : String(err);
        if (attempt < attempts - 1) continue;
        throw new FiGuardConnectionError(
          `All ${attempts} attempt(s) failed for ${method} ${path}: ${msg}`,
        );
      }

      // 5xx — retry if we have attempts left
      if (resp.status >= 500 && attempt < attempts - 1) {
        lastError = new FiGuardApiError(resp.status, `Server error ${resp.status}`);
        continue;
      }

      return await handleResponse(resp);
    }

    // Should not reach here, but satisfy TypeScript
    throw lastError instanceof Error
      ? lastError
      : new FiGuardConnectionError(`Request failed: ${method} ${path}`);
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function handleResponse(resp: Response): Promise<Record<string, unknown>> {
  if (resp.status >= 400) {
    let raw: unknown;
    let message = resp.statusText;
    try {
      raw = await resp.json();
      if (raw && typeof raw === "object") {
        const obj = raw as Record<string, unknown>;
        message = (obj["message"] as string | undefined) ?? (obj["error"] as string | undefined) ?? message;
      }
    } catch {
      try {
        message = await resp.text();
      } catch {
        // keep statusText
      }
    }
    throw new FiGuardApiError(resp.status, message, raw);
  }

  if (resp.status === 204 || resp.headers.get("content-length") === "0") {
    return {};
  }

  return (await resp.json()) as Record<string, unknown>;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Resolve expiresAt or expiresIn to an absolute ISO 8601 timestamp.
 *
 * Accepted expiresIn formats:
 *   "24h" | "7d" | "30m"  — hours, days, or minutes suffix
 *   number                 — seconds from now
 */
export function resolveExpiresAt(
  expiresAt?: string,
  expiresIn?: string | number,
): string {
  if (expiresAt !== undefined && expiresIn !== undefined) {
    throw new Error("Pass either expiresAt or expiresIn, not both.");
  }
  if (expiresAt !== undefined) return expiresAt;
  if (expiresIn === undefined) throw new Error("Either expiresAt or expiresIn is required.");

  const now = Date.now();

  if (typeof expiresIn === "number") {
    return new Date(now + expiresIn * 1000).toISOString().replace(/\.\d{3}Z$/, "Z");
  }

  const match = expiresIn.trim().match(/^(\d+)([hmd])$/);
  if (!match) {
    throw new Error(
      `Invalid expiresIn: "${expiresIn}". Use "24h", "7d", "30m", or a number of seconds.`,
    );
  }
  const n = parseInt(match[1], 10);
  const unit = match[2];
  let ms: number;
  if (unit === "h") ms = n * 60 * 60 * 1000;
  else if (unit === "d") ms = n * 24 * 60 * 60 * 1000;
  else ms = n * 60 * 1000; // "m"

  return new Date(now + ms).toISOString().replace(/\.\d{3}Z$/, "Z");
}

// Re-export model types that handlers need
export type { AllocationResponse, AuthorizationResult, Budget, DelegationToken, LedgerPage, SpendEventResponse, SpendTree, VoidResult };

// ---------------------------------------------------------------------------
// Allocation builder helper
// ---------------------------------------------------------------------------

export interface AllocationPercentageInput {
  category: string;
  percent: number;
  enforcementMode?: string;
  allowedCategories?: string[];
  forbiddenItemTypes?: string[];
}

/**
 * Build an allocations array from a total and an array of category/percentage entries.
 *
 * The last bucket absorbs the floating-point remainder so limits always sum to
 * exactly ``total``, avoiding ``$333.33 × 3 ≠ $1000.00`` precision errors.
 *
 * @param total       Total budget limit (same unit as the budget's totalLimit).
 * @param allocations Array of ``{ category, percent, ...optional fields }`` — percents must sum to 100.
 *
 * @throws Error if percents do not sum to 100 (within 0.001 tolerance).
 *
 * @example
 * const allocs = buildAllocationsFromPercentages(1000, [
 *   { category: "flight", percent: 60 },
 *   { category: "hotel",  percent: 30 },
 *   { category: "ground", percent: 10 },
 * ]);
 * // → [{ category: "flight", limit: 600 }, { category: "hotel", limit: 300 }, { category: "ground", limit: 100 }]
 */
export function buildAllocationsFromPercentages(
  total: number,
  allocations: AllocationPercentageInput[],
): Array<Record<string, unknown>> {
  const pctSum = allocations.reduce((s, a) => s + a.percent, 0);
  if (Math.abs(pctSum - 100) > 0.001) {
    throw new Error(
      `Percentages must sum to 100, got ${pctSum.toFixed(4)}. ` +
      "Adjust your values so they add up to exactly 100.",
    );
  }

  const result: Array<Record<string, unknown>> = [];
  let assigned = 0;

  for (let i = 0; i < allocations.length; i++) {
    const { category, percent, enforcementMode, allowedCategories, forbiddenItemTypes } = allocations[i];
    let limit: number;
    if (i < allocations.length - 1) {
      // Round to 4 decimal places — matches server's NUMERIC(19,4) column
      limit = Math.round(percent / 100 * total * 10000) / 10000;
      assigned += limit;
    } else {
      // Last bucket absorbs rounding remainder
      limit = Math.round((total - assigned) * 10000) / 10000;
    }

    const alloc: Record<string, unknown> = { category, limit };
    if (enforcementMode) alloc["enforcementMode"] = enforcementMode;
    if (allowedCategories?.length) alloc["allowedCategories"] = allowedCategories;
    if (forbiddenItemTypes?.length) alloc["forbiddenItemTypes"] = forbiddenItemTypes;
    result.push(alloc);
  }

  return result;
}
