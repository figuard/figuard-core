"""
Optional OpenTelemetry instrumentation for the FiGuard SDK.

Emits spans for authorize(), confirm_event(), fail_event(), and void_tree() so
FiGuard authorization decisions appear as child spans in the caller's trace —
Jaeger, Zipkin, Datadog, Honeycomb, Langfuse, or any OTEL-compatible backend.

No-op when opentelemetry-api is not installed. The core SDK has no hard dependency
on this package; install it via the optional extra::

    pip install figuard[opentelemetry]   # adds opentelemetry-api

Span names follow the ``figuard.*`` namespace:

    figuard.authorize    — decision, event_id, category, denial_reason
    figuard.confirm      — event_id, confirmed_quantity
    figuard.fail         — event_id, reason
    figuard.void_tree    — root_event_id, voided_count, total_quantity_released

The active OTEL trace ID (when present) is also injected into the FiGuard API
request body as ``traceId`` so ledger entries can be correlated to the originating
distributed trace. Use ``GET /api/v1/budgets/{id}/ledger?traceId=xxx`` to filter.

**Relationship to existing tools (Langfuse, Phoenix, OpenLLMetry)**

Those tools emit spans for LLM calls using W3C traceparent / OTel span context.
FiGuard adds *financial* child spans to the same trace — so a single Jaeger/Langfuse
trace shows: LLM call → tool decision → FiGuard AUTHORIZED/DENIED at the exact cost.
The LangChain callback handler automatically nests FiGuard spans under the right
tool node because figuard.authorize() is called inside on_tool_start(), which is
already a child span in LangChain's OTEL instrumentation.
"""

from __future__ import annotations

import contextlib
from typing import Any, Generator, Optional

try:
    from opentelemetry import trace
    from opentelemetry.trace import StatusCode
    _OTEL_AVAILABLE = True
    _tracer = trace.get_tracer(
        "figuard",
        schema_url="https://opentelemetry.io/schemas/1.24.0",
    )
except ImportError:
    _OTEL_AVAILABLE = False


# ---------------------------------------------------------------------------
# No-op span — returned when OTEL is not installed
# ---------------------------------------------------------------------------

class _NoOpSpan:
    """
    Returned by context managers when opentelemetry-api is not installed.
    All operations are silent no-ops — the SDK works identically without OTEL.
    """
    __slots__ = ()

    def set_attribute(self, key: str, value: Any) -> None:
        pass

    def set_status(self, *args: Any, **kwargs: Any) -> None:
        pass

    def record_exception(self, exc: BaseException) -> None:
        pass


_NOOP = _NoOpSpan()


# ---------------------------------------------------------------------------
# Trace ID injection
# ---------------------------------------------------------------------------

def get_current_trace_id() -> Optional[str]:
    """
    Return the active OTEL trace ID as a 32-char lowercase hex string, or None.

    Used to forward the active trace context to the FiGuard server so the
    authorization event in the ledger can be correlated with the originating
    request in your observability stack. Only returns a value when a sampled
    span is active — non-sampled or missing spans return None.
    """
    if not _OTEL_AVAILABLE:
        return None
    span = trace.get_current_span()
    ctx = span.get_span_context()
    if ctx.trace_flags.sampled:
        return format(ctx.trace_id, "032x")
    return None


# ---------------------------------------------------------------------------
# authorize()
# ---------------------------------------------------------------------------

@contextlib.contextmanager
def authorize_span(
    agent_id: str,
    action_type: str,
    requested_quantity: float,
    claimed_category: Optional[str],
    parent_event_id: Optional[str],
    dry_run: bool,
) -> Generator[Any, None, None]:
    """
    Context manager wrapping an authorize() call.

    Yields a live span (or _NoOpSpan if OTEL is not installed). The caller
    calls finish_authorize_span() after the HTTP response arrives to set
    decision-based attributes (event_id, decision, denial_reason).
    Network errors are caught and marked as ERROR before re-raising.
    """
    if not _OTEL_AVAILABLE:
        yield _NOOP
        return
    with _tracer.start_as_current_span("figuard.authorize") as span:
        span.set_attribute("figuard.agent_id", agent_id)
        span.set_attribute("figuard.action_type", action_type)
        span.set_attribute("figuard.requested_quantity", float(requested_quantity))
        if claimed_category:
            span.set_attribute("figuard.claimed_category", claimed_category)
        if parent_event_id:
            span.set_attribute("figuard.parent_event_id", parent_event_id)
        if dry_run:
            span.set_attribute("figuard.dry_run", True)
        try:
            yield span
        except Exception as exc:
            span.record_exception(exc)
            span.set_status(StatusCode.ERROR, str(exc))
            raise


def finish_authorize_span(span: Any, result: Any) -> None:
    """
    Set post-response attributes on an authorize span.

    Decision AUTHORIZED → status OK, records event_id and approved_quantity.
    Decision DENIED → status ERROR with denial_reason as description.
    """
    if isinstance(span, _NoOpSpan):
        return
    span.set_attribute("figuard.event_id", result.event_id)
    span.set_attribute("figuard.decision", result.decision)
    if result.is_authorized:
        if result.approved_quantity is not None:
            span.set_attribute("figuard.approved_quantity", float(result.approved_quantity))
        if result.budget_snapshot is not None:
            span.set_attribute(
                "figuard.budget_available",
                float(result.budget_snapshot.available_quantity),
            )
        span.set_status(StatusCode.OK)
    else:
        if result.denial_reason:
            span.set_attribute("figuard.denial_reason", result.denial_reason)
        span.set_status(StatusCode.ERROR, result.denial_reason or "DENIED")


# ---------------------------------------------------------------------------
# confirm_event() / fail_event()
# ---------------------------------------------------------------------------

@contextlib.contextmanager
def lifecycle_span(span_name: str, event_id: str) -> Generator[Any, None, None]:
    """
    Context manager for confirm_event() and fail_event().

    span_name: "figuard.confirm" or "figuard.fail"
    """
    if not _OTEL_AVAILABLE:
        yield _NOOP
        return
    with _tracer.start_as_current_span(span_name) as span:
        span.set_attribute("figuard.event_id", event_id)
        try:
            yield span
        except Exception as exc:
            span.record_exception(exc)
            span.set_status(StatusCode.ERROR, str(exc))
            raise


# ---------------------------------------------------------------------------
# void_tree()
# ---------------------------------------------------------------------------

@contextlib.contextmanager
def void_tree_span(root_event_id: str, reason: str) -> Generator[Any, None, None]:
    """Context manager wrapping a void_tree() call."""
    if not _OTEL_AVAILABLE:
        yield _NOOP
        return
    with _tracer.start_as_current_span("figuard.void_tree") as span:
        span.set_attribute("figuard.root_event_id", root_event_id)
        span.set_attribute("figuard.reason", reason)
        try:
            yield span
        except Exception as exc:
            span.record_exception(exc)
            span.set_status(StatusCode.ERROR, str(exc))
            raise


def finish_void_tree_span(span: Any, result: Any) -> None:
    """Set post-response attributes on a void_tree span."""
    if isinstance(span, _NoOpSpan):
        return
    span.set_attribute("figuard.voided_count", result.voided_count)
    span.set_attribute(
        "figuard.total_quantity_released", float(result.total_quantity_released)
    )
    span.set_status(StatusCode.OK)
