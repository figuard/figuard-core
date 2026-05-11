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
  LedgerPage,
  SpendEventResponse,
  SpendTree,
  VoidResult,
  makeBudget,
  makeAuthorizationResult,
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
  entityDedupEnabled?: boolean;
  allocations?: AllocationInput[];
  metadata?: Record<string, unknown>;
}

export interface AuthorizeOptions {
  sessionToken: string;
  agentId: string;
  actionType: string;
  description: string;
  requestedQuantity: number;
  /**
   * Required. A unique key for this request so retries are safe and never
   * double-spend. Generate once per logical spend intent (e.g. crypto.randomUUID())
   * and reuse on retries.
   */
  idempotencyKey: string;
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

  async rotateSessionToken(budgetId: string): Promise<string> {
    const data = await this.request("POST", `/api/v1/budgets/${budgetId}/rotate-token`, {
      retryable: true,
    });
    return data["sessionToken"] as string;
  }

  // -------------------------------------------------------------------------
  // Authorization
  // -------------------------------------------------------------------------

  async authorize(options: AuthorizeOptions): Promise<AuthorizationResult> {
    if (!options.idempotencyKey || !options.idempotencyKey.trim()) {
      throw new Error(
        "idempotencyKey is required for authorize(). " +
          "Generate one per logical spend intent (e.g. crypto.randomUUID()) and reuse on retries.",
      );
    }

    const body: Record<string, unknown> = {
      agentId: options.agentId,
      actionType: options.actionType,
      description: options.description,
      requestedQuantity: options.requestedQuantity,
      idempotencyKey: options.idempotencyKey,
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
export type { AllocationResponse, AuthorizationResult, Budget, LedgerPage, SpendEventResponse, SpendTree, VoidResult };
