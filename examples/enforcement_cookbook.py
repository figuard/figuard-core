"""
FiGuard Enforcement Cookbook — Refund Fleet
============================================

This script demonstrates every budget enforcement capability in FiGuard using
a refund agent fleet as the example. Each scenario is self-contained and prints
the enforcement outcome so you can see exactly what fires and why.

The fleet:
    RefundOrchestratorAgent  — holds the master weekly pool
    RefundProcessorAgent     — executes payouts to the payment gateway
    RefundValidatorAgent     — calls an LLM to validate refunds (token budget)
    RefundNotifierAgent      — sends email/SMS confirmations (API call budget)

Run against a local server:
    make run                                  # start figuard-core container
    python examples/enforcement_cookbook.py   # run all scenarios

Override the server URL:
    FIGUARD_URL=https://your-server.com python examples/enforcement_cookbook.py

Scenarios
---------
  1. Flat monetary budget + intent scope enforcement
  2. Category allocations (CATEGORY_CONSTRAINED)
  3. STRICT mode + forbidden item types
  4. Per-transaction ceiling (maxTransactionQuantity)
  5. Entity dedup — one refund per order
  6. TraceId — linking events across a run + ledger filtering
  7. Non-monetary resource budget (token quota)
  8. CompositeGuard — atomic authorization across multiple budgets
  9. Authorization auto-expiry — recycling stale reservations
"""

from __future__ import annotations

import os
import sys
import time
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import requests

from figuard import FiGuardClient, CompositeGuard, GuardedResource

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

FIGUARD_URL = os.environ.get("FIGUARD_URL", "http://localhost:8080")
API_KEY = os.environ.get("FIGUARD_API_KEY", "ab_live_demo")


def _expires_at(hours: int = 23) -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=hours)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


def _check_server() -> bool:
    try:
        resp = requests.get(f"{FIGUARD_URL}/actuator/health", timeout=3)
        return resp.status_code == 200
    except Exception:
        return False


def _header(title: str) -> None:
    print(f"\n{'=' * 60}")
    print(f"  {title}")
    print(f"{'=' * 60}")


def _ok(msg: str) -> None:
    print(f"  ✓  {msg}")


def _denied(msg: str) -> None:
    print(f"  ✗  {msg}")


# ---------------------------------------------------------------------------
# Scenario 1 — Flat monetary budget + intent scope enforcement
#
# Budget has intentTags. Any authorize() request whose intentContext does
# not match a tag is denied as INTENT_SCOPE_VIOLATION.
#
# Use this to prevent a session token from being reused for unrelated spend.
#
# Note: intentTags enforcement only applies to flat (no-allocation) budgets.
# On allocated budgets, claimedCategory is the gating mechanism instead.
# ---------------------------------------------------------------------------

def scenario_1_flat_budget_with_intent_scope(client: FiGuardClient) -> None:
    _header("Scenario 1 — Flat budget + intent scope")

    budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=10_000.00,
        expires_at=_expires_at(),
        currency="USD",
        # Only requests with intentContext matching "daily-refund-run" are allowed
        intent_tags=["daily-refund-run"],
        external_reference="batch-2026-05-08",
    )
    print(f"\n  Budget {budget.id[:8]}…  total=${budget.total_limit:.2f}  "
          f"tags={budget.intent_tags}")

    # — Authorized: correct intent context
    client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Standard refund ORD-001",
        requested_quantity=149.99,
        currency="USD",
        intent_context="daily-refund-run",
        idempotency_key=str(uuid4()),
    )
    _ok("AUTHORIZED  qty=149.99  intent_context='daily-refund-run'")

    # — Denied: missing intent context
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Attempt without context",
        requested_quantity=50.00,
        currency="USD",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — no intentContext on request")

    # — Denied: wrong intent context
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="some-other-agent",
        action_type="PURCHASE",
        description="Unrelated purchase attempt",
        requested_quantity=200.00,
        currency="USD",
        intent_context="hotel-booking-run",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — 'hotel-booking-run' not in tags")

    # — Denied: cap exceeded
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Way over the limit",
        requested_quantity=99_000.00,
        currency="USD",
        intent_context="daily-refund-run",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — $99,000 on a $10,000 budget")

    # — Denied: currency mismatch
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="EUR refund on USD budget",
        requested_quantity=50.00,
        currency="EUR",
        intent_context="daily-refund-run",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — EUR request on USD budget")


# ---------------------------------------------------------------------------
# Scenario 2 — Category allocations (CATEGORY_CONSTRAINED)
#
# The orchestrator's weekly pool is split into sub-buckets by purpose.
# Agents must declare claimedCategory; the server routes to the matching
# allocation and enforces its sub-limit independently of all others.
#
# Denial codes:
#   MISSING_CLAIMED_CATEGORY   — budget has allocations but request omits it
#   NO_MATCHING_ALLOCATION     — claimedCategory not in any allowedCategories
#   ALLOCATION_EXHAUSTED       — matched allocation is at its limit
# ---------------------------------------------------------------------------

def scenario_2_category_allocations(client: FiGuardClient) -> None:
    _header("Scenario 2 — Category allocations (CATEGORY_CONSTRAINED)")

    budget = client.create_budget(
        user_id="refund-orchestrator",
        total_limit=500.00,
        expires_at=_expires_at(),
        currency="USD",
        allocations=[
            {
                "category": "PAYOUT",
                "allowedCategories": ["STANDARD_REFUND", "EXPRESS_REFUND"],
                "limit": 400.00,
                "enforcementMode": "CATEGORY_CONSTRAINED",
            },
            {
                "category": "DISPUTE_FEES",
                "allowedCategories": ["CHARGEBACK_FEE", "ARBITRATION_FEE"],
                "limit": 100.00,
                "enforcementMode": "CATEGORY_CONSTRAINED",
            },
        ],
    )
    print(f"\n  Budget {budget.id[:8]}…  total=$500  PAYOUT=$400  DISPUTE_FEES=$100")

    # — Authorized: standard refund routes to PAYOUT allocation
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Standard refund ORD-100",
        requested_quantity=100.00,
        claimed_category="STANDARD_REFUND",
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=100  category=STANDARD_REFUND  "
        f"→ PAYOUT available now ${r.allocation_snapshot.available_quantity:.2f}")

    # — Authorized: chargeback fee routes to DISPUTE_FEES allocation
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="FEE_DEDUCTION",
        description="Chargeback fee DISP-001",
        requested_quantity=25.00,
        claimed_category="CHARGEBACK_FEE",
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=25  category=CHARGEBACK_FEE  "
        f"→ DISPUTE_FEES available now ${r.allocation_snapshot.available_quantity:.2f}")

    # — Denied: no category provided
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Forgot to set category",
        requested_quantity=50.00,
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — budget has allocations but no claimedCategory sent")

    # — Denied: category not in any allocation
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Crypto refund attempt",
        requested_quantity=50.00,
        claimed_category="CRYPTO_REFUND",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — CRYPTO_REFUND not in any allocation's allowedCategories")

    # — Exhaust PAYOUT, then show DISPUTE_FEES is unaffected
    client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Large refund to exhaust PAYOUT",
        requested_quantity=300.00,
        claimed_category="EXPRESS_REFUND",
        idempotency_key=str(uuid4()),
    )
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="PAYOUT exhausted",
        requested_quantity=1.00,
        claimed_category="STANDARD_REFUND",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — PAYOUT allocation at $400 limit")

    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="FEE_DEDUCTION",
        description="DISPUTE_FEES still open",
        requested_quantity=20.00,
        claimed_category="ARBITRATION_FEE",
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=20  category=ARBITRATION_FEE  "
        f"— DISPUTE_FEES unaffected by PAYOUT exhaustion")


# ---------------------------------------------------------------------------
# Scenario 3 — STRICT mode + forbidden item types
#
# STRICT builds on CATEGORY_CONSTRAINED: after the category is matched,
# claimedItemType is checked against forbiddenItemTypes.
# If the item type is in the forbidden list → FORBIDDEN_ITEM_TYPE.
# ---------------------------------------------------------------------------

def scenario_3_strict_mode_forbidden_items(client: FiGuardClient) -> None:
    _header("Scenario 3 — STRICT mode + forbidden item types")

    budget = client.create_budget(
        user_id="refund-orchestrator",
        total_limit=10_000.00,
        expires_at=_expires_at(),
        currency="USD",
        allocations=[
            {
                "category": "PAYOUT",
                "allowedCategories": ["STANDARD_REFUND", "EXPRESS_REFUND"],
                "limit": 10_000.00,
                "enforcementMode": "STRICT",
                # Compliance rule: no crypto or international wire refunds
                "forbiddenItemTypes": ["CRYPTO_PAYOUT", "WIRE_TRANSFER_INTL"],
            }
        ],
    )
    print(f"\n  Budget {budget.id[:8]}…  PAYOUT/STRICT  "
          f"forbidden=[CRYPTO_PAYOUT, WIRE_TRANSFER_INTL]")

    # — Denied: category matches but item type is forbidden
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Crypto refund ORD-999",
        requested_quantity=500.00,
        claimed_category="EXPRESS_REFUND",
        claimed_item_type="CRYPTO_PAYOUT",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — EXPRESS_REFUND matched, CRYPTO_PAYOUT is forbidden")

    # — Denied: international wire also blocked
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="International wire ORD-998",
        requested_quantity=300.00,
        claimed_category="EXPRESS_REFUND",
        claimed_item_type="WIRE_TRANSFER_INTL",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — WIRE_TRANSFER_INTL is forbidden")

    # — Authorized: domestic bank transfer is allowed
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Domestic bank transfer ORD-997",
        requested_quantity=149.99,
        claimed_category="EXPRESS_REFUND",
        claimed_item_type="BANK_TRANSFER_DOM",
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=149.99  item_type=BANK_TRANSFER_DOM  — not in forbidden list")

    # — Authorized: no item type declared also passes (STRICT only blocks listed types)
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Standard refund, no item type",
        requested_quantity=75.00,
        claimed_category="STANDARD_REFUND",
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=75  no item_type  — STRICT only blocks declared forbidden types")


# ---------------------------------------------------------------------------
# Scenario 4 — Per-transaction ceiling (maxTransactionQuantity)
#
# Any single authorize() over the ceiling is denied regardless of available
# balance. Checked before allocation routing.
# ---------------------------------------------------------------------------

def scenario_4_per_transaction_cap(client: FiGuardClient) -> None:
    _header("Scenario 4 — Per-transaction ceiling")

    budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=10_000.00,
        expires_at=_expires_at(),
        currency="USD",
        # Single refund cannot exceed $2,000 without manual approval
        max_transaction_quantity=2_000.00,
    )
    print(f"\n  Budget {budget.id[:8]}…  total=$10,000  ceiling=$2,000/txn")

    # — Denied: over ceiling even though balance is fine
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="High-value refund needing manual review",
        requested_quantity=3_500.00,
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — $3,500 exceeds $2,000 ceiling")

    # — Authorized: exactly at the ceiling
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Refund at ceiling",
        requested_quantity=2_000.00,
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=2,000  — exactly at ceiling")

    # — Authorized: well under ceiling
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Standard refund ORD-200",
        requested_quantity=149.99,
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=149.99  — normal refund under ceiling")


# ---------------------------------------------------------------------------
# Scenario 5 — Entity dedup (one refund per order)
#
# When entityDedupEnabled=True, a second authorize() with the same entityId
# is denied as ENTITY_ALREADY_AUTHORIZED regardless of idempotency key.
#
# Distinction:
#   same idempotencyKey  → safe network retry — replays original decision
#   same entityId + new key  → business rule violation — double-refund blocked
# ---------------------------------------------------------------------------

def scenario_5_entity_dedup(client: FiGuardClient) -> None:
    _header("Scenario 5 — Entity dedup (one refund per order)")

    budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=5_000.00,
        expires_at=_expires_at(),
        currency="USD",
        entity_dedup_enabled=True,
    )
    print(f"\n  Budget {budget.id[:8]}…  entityDedupEnabled=True")

    order_id = f"ORD-{uuid4().hex[:8].upper()}"

    # — First authorize: succeeds
    r1 = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description=f"Refund {order_id}",
        requested_quantity=149.99,
        entity_id=order_id,
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  entity_id={order_id}  event_id={r1.event_id[:8]}…")

    # — Second attempt with different idempotency key: business rule violation
    r2 = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description=f"Duplicate refund attempt {order_id}",
        requested_quantity=149.99,
        entity_id=order_id,
        idempotency_key=str(uuid4()),   # different key — not a retry
    )
    _denied(f"{r2.denial_reason}  — same order, different key  "
            f"original_event_id={r2.original_event_id[:8]}…")

    # — Same key as first call: safe network retry — returns same event_id
    key = str(uuid4())
    order_b = f"ORD-{uuid4().hex[:8].upper()}"
    r3 = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description=f"Refund {order_b}",
        requested_quantity=75.00,
        entity_id=order_b,
        idempotency_key=key,
    )
    r4 = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description=f"Retry refund {order_b}",
        requested_quantity=75.00,
        entity_id=order_b,
        idempotency_key=key,    # same key — safe retry
    )
    _ok(f"Idempotency replay: same event_id={r3.event_id == r4.event_id}  "
        f"— safe retry returns same event")


# ---------------------------------------------------------------------------
# Scenario 6 — TraceId: linking events across a run + ledger filtering
#
# Pass the same trace_id on every authorize() in a single agent run.
# All events are tagged and can be filtered in one ledger query.
# trace_id is indexed and queryable — unlike metadata which is opaque.
# ---------------------------------------------------------------------------

def scenario_6_trace_id_and_ledger_filter(client: FiGuardClient) -> None:
    _header("Scenario 6 — TraceId + ledger filtering")

    budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=5_000.00,
        expires_at=_expires_at(),
        currency="USD",
    )

    trace_run_1 = f"run-{uuid4().hex[:12]}"
    trace_run_2 = f"run-{uuid4().hex[:12]}"
    print(f"\n  Budget {budget.id[:8]}…")
    print(f"  Run 1 trace: {trace_run_1}")
    print(f"  Run 2 trace: {trace_run_2}")

    # Three events under run 1
    for i in range(3):
        r = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"Run 1 — refund {i}",
            requested_quantity=10.00,
            trace_id=trace_run_1,
            idempotency_key=str(uuid4()),
        )
        client.confirm_event(r.event_id, confirmed_quantity=10.00)

    # Two events under run 2
    for i in range(2):
        r = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"Run 2 — refund {i}",
            requested_quantity=20.00,
            trace_id=trace_run_2,
            idempotency_key=str(uuid4()),
        )

    page_1 = client.get_ledger(budget.id, trace_id=trace_run_1, size=50)
    page_2 = client.get_ledger(budget.id, trace_id=trace_run_2, size=50)

    _ok(f"get_ledger(traceId=run_1) → {page_1.total_elements} events  "
        f"all match={all(e.trace_id == trace_run_1 for e in page_1.events)}")
    _ok(f"get_ledger(traceId=run_2) → {page_2.total_elements} events  "
        f"all match={all(e.trace_id == trace_run_2 for e in page_2.events)}")

    ids_1 = {e.id for e in page_1.events}
    ids_2 = {e.id for e in page_2.events}
    _ok(f"Runs are disjoint={ids_1.isdisjoint(ids_2)}  — traces never bleed into each other")


# ---------------------------------------------------------------------------
# Scenario 7 — Non-monetary resource budget (token / API call quota)
#
# Pass unit= instead of currency=. The server enforces dimensionless quantity
# arithmetic — no currency field required or expected on authorize().
# Use for: LLM tokens, API calls, compute minutes, storage GB, etc.
# ---------------------------------------------------------------------------

def scenario_7_resource_budget(client: FiGuardClient) -> None:
    _header("Scenario 7 — Non-monetary resource budget")

    # Validator agent: 2M token budget for LLM validation calls
    token_budget = client.create_budget(
        user_id="refund-validator-agent",
        total_limit=2_000_000,
        expires_at=_expires_at(),
        unit="tokens",              # not currency= — dimensionless quantity
        max_transaction_quantity=50_000,
        anomaly_detection_enabled=True,
    )

    # Notifier agent: 500 API call budget
    api_budget = client.create_budget(
        user_id="refund-notifier-agent",
        total_limit=500,
        expires_at=_expires_at(),
        unit="api_calls",
        max_transaction_quantity=10,
        authorization_expiry_seconds=120,
    )

    print(f"\n  Token budget  {token_budget.id[:8]}…  unit=tokens  "
          f"total=2,000,000  ceiling=50,000/call")
    print(f"  API budget    {api_budget.id[:8]}…  unit=api_calls  "
          f"total=500  ceiling=10/call  expiry=120s")

    # — Token budget: LLM validation call (no currency field)
    r = client.authorize(
        session_token=token_budget.session_token,
        agent_id="refund-validator-agent",
        action_type="LLM_VALIDATION",
        description="Validate legitimacy of refund ORD-300",
        requested_quantity=12_000,      # tokens, not dollars
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=12,000 tokens  (no currency field)")
    client.confirm_event(r.event_id, confirmed_quantity=11_234)
    _ok(f"CONFIRMED   actual=11,234 tokens")

    # — Token budget: over per-call ceiling
    r = client.authorize(
        session_token=token_budget.session_token,
        agent_id="refund-validator-agent",
        action_type="LLM_VALIDATION",
        description="Oversized LLM call",
        requested_quantity=80_000,
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — 80,000 tokens exceeds 50,000 ceiling")

    # — API call budget: send 2 notifications
    r = client.authorize(
        session_token=api_budget.session_token,
        agent_id="refund-notifier-agent",
        action_type="SEND_NOTIFICATION",
        description="Email + SMS for ORD-300",
        requested_quantity=2,           # 2 API calls
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=2 api_calls")


# ---------------------------------------------------------------------------
# Scenario 8 — CompositeGuard: atomic authorization across multiple budgets
#
# When an agent needs resources from more than one budget per operation,
# CompositeGuard handles all authorizations atomically:
#   - Resources are authorized in list order.
#   - If resource N denies, all resources 0..N-1 are voided automatically.
#   - Idempotency key is namespaced per resource: "{key}:{resource_label}"
#
# Use this when budgets must all agree before the operation can proceed.
# ---------------------------------------------------------------------------

def scenario_8_composite_guard(client: FiGuardClient) -> None:
    _header("Scenario 8 — CompositeGuard (atomic multi-resource)")

    dollar_budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=1_000.00,
        expires_at=_expires_at(),
        currency="USD",
    )
    token_budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=100_000,
        expires_at=_expires_at(),
        unit="tokens",
    )
    tiny_dollar_budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=5.00,           # will fail the partial denial test
        expires_at=_expires_at(),
        currency="USD",
    )

    print(f"\n  Dollar budget  {dollar_budget.id[:8]}…  $1,000")
    print(f"  Token budget   {token_budget.id[:8]}…  100,000 tokens")
    print(f"  Tiny budget    {tiny_dollar_budget.id[:8]}…  $5  (for denial test)")

    # ---- Success path: both budgets authorize and confirm
    guard = CompositeGuard([
        GuardedResource(client=client, session_token=dollar_budget.session_token, resource="USD"),
        GuardedResource(client=client, session_token=token_budget.session_token,  resource="tokens"),
    ])

    result = guard.authorize(
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Payout + memo for ORD-500",
        requested={"USD": 149.99, "tokens": 8_000},
        idempotency_key=str(uuid4()),
        trace_id=f"run-{uuid4().hex[:8]}",
    )
    _ok(f"all_authorized={result.all_authorized}  "
        f"events={[r.event_id[:8] + '…' for r in result.authorizations]}")

    events = guard.confirm(result, confirmed={"USD": 149.99, "tokens": 7_412})
    _ok(f"confirmed  USD=149.99  tokens=7,412  ({len(events)} events)")

    # ---- Partial denial: tokens authorize first, tiny dollar budget denies second.
    # The token reservation must be voided automatically.
    token_available_before = client.get_budget(token_budget.id).available_quantity

    guard_partial = CompositeGuard([
        GuardedResource(client=client, session_token=token_budget.session_token,       resource="tokens"),
        GuardedResource(client=client, session_token=tiny_dollar_budget.session_token, resource="USD"),
    ])

    result2 = guard_partial.authorize(
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Will fail on tiny dollar budget",
        requested={"tokens": 5_000, "USD": 500.00},
        idempotency_key=str(uuid4()),
    )
    _denied(f"all_authorized={result2.all_authorized}  "
            f"denied_on={result2.first_denial_resource}  "
            f"reason={result2.first_denial.denial_reason}")

    token_available_after = client.get_budget(token_budget.id).available_quantity
    _ok(f"Token reservation voided automatically: "
        f"before={token_available_before:.0f}  after={token_available_after:.0f}  "
        f"restored={token_available_after == token_available_before}")


# ---------------------------------------------------------------------------
# Scenario 9 — Authorization auto-expiry (recycling stale reservations)
#
# authorizationExpirySeconds tells FiGuard to exclude AUTHORIZED events
# older than N seconds from the reserved-capacity calculation. No background
# job — evaluated lazily on the next authorize() call.
#
# The original event stays AUTHORIZED in the ledger (immutable audit trail).
# Only its contribution to reserved capacity is removed after the window.
#
# Set this to your agent's expected max run time.
# ---------------------------------------------------------------------------

def scenario_9_authorization_auto_expiry(client: FiGuardClient) -> None:
    _header("Scenario 9 — Authorization auto-expiry")

    budget = client.create_budget(
        user_id="refund-processor-agent",
        total_limit=100.00,
        expires_at=_expires_at(),
        currency="USD",
        authorization_expiry_seconds=2,     # short window for demo
    )
    print(f"\n  Budget {budget.id[:8]}…  total=$100  expiry=2s")

    # Reserve $90 and "crash" — never confirm or fail
    r_crash = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="Reservation from crashed agent",
        requested_quantity=90.00,
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=90  (simulating crashed agent — will never confirm)")
    print(f"  Available now: $10")

    # Immediately: $50 request fails (only $10 headroom)
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="New run — should fail immediately",
        requested_quantity=50.00,
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r.denial_reason}  — $90 still reserved by crashed agent")

    print(f"\n  Waiting 3 seconds for expiry window to pass...")
    time.sleep(3)

    # After expiry: $90 reservation excluded from calculation — $50 succeeds
    r = client.authorize(
        session_token=budget.session_token,
        agent_id="refund-processor-agent",
        action_type="REFUND_PAYOUT",
        description="New run after expiry — should succeed",
        requested_quantity=50.00,
        idempotency_key=str(uuid4()),
    )
    if r.is_authorized:
        _ok(f"AUTHORIZED  qty=50  — stale $90 reservation recycled after expiry")
    else:
        _denied(f"{r.denial_reason}  (unexpected)")

    # Verify the crashed event is still AUTHORIZED in the ledger — not mutated
    page = client.get_ledger(budget.id, decision="AUTHORIZED", size=50)
    still_there = any(e.id == r_crash.event_id for e in page.events)
    _ok(f"Original AUTHORIZED event still in ledger (not mutated): {still_there}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def _print_model_overview() -> None:
    print("""
Authorization model
-------------------
  create_budget()  →  allocates a spending envelope; returns a session_token
                      (hand the session_token to your agent — keep the API key
                      in your backend, it never leaves)

  authorize()      →  agent asks permission before consuming a resource
                      reserves quantity; returns AUTHORIZED or DENIED
                      nothing has moved yet — this is the pre-flight check

  confirm_event()  →  action succeeded; reservation → confirmed spend
  fail_event()     →  action failed (e.g. payment declined); reservation released
  void_event()     →  action cancelled before execution; reservation released

Every call writes a SpendEvent to the append-only ledger regardless of outcome.
Denied decisions are recorded just like authorized ones — full audit trail always.

Enforcement runs at authorize() time. The scenarios below show every knob
you can configure on a budget and the denial code that fires when it trips.
""")


def main() -> None:
    if not _check_server():
        print(f"\nFiGuard server not reachable at {FIGUARD_URL}")
        print("Start it with:  make run")
        print("Then re-run:    python examples/enforcement_cookbook.py\n")
        sys.exit(1)

    client = FiGuardClient(api_key=API_KEY, base_url=FIGUARD_URL)

    print("\nFiGuard Enforcement Cookbook — Refund Fleet")
    print(f"Server: {FIGUARD_URL}")
    _print_model_overview()

    scenario_1_flat_budget_with_intent_scope(client)
    scenario_2_category_allocations(client)
    scenario_3_strict_mode_forbidden_items(client)
    scenario_4_per_transaction_cap(client)
    scenario_5_entity_dedup(client)
    scenario_6_trace_id_and_ledger_filter(client)
    scenario_7_resource_budget(client)
    scenario_8_composite_guard(client)
    scenario_9_authorization_auto_expiry(client)

    print(f"\n{'=' * 60}")
    print("  All scenarios complete.")
    print(f"{'=' * 60}\n")


if __name__ == "__main__":
    main()
