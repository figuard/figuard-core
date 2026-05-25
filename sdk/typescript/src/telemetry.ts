/**
 * Optional OpenTelemetry instrumentation for the FiGuard TypeScript SDK.
 *
 * Emits spans for authorize(), confirmEvent(), failEvent(), and voidTree() so
 * FiGuard authorization decisions appear as child spans in the caller's trace —
 * Jaeger, Zipkin, Datadog, Honeycomb, Langfuse, or any OTEL-compatible backend.
 *
 * No-op when @opentelemetry/api is not installed. The core SDK has no hard
 * dependency on it; add it as an optional peer:
 *
 *   npm install @opentelemetry/api        # required for spans
 *   npm install @opentelemetry/sdk-node   # required for actual export
 *
 * Span names follow the figuard.* namespace:
 *
 *   figuard.authorize    — decision, event_id, category, denial_reason
 *   figuard.confirm      — event_id, confirmed_quantity
 *   figuard.fail         — event_id, reason
 *   figuard.void_tree    — root_event_id, voided_count, total_quantity_released
 *
 * The active OTEL trace ID (when present) is forwarded to the FiGuard server as
 * traceId so ledger entries can be correlated to the originating distributed trace.
 * Use GET /api/v1/budgets/{id}/ledger?traceId=xxx to filter by trace.
 */

import type { AuthorizationResult, VoidTreeResult } from "./models";

// ---------------------------------------------------------------------------
// Lazy-load @opentelemetry/api — no-op if not installed
// ---------------------------------------------------------------------------

// eslint-disable-next-line @typescript-eslint/no-explicit-any
let _otel: any;
try {
  // Dynamic require so the SDK doesn't crash when @opentelemetry/api is absent.
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  _otel = require("@opentelemetry/api");
} catch {
  // @opentelemetry/api not installed — all span operations are silent no-ops.
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const _tracer: any = _otel ? _otel.trace.getTracer("figuard") : null;

// ---------------------------------------------------------------------------
// No-op span — used when OTEL is not installed
// ---------------------------------------------------------------------------

interface AnySpan {
  setAttribute(key: string, value: string | number | boolean): void;
  setStatus(status: { code: number; message?: string }): void;
  recordException(exc: Error | unknown): void;
  end(): void;
}

const _noOpSpan: AnySpan = {
  setAttribute: () => {},
  setStatus: () => {},
  recordException: () => {},
  end: () => {},
};

// ---------------------------------------------------------------------------
// Trace ID extraction
// ---------------------------------------------------------------------------

/**
 * Return the active OTEL trace ID as a 32-char lowercase hex string, or undefined.
 *
 * Injected into authorize() requests as `traceId` so FiGuard ledger entries can
 * be correlated to the originating distributed trace. Only returns a value when
 * @opentelemetry/api is installed and a sampled span is active.
 */
export function getCurrentTraceId(): string | undefined {
  if (!_otel) return undefined;
  try {
    const span = _otel.trace.getActiveSpan();
    if (!span) return undefined;
    const ctx = span.spanContext();
    if (_otel.isSpanContextValid(ctx)) {
      return ctx.traceId;
    }
  } catch {
    // Defensive — OTEL API mismatch or not configured
  }
  return undefined;
}

// ---------------------------------------------------------------------------
// authorize() span
// ---------------------------------------------------------------------------

export interface AuthorizeSpanAttrs {
  agentId: string;
  actionType: string;
  requestedQuantity: number;
  claimedCategory?: string;
  parentEventId?: string;
  dryRun?: boolean;
}

/**
 * Wrap an authorize() call in an OTEL span.
 *
 * The callback receives a span (or no-op span) and must call finishAuthorizeSpan()
 * after the HTTP response is parsed, then return the result. Any thrown error is
 * recorded on the span and re-thrown.
 */
export async function withAuthorizeSpan<T>(
  attrs: AuthorizeSpanAttrs,
  fn: (span: AnySpan) => Promise<T>,
): Promise<T> {
  if (!_tracer) return fn(_noOpSpan);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return _tracer.startActiveSpan("figuard.authorize", async (span: any) => {
    span.setAttribute("figuard.agent_id", attrs.agentId);
    span.setAttribute("figuard.action_type", attrs.actionType);
    span.setAttribute("figuard.requested_quantity", attrs.requestedQuantity);
    if (attrs.claimedCategory !== undefined) {
      span.setAttribute("figuard.claimed_category", attrs.claimedCategory);
    }
    if (attrs.parentEventId !== undefined) {
      span.setAttribute("figuard.parent_event_id", attrs.parentEventId);
    }
    if (attrs.dryRun) {
      span.setAttribute("figuard.dry_run", true);
    }
    try {
      const result = await fn(span);
      span.end();
      return result;
    } catch (e) {
      span.recordException(e instanceof Error ? e : new Error(String(e)));
      if (_otel?.SpanStatusCode) {
        span.setStatus({ code: _otel.SpanStatusCode.ERROR, message: String(e) });
      }
      span.end();
      throw e;
    }
  });
}

/**
 * Set post-response attributes on an authorize span.
 *
 * AUTHORIZED → status OK, records event_id, approved_quantity, budget_available.
 * DENIED     → status ERROR with denial_reason as description.
 */
export function finishAuthorizeSpan(span: AnySpan, result: AuthorizationResult): void {
  if (span === _noOpSpan) return;
  span.setAttribute("figuard.event_id", result.eventId);
  span.setAttribute("figuard.decision", result.decision);
  if (result.isAuthorized) {
    if (result.approvedQuantity !== undefined && result.approvedQuantity !== null) {
      span.setAttribute("figuard.approved_quantity", result.approvedQuantity);
    }
    if (result.budgetSnapshot) {
      span.setAttribute(
        "figuard.budget_available",
        result.budgetSnapshot.availableQuantity,
      );
    }
    if (_otel?.SpanStatusCode) {
      span.setStatus({ code: _otel.SpanStatusCode.OK });
    }
  } else {
    if (result.denialReason) {
      span.setAttribute("figuard.denial_reason", result.denialReason);
    }
    if (_otel?.SpanStatusCode) {
      span.setStatus({
        code: _otel.SpanStatusCode.ERROR,
        message: result.denialReason ?? "DENIED",
      });
    }
  }
}

// ---------------------------------------------------------------------------
// confirmEvent() / failEvent() spans
// ---------------------------------------------------------------------------

/**
 * Wrap a lifecycle call (confirmEvent or failEvent) in an OTEL span.
 *
 * spanName: "figuard.confirm" | "figuard.fail"
 */
export async function withLifecycleSpan<T>(
  spanName: string,
  eventId: string,
  extraAttrs: Record<string, string | number | boolean>,
  fn: () => Promise<T>,
): Promise<T> {
  if (!_tracer) return fn();

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return _tracer.startActiveSpan(spanName, async (span: any) => {
    span.setAttribute("figuard.event_id", eventId);
    for (const [k, v] of Object.entries(extraAttrs)) {
      span.setAttribute(k, v);
    }
    try {
      const result = await fn();
      span.end();
      return result;
    } catch (e) {
      span.recordException(e instanceof Error ? e : new Error(String(e)));
      if (_otel?.SpanStatusCode) {
        span.setStatus({ code: _otel.SpanStatusCode.ERROR, message: String(e) });
      }
      span.end();
      throw e;
    }
  });
}

// ---------------------------------------------------------------------------
// voidTree() span
// ---------------------------------------------------------------------------

/**
 * Wrap a voidTree() call in an OTEL span.
 */
export async function withVoidTreeSpan<T>(
  rootEventId: string,
  reason: string,
  fn: (span: AnySpan) => Promise<T>,
): Promise<T> {
  if (!_tracer) return fn(_noOpSpan);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return _tracer.startActiveSpan("figuard.void_tree", async (span: any) => {
    span.setAttribute("figuard.root_event_id", rootEventId);
    span.setAttribute("figuard.reason", reason);
    try {
      const result = await fn(span);
      span.end();
      return result;
    } catch (e) {
      span.recordException(e instanceof Error ? e : new Error(String(e)));
      if (_otel?.SpanStatusCode) {
        span.setStatus({ code: _otel.SpanStatusCode.ERROR, message: String(e) });
      }
      span.end();
      throw e;
    }
  });
}

/**
 * Set post-response attributes on a void_tree span.
 */
export function finishVoidTreeSpan(span: AnySpan, result: VoidTreeResult): void {
  if (span === _noOpSpan) return;
  span.setAttribute("figuard.voided_count", result.voidedCount);
  span.setAttribute("figuard.total_quantity_released", result.totalQuantityReleased);
  if (_otel?.SpanStatusCode) {
    span.setStatus({ code: _otel.SpanStatusCode.OK });
  }
}
