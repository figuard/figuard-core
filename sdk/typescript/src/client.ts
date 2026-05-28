/**
 * FiGuard TypeScript client.
 *
 * Zero-config demo (no account needed):
 *   import { FiGuardClient } from "figuard";
 *   const client = new FiGuardClient();  // connects to shared public sandbox
 *
 * Configuration resolution order:
 *   1. Explicit { apiKey, baseUrl } options
 *   2. FIGUARD_API_KEY / FIGUARD_BASE_URL environment variables
 *   3. Shared public sandbox (sb_live_demo / figuard-sandbox-g1ha.onrender.com)
 *
 * Full usage:
 *   import { FiGuardClient } from "figuard";
 *
 *   const client = new FiGuardClient({ apiKey: "fg_live_..." });
 *
 *   const budget = await client.createBudget({
 *     userId: "user_123",
 *     totalLimit: 500,
 *     expiresIn: "24h",
 *     currency: "USD",
 *   });
 *
 *   const result = await client.authorize({
 *     sessionToken: budget.tokens![0].sessionToken!,
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
  ApiKey,
  AuthorizationResult,
  Budget,
  BudgetFundingResult,
  BudgetStateSnapshot,
  BudgetTimeline,
  DelegationToken,
  EntitlementItem,
  LedgerPage,
  SpendEventResponse,
  SpendTree,
  Subscription,
  VoidResult,
  VoidTreeResult,
  WebhookConfig,
  WebhookDelivery,
  WebhookTestResult,
  makeApiKey,
  makeAuthorizationResult,
  makeBudget,
  makeBudgetFundingResult,
  makeBudgetStateSnapshot,
  makeBudgetTimeline,
  makeDelegationToken,
  makeEntitlementItem,
  makeSpendEvent,
  makeSpendTreeNode,
  makeSubscription,
  makeVoidTreeResult,
  makeWebhookConfig,
  makeWebhookDelivery,
  makeWebhookTestResult,
} from "./models";
import {
  getCurrentTraceId,
  withAuthorizeSpan,
  finishAuthorizeSpan,
  withLifecycleSpan,
  withVoidTreeSpan,
  finishVoidTreeSpan,
} from "./telemetry";

const MAX_RETRIES = 3;
const RETRY_BACKOFF_BASE_MS = 1000;

// Sandbox defaults — used when no configuration is supplied
const SANDBOX_API_KEY = "sb_live_demo";
const SANDBOX_BASE_URL = "https://figuard-sandbox-g1ha.onrender.com";
let _sandboxWarnShown = false;

// ---------------------------------------------------------------------------
// Public option types
// ---------------------------------------------------------------------------

export interface FiGuardClientOptions {
  /**
   * Your `fg_live_...` or `fg_test_...` API key.
   * Defaults to `FIGUARD_API_KEY` env var, then the shared public sandbox.
   */
  apiKey?: string;
  /**
   * Override for self-hosted deployments.
   * Defaults to `FIGUARD_BASE_URL` env var, then the shared public sandbox URL.
   */
  baseUrl?: string;
  /** Per-request timeout in milliseconds (default: 30_000). */
  timeoutMs?: number;
  /**
   * When `true`, a `FiGuardConnectionError` in `authorize()` returns a synthetic
   * AUTHORIZED result instead of throwing, so agent pipelines keep running when
   * FiGuard is temporarily unreachable.
   *
   * The returned `AuthorizationResult` will have `isFallback: true` and an `eventId`
   * prefixed with `"fallback_"`. Subsequent `confirmEvent/failEvent/voidEvent` calls
   * with that id are silently skipped — no ledger entry was created.
   *
   * Default: `false` (fail closed — throw on server errors).
   */
  failOpen?: boolean;
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
  velocityMaxPerMinute?: number;
  velocityMaxAmountPerHour?: number;
  velocityMaxPerDay?: number;
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
   * Optional per-chain spend cap. Only meaningful on root authorize() calls
   * (i.e. when parentEventId is omitted). When set, the total AUTHORIZED +
   * CONFIRMED spend across the entire causal chain rooted at this event is
   * checked against this ceiling on every subsequent child authorization.
   * Denied child requests receive SUBTREE_CAP_EXCEEDED with remaining capacity.
   */
  maxSubtreeQuantity?: number;
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

export interface VoidTreeOptions {
  /**
   * Root event ID — the orchestrator's authorization whose entire causal subtree
   * should be voided atomically.
   */
  eventId: string;
  /** Reason code written to every voided event's audit log. */
  reason: string;
}

export interface RecordExternalEventOptions {
  /** Budget to charge against. */
  budgetId: string;
  /** Who performed the action (e.g. "finance_manager_u123"). */
  agentId: string;
  /** Label for the action type (e.g. "PAYMENT", "REFUND"). */
  actionType: string;
  description: string;
  /** Actual amount spent (same unit as the budget). */
  quantity: number;
  /**
   * Unique key to prevent duplicate recording. Recommend using the external
   * system's transaction ID (e.g. QuickBooks transaction ID).
   */
  idempotencyKey: string;
  /** Optional category for audit and reporting. */
  claimedCategory?: string;
  /**
   * Origin: "HUMAN" for a person acting outside FiGuard, "EXTERNAL" for an
   * automated system. Defaults to "EXTERNAL".
   */
  source?: string;
  /**
   * When the action actually happened (ISO 8601). Defaults to now.
   * Use to backdate events recorded after the fact.
   */
  occurredAt?: string;
  metadata?: Record<string, unknown>;
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
  private readonly _failOpen: boolean;

  /**
   * Create a FiGuardClient.
   *
   * Configuration resolution order:
   * 1. Explicit `apiKey` / `baseUrl` options
   * 2. `FIGUARD_API_KEY` / `FIGUARD_BASE_URL` environment variables
   * 3. Shared public sandbox (zero-config demo mode)
   *
   * When the sandbox fallback is used, a one-time warning is printed to stdout.
   * Suppress it with `FIGUARD_SUPPRESS_SANDBOX_WARNING=1`.
   */
  constructor({ apiKey, baseUrl, timeoutMs = 30_000, failOpen = false }: FiGuardClientOptions = {}) {
    const resolvedKey =
      apiKey ??
      (typeof process !== "undefined" ? process.env["FIGUARD_API_KEY"] : undefined);
    const resolvedUrl =
      baseUrl ??
      (typeof process !== "undefined" ? process.env["FIGUARD_BASE_URL"] : undefined);

    if (!resolvedKey) {
      if (
        !_sandboxWarnShown &&
        (typeof process === "undefined" || !process.env["FIGUARD_SUPPRESS_SANDBOX_WARNING"])
      ) {
        console.log(
          "\n⚠️  FiGuard: No configuration found — connecting to the shared public sandbox.\n" +
          "    → Data is wiped periodically. Not for production use.\n" +
          "    → Self-host: https://figuard.io/docs/self-hosting\n" +
          "    → Suppress this warning: set FIGUARD_SUPPRESS_SANDBOX_WARNING=1\n"
        );
        _sandboxWarnShown = true;
      }
      this.apiKey = SANDBOX_API_KEY;
      this.baseUrl = (resolvedUrl ?? SANDBOX_BASE_URL).replace(/\/$/, "");
    } else {
      this.apiKey = resolvedKey;
      this.baseUrl = (resolvedUrl ?? SANDBOX_BASE_URL).replace(/\/$/, "");
    }
    this.timeoutMs = timeoutMs;
    this._failOpen = failOpen;
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
    if (options.velocityMaxPerMinute !== undefined) body["velocityMaxPerMinute"] = options.velocityMaxPerMinute;
    if (options.velocityMaxAmountPerHour !== undefined) body["velocityMaxAmountPerHour"] = options.velocityMaxAmountPerHour;
    if (options.velocityMaxPerDay !== undefined) body["velocityMaxPerDay"] = options.velocityMaxPerDay;
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
  // Fund budget
  // -------------------------------------------------------------------------

  /**
   * Adjust a budget's totalLimit in-place.
   *
   * @param operation
   *   - `CREDIT`      — add amount to totalLimit.
   *   - `DEBIT`       — subtract amount from totalLimit (rejected if result < quantitySpent).
   *   - `RESET`       — set totalLimit to exactly amount.
   *   - `RESET_SPENT` — zero quantitySpent and set totalLimit to amount; reactivates EXHAUSTED budgets.
   */
  async fundBudget(options: {
    budgetId: string;
    operation: "CREDIT" | "DEBIT" | "RESET" | "RESET_SPENT";
    amount: number;
    reason?: string;
  }): Promise<BudgetFundingResult> {
    const { budgetId, ...body } = options;
    const data = await this.request("POST", `/api/v1/budgets/${budgetId}/fund`, { body });
    return makeBudgetFundingResult(data as Record<string, unknown>);
  }

  // API keys
  // -------------------------------------------------------------------------

  /** List all API keys for this tenant. rawKey is never returned here. */
  async listApiKeys(): Promise<ApiKey[]> {
    const data = await this.request("GET", "/api/v1/api-keys");
    return (data as unknown as Record<string, unknown>[]).map(makeApiKey);
  }

  /**
   * Create a new API key. `rawKey` is populated once in the returned object — store it.
   * All subsequent reads return `undefined`.
   */
  async createApiKey(options: { description?: string } = {}): Promise<ApiKey> {
    const data = await this.request("POST", "/api/v1/api-keys", { body: options });
    return makeApiKey(data as Record<string, unknown>);
  }

  /** Revoke an API key. Idempotent. Row retained for audit. */
  async revokeApiKey(keyId: string): Promise<ApiKey> {
    const data = await this.request("POST", `/api/v1/api-keys/${keyId}/revoke`);
    return makeApiKey(data as Record<string, unknown>);
  }

  /**
   * Revoke the current key and issue a replacement atomically.
   * The new `rawKey` is returned once in the response.
   */
  async rotateApiKey(keyId: string): Promise<ApiKey> {
    const data = await this.request("POST", `/api/v1/api-keys/${keyId}/rotate`);
    return makeApiKey(data as Record<string, unknown>);
  }

  // Subscriptions & Entitlements
  // -------------------------------------------------------------------------

  /** List all subscriptions for this tenant. */
  async listSubscriptions(): Promise<Subscription[]> {
    const data = await this.request("GET", "/api/v1/subscriptions");
    return (data as unknown as Record<string, unknown>[]).map(makeSubscription);
  }

  /** Create a subscription. */
  async createSubscription(options: {
    externalSubscriberId: string;
    plan: string;
    /** MONTHLY | QUARTERLY | ANNUALLY */
    renewalPeriod: string;
    startsAt?: string;
  }): Promise<Subscription> {
    const data = await this.request("POST", "/api/v1/subscriptions", { body: options });
    return makeSubscription(data as Record<string, unknown>);
  }

  /** Get a subscription by its FiGuard ID. */
  async getSubscription(subscriptionId: string): Promise<Subscription> {
    const data = await this.request("GET", `/api/v1/subscriptions/${subscriptionId}`);
    return makeSubscription(data as Record<string, unknown>);
  }

  /** Look up a subscription by your own subscriber ID. */
  async getSubscriptionBySubscriber(externalSubscriberId: string): Promise<Subscription> {
    const data = await this.request(
      "GET",
      `/api/v1/subscriptions/by-subscriber/${externalSubscriberId}`,
    );
    return makeSubscription(data as Record<string, unknown>);
  }

  /**
   * Pause a subscription. All linked budgets will receive HTTP 402
   * (`SUBSCRIPTION_PAUSED`) on the next authorize call.
   */
  async pauseSubscription(subscriptionId: string): Promise<Subscription> {
    const data = await this.request("POST", `/api/v1/subscriptions/${subscriptionId}/pause`);
    return makeSubscription(data as Record<string, unknown>);
  }

  /** Resume a paused subscription. */
  async resumeSubscription(subscriptionId: string): Promise<Subscription> {
    const data = await this.request("POST", `/api/v1/subscriptions/${subscriptionId}/resume`);
    return makeSubscription(data as Record<string, unknown>);
  }

  /** Cancel a subscription. */
  async cancelSubscription(subscriptionId: string): Promise<Subscription> {
    const data = await this.request("POST", `/api/v1/subscriptions/${subscriptionId}/cancel`);
    return makeSubscription(data as Record<string, unknown>);
  }

  /** List all entitlement items for a subscription. */
  async listEntitlements(subscriptionId: string): Promise<EntitlementItem[]> {
    const data = await this.request(
      "GET",
      `/api/v1/subscriptions/${subscriptionId}/entitlements`,
    );
    return (data as unknown as Record<string, unknown>[]).map(makeEntitlementItem);
  }

  /** Add an entitlement item to a subscription. */
  async addEntitlement(options: {
    subscriptionId: string;
    category: string;
    periodLimit: number;
    /** BLOCK (deny at limit) | WARN_ONLY (fire webhook but allow). Default: BLOCK. */
    overagePolicy?: "BLOCK" | "WARN_ONLY";
    /** MONTHLY | QUARTERLY | ANNUALLY. Default: MONTHLY. */
    renewalPeriod?: string;
    /** Fire ENTITLEMENT_STATE_CHANGED webhook at this % consumed. E.g. 80. */
    warnAtPercentage?: number;
  }): Promise<EntitlementItem> {
    const { subscriptionId, ...body } = options;
    const data = await this.request(
      "POST",
      `/api/v1/subscriptions/${subscriptionId}/entitlements`,
      { body },
    );
    return makeEntitlementItem(data as Record<string, unknown>);
  }

  /** Get a single entitlement item including current consumption and state. */
  async getEntitlement(subscriptionId: string, entitlementItemId: string): Promise<EntitlementItem> {
    const data = await this.request(
      "GET",
      `/api/v1/subscriptions/${subscriptionId}/entitlements/${entitlementItemId}`,
    );
    return makeEntitlementItem(data as Record<string, unknown>);
  }

  /**
   * Manually reset an entitlement item's consumed counter to zero and advance nextRenewalAt.
   * Use for mid-period corrections or manual billing period control.
   */
  async resetEntitlement(subscriptionId: string, entitlementItemId: string): Promise<EntitlementItem> {
    const data = await this.request(
      "POST",
      `/api/v1/subscriptions/${subscriptionId}/entitlements/${entitlementItemId}/reset`,
    );
    return makeEntitlementItem(data as Record<string, unknown>);
  }

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
   *   sessionToken: budget.tokens![0].sessionToken!,
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
   * Project the budget's state at an exact point in time.
   *
   * Replays all ledger events up to `at` and returns the resulting balance
   * snapshot. Use for incident investigation or customer support queries:
   * *"What did this budget look like at 14:32 yesterday?"*
   *
   * @param budgetId  Budget to inspect.
   * @param at        ISO 8601 timestamp to project state to.
   * @returns `BudgetStateSnapshot` with running totals and per-allocation breakdowns.
   *
   * @example
   * const snapshot = await client.getBudgetStateAt("bdg_...", "2025-05-28T14:32:00Z");
   * console.log(`${snapshot.eventsApplied} events applied`);
   * console.log(`Available at that moment: ${snapshot.available}`);
   */
  async getBudgetStateAt(budgetId: string, at: string): Promise<BudgetStateSnapshot> {
    const query = new URLSearchParams({ at }).toString();
    const data = await this.request("GET", `/api/v1/budgets/${budgetId}/replay/state?${query}`, {
      retryable: true,
    });
    return makeBudgetStateSnapshot(data);
  }

  /**
   * Return a chronological event sequence for a budget, without state projections.
   *
   * Lighter and faster than a full replay — use when you need to see *what
   * happened and when*, not the projected balance after each step.
   *
   * @param options.from   Only include events at or after this ISO 8601 timestamp.
   * @param options.until  Only include events before or at this ISO 8601 timestamp.
   * @returns `BudgetTimeline` with ordered events and `millisSincePrevious` gaps.
   *
   * @example
   * const tl = await client.getBudgetTimeline({
   *   budgetId: "bdg_...",
   *   from: "2025-05-28T13:00:00Z",
   *   until: "2025-05-28T15:00:00Z",
   * });
   * for (const e of tl.timeline) {
   *   console.log(`[${e.createdAt}] ${e.decision.padEnd(12)} ${e.requestedQuantity}`);
   * }
   */
  async getBudgetTimeline(options: {
    budgetId: string;
    from?: string;
    until?: string;
  }): Promise<BudgetTimeline> {
    const params: Record<string, string> = {};
    if (options.from)  params["from"]  = options.from;
    if (options.until) params["until"] = options.until;
    const query = new URLSearchParams(params).toString();
    const url = query
      ? `/api/v1/budgets/${options.budgetId}/replay/timeline?${query}`
      : `/api/v1/budgets/${options.budgetId}/replay/timeline`;
    const data = await this.request("GET", url, { retryable: true });
    return makeBudgetTimeline(data);
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

    // Forward the active OTEL trace ID to the server for ledger correlation.
    // Caller-supplied traceId takes precedence; OTEL is used only as a fallback.
    const effectiveTraceId = options.traceId ?? getCurrentTraceId();

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
    if (effectiveTraceId !== undefined) body["traceId"] = effectiveTraceId;
    if (options.metadata !== undefined) body["metadata"] = options.metadata;
    if (options.maxSubtreeQuantity !== undefined) body["maxSubtreeQuantity"] = options.maxSubtreeQuantity;
    if (options.dryRun) body["dryRun"] = true;

    return withAuthorizeSpan(
      {
        agentId: options.agentId,
        actionType: options.actionType,
        requestedQuantity: options.requestedQuantity,
        claimedCategory: options.claimedCategory,
        parentEventId: options.parentEventId,
        dryRun: options.dryRun,
      },
      async (span) => {
        let result: AuthorizationResult;
        try {
          const data = await this.request("POST", "/api/v1/authorize", {
            body,
            headers: { "X-Session-Token": options.sessionToken },
            retryable: true,
          });
          result = makeAuthorizationResult(data);
        } catch (err) {
          if (this._failOpen && err instanceof FiGuardConnectionError) {
            console.warn(
              `figuard: server unreachable (failOpen=true) — authorizing agent=${options.agentId} ` +
              `action=${options.actionType} quantity=${options.requestedQuantity} as fallback. ` +
              `No ledger entry was created. Error: ${err.message}`,
            );
            result = makeFallbackAuthorizationResult(idempotencyKey, options.requestedQuantity);
          } else {
            throw err;
          }
        }
        finishAuthorizeSpan(span, result);
        return result;
      },
    );
  }

  // -------------------------------------------------------------------------
  // Payment lifecycle
  // -------------------------------------------------------------------------

  async confirmEvent(options: ConfirmEventOptions): Promise<SpendEventResponse> {
    if (options.eventId.startsWith("fallback_")) {
      return makeFallbackSpendEvent(options.eventId, "CONFIRMED", 0);
    }
    const body: Record<string, unknown> = { confirmedQuantity: options.confirmedQuantity };
    if (options.externalTransactionId !== undefined) body["externalTransactionId"] = options.externalTransactionId;
    return withLifecycleSpan(
      "figuard.confirm",
      options.eventId,
      { "figuard.confirmed_quantity": options.confirmedQuantity },
      async () => {
        const data = await this.request("POST", `/api/v1/events/${options.eventId}/confirm`, {
          body,
          retryable: true,
        });
        return makeSpendEvent(data);
      },
    );
  }

  async failEvent(options: FailEventOptions): Promise<SpendEventResponse> {
    if (options.eventId.startsWith("fallback_")) {
      return makeFallbackSpendEvent(options.eventId, "FAILED", 0);
    }
    const body: Record<string, unknown> = { reason: options.reason };
    if (options.errorMessage !== undefined) body["errorMessage"] = options.errorMessage;
    return withLifecycleSpan(
      "figuard.fail",
      options.eventId,
      { "figuard.reason": options.reason },
      async () => {
        const data = await this.request("POST", `/api/v1/events/${options.eventId}/fail`, {
          body,
          retryable: true,
        });
        return makeSpendEvent(data);
      },
    );
  }

  async voidEvent(options: VoidEventOptions): Promise<VoidResult> {
    if (options.eventId.startsWith("fallback_")) {
      const event = makeFallbackSpendEvent(options.eventId, "VOIDED", 0);
      return { event, isVoided: true };
    }
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

  /**
   * Atomically void a root event and every AUTHORIZED descendant in its causal
   * subtree — in a single server-side transaction.
   *
   * Use when an orchestration job is cancelled and you want to release all child
   * agent reservations at once instead of voiding each individually.
   *
   * CONFIRMED and already-VOIDED descendants are left untouched.
   * Throws HTTP 409 if any descendant has an externalTransactionId set (that
   * event must be refunded before the tree can be voided).
   */
  async voidTree(options: VoidTreeOptions): Promise<VoidTreeResult> {
    return withVoidTreeSpan(options.eventId, options.reason, async (span) => {
      const data = await this.request(
        "POST",
        `/api/v1/events/${options.eventId}/void-tree`,
        { body: { reason: options.reason }, retryable: true },
      );
      const result = makeVoidTreeResult(data);
      finishVoidTreeSpan(span, result);
      return result;
    });
  }

  // -------------------------------------------------------------------------
  // External events
  // -------------------------------------------------------------------------

  /**
   * Record a spend that happened outside the normal authorize → confirm flow.
   *
   * Creates a spend event directly in CONFIRMED state. Budget capacity limits are
   * NOT enforced — the money was already spent in an external system.
   * A SPEND_CONFIRMED webhook fires after recording.
   *
   * @throws FiGuardApiError HTTP 404 if budget not found; HTTP 409 on duplicate idempotency key.
   */
  async recordExternalEvent(options: RecordExternalEventOptions): Promise<SpendEventResponse> {
    const body: Record<string, unknown> = {
      budgetId: options.budgetId,
      agentId: options.agentId,
      actionType: options.actionType,
      description: options.description,
      quantity: options.quantity,
      idempotencyKey: options.idempotencyKey,
      source: options.source ?? "EXTERNAL",
    };
    if (options.claimedCategory !== undefined) body["claimedCategory"] = options.claimedCategory;
    if (options.occurredAt !== undefined) body["occurredAt"] = options.occurredAt;
    if (options.metadata !== undefined) body["metadata"] = options.metadata;

    const data = await this.request("POST", "/api/v1/events/external", { body, retryable: false });
    return makeSpendEvent(data);
  }

  // -------------------------------------------------------------------------
  // Webhook verification
  // -------------------------------------------------------------------------

  /**
   * Verify the HMAC-SHA256 signature on an incoming FiGuard webhook and return
   * the parsed payload.
   *
   * FiGuard signs every webhook with `X-Webhook-Signature: sha256=<hex>`.
   * Call this at the top of your webhook handler before processing.
   *
   * @param payload           Raw request body (Buffer or string).
   * @param signatureHeader   Value of the `X-Webhook-Signature` header.
   * @param secret            Your webhook secret from the webhook configuration.
   *
   * @returns Parsed JSON payload.
   * @throws Error with message "Webhook signature verification failed" on mismatch.
   *
   * @example
   * app.post("/webhooks/figuard", (req, res) => {
   *   let event: Record<string, unknown>;
   *   try {
   *     event = FiGuardClient.verifyWebhook(
   *       req.rawBody,
   *       req.headers["x-webhook-signature"] as string,
   *       process.env.FIGUARD_WEBHOOK_SECRET!,
   *     );
   *   } catch {
   *     return res.status(400).json({ error: "invalid signature" });
   *   }
   *   if (event["eventType"] === "SPEND_CONFIRMED") { ... }
   * });
   */
  static verifyWebhook(
    payload: Buffer | string,
    signatureHeader: string,
    secret: string,
  ): Record<string, unknown> {
    // Dynamic import of Node's crypto so this stays tree-shakeable in browser builds
    // that never call verifyWebhook (it's a server-side concern).
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const crypto = require("crypto") as typeof import("crypto");

    const body = typeof payload === "string" ? Buffer.from(payload, "utf8") : payload;

    if (!signatureHeader || !signatureHeader.startsWith("sha256=")) {
      throw new Error(
        "Missing or malformed X-Webhook-Signature header — expected 'sha256=<hex>'"
      );
    }
    const expectedHex = signatureHeader.slice("sha256=".length);

    const computed = crypto
      .createHmac("sha256", secret)
      .update(body)
      .digest("hex");

    if (!crypto.timingSafeEqual(Buffer.from(computed, "hex"), Buffer.from(expectedHex, "hex"))) {
      throw new Error(
        "Webhook signature verification failed — the payload may have been tampered with"
      );
    }

    return JSON.parse(body.toString("utf8")) as Record<string, unknown>;
  }

  // -------------------------------------------------------------------------
  // Webhook management
  // -------------------------------------------------------------------------

  /**
   * Register a new webhook endpoint for this tenant.
   *
   * FiGuard will POST signed event payloads to `url` when any of the `events` occur.
   * Verify deliveries with `FiGuardClient.verifyWebhook()`.
   *
   * @param url     HTTPS URL where FiGuard will POST events.
   * @param secret  Your chosen signing secret (min 16 chars). Store securely —
   *                it is **never** returned by the API again.
   * @param events  Event type strings to subscribe to, e.g.
   *                `["BUDGET_EXHAUSTED", "SPEND_CONFIRMED", "ANOMALY_DETECTED"]`.
   */
  async createWebhook(url: string, secret: string, events: string[]): Promise<WebhookConfig> {
    const data = await this.request("POST", "/api/v1/webhooks", {
      body: { url, secret, events },
      retryable: false,
    });
    return makeWebhookConfig(data);
  }

  /** Return all webhook configurations for this tenant. */
  async listWebhooks(): Promise<WebhookConfig[]> {
    const data = await this.request("GET", "/api/v1/webhooks", { retryable: true });
    return (Array.isArray(data) ? data : []).map(makeWebhookConfig);
  }

  /**
   * Delete a webhook config and all its delivery history.
   * @param webhookId ID of the webhook config to delete.
   */
  async deleteWebhook(webhookId: string): Promise<void> {
    await this.request("DELETE", `/api/v1/webhooks/${webhookId}`, { retryable: false });
  }

  /**
   * Delivery history for a specific webhook config, newest first.
   * @param webhookId ID of the webhook config.
   */
  async getWebhookDeliveries(webhookId: string): Promise<WebhookDelivery[]> {
    const data = await this.request("GET", `/api/v1/webhooks/${webhookId}/deliveries`, { retryable: true });
    return (Array.isArray(data) ? data : []).map(makeWebhookDelivery);
  }

  /**
   * Fire a test event to the configured URL synchronously.
   * Returns whether the endpoint responded with 2xx. Does not create a delivery record.
   * @param webhookId ID of the webhook config to test.
   */
  async testWebhook(webhookId: string): Promise<WebhookTestResult> {
    const data = await this.request("POST", `/api/v1/webhooks/${webhookId}/test`, { retryable: false });
    return makeWebhookTestResult(data);
  }

  /**
   * All deliveries for this tenant across all webhook configs, newest first.
   * All filter params are optional and combinable.
   */
  async getAllDeliveries(options?: {
    status?: string;
    eventType?: string;
    since?: string;
  }): Promise<WebhookDelivery[]> {
    const params = new URLSearchParams();
    if (options?.status) params.set("status", options.status);
    if (options?.eventType) params.set("eventType", options.eventType);
    if (options?.since) params.set("since", options.since);
    const qs = params.toString();
    const data = await this.request(
      "GET",
      `/api/v1/webhooks/deliveries${qs ? `?${qs}` : ""}`,
      { retryable: true },
    );
    return (Array.isArray(data) ? data : []).map(makeWebhookDelivery);
  }

  /**
   * Re-attempt a FAILED delivery. Fires asynchronously.
   * @param deliveryId ID of the failed delivery to retry.
   */
  async retryDelivery(deliveryId: string): Promise<void> {
    await this.request("POST", `/api/v1/webhooks/deliveries/${deliveryId}/retry`, { retryable: false });
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

  /**
   * Async generator that iterates over every spend event for a budget,
   * automatically fetching pages. Yields events newest-first.
   *
   * @example
   * for await (const event of client.iterEvents({ budgetId: budget.id })) {
   *   console.log(event.decision, event.requestedQuantity);
   * }
   *
   * // Collect all confirmed events:
   * const confirmed: SpendEventResponse[] = [];
   * for await (const e of client.iterEvents({ budgetId: id, decision: "CONFIRMED" })) {
   *   confirmed.push(e);
   * }
   */
  async *iterEvents(options: {
    budgetId: string;
    decision?: string;
    traceId?: string;
    pageSize?: number;
  }): AsyncGenerator<SpendEventResponse> {
    let page = 0;
    const size = options.pageSize ?? 100;
    while (true) {
      const ledger = await this.getLedger({
        budgetId: options.budgetId,
        page,
        size,
        decision: options.decision,
        traceId: options.traceId,
      });
      for (const event of ledger.events) {
        yield event;
      }
      if (!ledger.hasNext) break;
      page++;
    }
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

// ---------------------------------------------------------------------------
// fail_open synthetic results (module-level helpers)
// ---------------------------------------------------------------------------

function makeFallbackAuthorizationResult(
  idempotencyKey: string,
  requestedQuantity: number,
): AuthorizationResult {
  const eventId = `fallback_${idempotencyKey}`;
  const result: AuthorizationResult = {
    eventId,
    decision: "AUTHORIZED",
    approvedQuantity: requestedQuantity,
    isFallback: true,
    isAuthorized: true,
    raiseIfDenied() { return result; },
  };
  return result;
}

function makeFallbackSpendEvent(
  eventId: string,
  decision: string,
  requestedQuantity: number,
): SpendEventResponse {
  return {
    id: eventId,
    decision,
    requestedQuantity,
    createdAt: "",
  };
}
