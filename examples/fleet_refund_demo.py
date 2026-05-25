#!/usr/bin/env python3
"""
FiGuard fleet refund demo.

Architecture:
  - Fleet budget:   $5,000 USD  — shared ceiling for all refund agents
  - API call budget: 1,000 calls — tracks every payment API call made
  - 5 refund agents, each handling a different order via a delegation token
  - CompositeGuard: every refund atomically reserves both USD + 1 API call

Scenarios (all visible on the dashboard):

  1. Normal fleet run       — 3 agents refund ORD-001, ORD-002, ORD-003
                              Each uses CompositeGuard (USD + api_call)
                              AUTHORIZED → CONFIRMED on both budgets

  2. Delegation cap breach  — Agent for ORD-004 has a $200 cap but tries $850
                              DENIED: DELEGATE_CAP_EXCEEDED on fleet budget
                              CompositeGuard voids the api_call authorization too

  3. Duplicate order        — A second agent tries to refund ORD-001 again
                              DENIED: ENTITY_ALREADY_AUTHORIZED

  4. Per-chain cap          — Orchestrator starts a high-value order chain with
                              maxSubtreeQuantity=$300. Child agent tries $350.
                              DENIED: SUBTREE_CAP_EXCEEDED

  5. Cascading void         — ORD-003 chain fails mid-processing (payment error)
                              void_tree() releases fleet budget + api_call budget

  6. Fleet summary          — Ledger + remaining capacity on both budgets

Usage:
    cd sdk/python && pip install -e ".[langchain]"
    cd ../../examples
    python fleet_refund_demo.py [--base-url http://localhost:8080] [--api-key fg_live_demo]
"""

import argparse
import sys
import uuid
from dataclasses import dataclass
from typing import Optional

sys.path.insert(0, "../sdk/python")

from figuard import FiGuardClient
from figuard.composite import CompositeGuard, GuardedResource


# ── Helpers ───────────────────────────────────────────────────────────────────

def section(title: str) -> None:
    print(f"\n{'═' * 65}")
    print(f"  {title}")
    print(f"{'═' * 65}")

def ok(msg: str)    -> None: print(f"  ✓  {msg}")
def bad(msg: str)   -> None: print(f"  ✗  {msg}")
def info(msg: str)  -> None: print(f"     {msg}")
def sub(msg: str)   -> None: print(f"       {msg}")


# ── Order data ────────────────────────────────────────────────────────────────

@dataclass
class Order:
    id: str
    amount: float
    customer: str

ORDERS = [
    Order("ORD-001", 120.00, "Alice Chen"),
    Order("ORD-002", 340.00, "Bob Martinez"),
    Order("ORD-003", 890.00, "Carol Singh"),
    Order("ORD-004", 850.00, "David Okonkwo"),   # agent capped at $200 — will be denied
    Order("ORD-005", 350.00, "Eva Johansson"),   # chain capped at $300 — will be denied
]


# ── Demo ──────────────────────────────────────────────────────────────────────

def run(base_url: str, api_key: str) -> None:
    client = FiGuardClient(api_key=api_key, base_url=base_url)
    trace_id = f"fleet-{uuid.uuid4().hex[:8]}"

    # ── 1. Create budgets ─────────────────────────────────────────────────────
    section("1 / Setup — Create fleet budget ($5,000) + API call budget (1,000)")

    fleet_budget = client.create_budget(
        user_id="refund_platform",
        total_limit=5000.00,
        currency="USD",
        expires_in="2h",
        entity_dedup_enabled=True,        # block duplicate order refunds
        velocity_max_per_minute=20,       # no more than 20 refunds/min fleet-wide
        allocations=[
            {
                "category": "refunds",
                "limit": 5000.00,
                "allowedCategories": ["refunds"],
            }
        ],
    )

    api_budget = client.create_budget(
        user_id="refund_platform",
        total_limit=1000,
        unit="api_calls",
        expires_in="2h",
        # No allocations — flat resource budget. Category enforcement doesn't
        # apply here; we just want a hard cap on total API calls fleet-wide.
    )

    ok(f"Fleet budget   : {fleet_budget.id}  (USD 5,000)")
    ok(f"API call budget: {api_budget.id}  (1,000 calls)")
    ok(f"Trace ID       : {trace_id}")
    info(f"")
    info(f"Dashboard → {base_url}/ui")

    # ── 2. Issue delegation tokens — one per agent ────────────────────────────
    section("2 / Delegation tokens — one per refund agent")

    # Each agent gets a capped delegation token from the fleet budget.
    # The agent uses it exactly like a session token — FiGuard enforces
    # both the per-agent cap and the $5,000 fleet ceiling simultaneously.
    agent_tokens: dict[str, str] = {}
    caps = {
        "ORD-001": 500.00,
        "ORD-002": 500.00,
        "ORD-003": 1000.00,
        "ORD-004": 200.00,   # intentionally low — will cause DELEGATE_CAP_EXCEEDED
        "ORD-005": 500.00,
    }

    for order in ORDERS:
        token = client.create_delegation_token(
            budget_id=fleet_budget.id,
            label=f"refund-agent-{order.id}",
            caps=[{"category": "refunds", "limit": caps[order.id]}],
        )
        agent_tokens[order.id] = token.session_token
        ok(f"  {order.id}  cap=${caps[order.id]:.0f}  token={token.session_token[:16]}...")

    # ── 3. Scenario 1: Normal fleet run ───────────────────────────────────────
    section("3 / Scenario 1 — Normal fleet run (ORD-001, ORD-002, ORD-003)")
    info("CompositeGuard: each refund atomically reserves USD + 1 api_call")
    info("Expected: all 3 CONFIRMED on both budgets")

    confirmed_event_ids: dict[str, list] = {}  # order_id → [fleet_event_id, api_event_id]

    for order in ORDERS[:3]:
        guard = CompositeGuard([
            GuardedResource(
                client=client,
                session_token=agent_tokens[order.id],
                resource="USD",
            ),
            GuardedResource(
                client=client,
                session_token=api_budget.session_token,
                resource="api_calls",
            ),
        ])

        result = guard.authorize(
            agent_id=f"refund-agent-{order.id}",
            action_type="REFUND",
            description=f"Refund USD {order.amount:.2f} for {order.id} — {order.customer}",
            requested={"USD": order.amount, "api_calls": 1},
            idempotency_key=f"refund-{order.id}-{trace_id}",
            trace_id=trace_id,
            claimed_category="refunds",
            entity_id=order.id,
        )

        if result.all_authorized:
            # Simulate payment processor call — then confirm actual amounts
            guard.confirm(result, confirmed={"USD": order.amount, "api_calls": 1})
            confirmed_event_ids[order.id] = result.event_ids()
            ok(f"  {order.id}  USD {order.amount:.2f}  CONFIRMED  events={result.event_ids()}")
        else:
            bad(f"  {order.id}  DENIED on {result.first_denial_resource}: {result.first_denial.denial_reason}")

    # ── 4. Scenario 2: Delegation cap breach (ORD-004) ────────────────────────
    section("4 / Scenario 2 — Delegation cap breach (ORD-004, $850 vs $200 cap)")
    info("Expected: DENIED — DELEGATE_CAP_EXCEEDED on USD")
    info("CompositeGuard automatically voids the api_call authorization")

    order = ORDERS[3]  # ORD-004
    guard = CompositeGuard([
        GuardedResource(
            client=client,
            session_token=agent_tokens[order.id],
            resource="USD",
        ),
        GuardedResource(
            client=client,
            session_token=api_budget.session_token,
            resource="api_calls",
        ),
    ])

    result = guard.authorize(
        agent_id=f"refund-agent-{order.id}",
        action_type="REFUND",
        description=f"Refund USD {order.amount:.2f} for {order.id} — {order.customer}",
        requested={"USD": order.amount, "api_calls": 1},
        idempotency_key=f"refund-{order.id}-{trace_id}",
        trace_id=trace_id,
        claimed_category="refunds",
        entity_id=order.id,
    )

    if not result.all_authorized:
        bad(f"  {order.id}  DENIED on {result.first_denial_resource}: {result.first_denial.denial_reason}")
        sub(f"Denial message: {result.first_denial.denial_message}")
        sub(f"api_call authorization voided automatically by CompositeGuard")
    else:
        ok(f"  {order.id}  unexpectedly authorized")

    # ── 5. Scenario 3: Duplicate order (ORD-001 again) ───────────────────────
    section("5 / Scenario 3 — Duplicate refund attempt (ORD-001, second agent)")
    info("Expected: DENIED — ENTITY_ALREADY_AUTHORIZED")

    dup_result = client.authorize(
        session_token=agent_tokens["ORD-001"],
        agent_id="refund-agent-ORD-001-duplicate",
        action_type="REFUND",
        description="Refund USD 120.00 for ORD-001 — duplicate attempt",
        requested_quantity=120.00,
        currency="USD",
        claimed_category="refunds",
        entity_id="ORD-001",
        idempotency_key=f"refund-ORD-001-dup-{uuid.uuid4().hex[:8]}",
        trace_id=trace_id,
    )

    if not dup_result.is_authorized:
        bad(f"  ORD-001  DENIED: {dup_result.denial_reason}")
        sub(f"{dup_result.denial_message}")
    else:
        ok(f"  ORD-001  unexpectedly authorized")

    # ── 6. Scenario 4: Per-chain cap (ORD-005) ────────────────────────────────
    section("6 / Scenario 4 — Per-chain spend cap (ORD-005, cap=$300, request=$350)")
    info("Orchestrator authorizes root event with maxSubtreeQuantity=300")
    info("Child agent requests $350 — exceeds the chain cap")
    info("Expected: DENIED — SUBTREE_CAP_EXCEEDED")

    # Orchestrator creates the root event and declares the chain cap
    order = ORDERS[4]  # ORD-005
    root = client.authorize(
        session_token=agent_tokens[order.id],
        agent_id="orchestrator",
        action_type="ORDER_START",
        description=f"Start processing {order.id} — chain cap $300",
        requested_quantity=0.01,          # nominal reservation for the orchestrator node
        currency="USD",
        claimed_category="refunds",
        entity_id=f"{order.id}-root",
        idempotency_key=f"root-{order.id}-{trace_id}",
        trace_id=trace_id,
        max_subtree_quantity=300.00,      # entire chain must stay under $300
    )

    if root.is_authorized:
        ok(f"  Root event authorized: {root.event_id}  (chain cap: $300)")

        # Child agent tries to refund $350 — should be denied
        child = client.authorize(
            session_token=agent_tokens[order.id],
            agent_id=f"refund-agent-{order.id}",
            action_type="REFUND",
            description=f"Refund USD {order.amount:.2f} for {order.id} — {order.customer}",
            requested_quantity=order.amount,   # $350 — exceeds $300 chain cap
            currency="USD",
            claimed_category="refunds",
            entity_id=order.id,
            parent_event_id=root.event_id,
            idempotency_key=f"refund-{order.id}-{trace_id}",
            trace_id=trace_id,
        )

        if not child.is_authorized:
            bad(f"  {order.id}  DENIED: {child.denial_reason}")
            sub(f"{child.denial_message}")
        else:
            ok(f"  {order.id}  unexpectedly authorized")
    else:
        bad(f"  Root event denied: {root.denial_reason}")

    # ── 7. Scenario 5: Cascading void (ORD-003) ──────────────────────────────
    section("7 / Scenario 5 — Cascading void (ORD-003 payment processor failed)")
    info("ORD-003 was CONFIRMED but imagine the downstream system rolled it back.")
    info("void_tree() atomically releases all events in the chain.")
    info("Both fleet budget capacity and api_call quota are returned.")

    if "ORD-003" in confirmed_event_ids:
        fleet_event_id = confirmed_event_ids["ORD-003"][0]
        void_result = client.void_tree(
            event_id=fleet_event_id,
            reason="PAYMENT_PROCESSOR_ROLLBACK",
        )
        ok(f"  Voided {void_result.voided_count} event(s)")
        ok(f"  Fleet budget released: USD {void_result.total_quantity_released:.2f}")
        sub(f"  Voided event IDs: {void_result.voided_event_ids}")
    else:
        info("  (ORD-003 was not confirmed — skipping)")

    # ── 8. Ledger summary ─────────────────────────────────────────────────────
    section("8 / Ledger — fleet budget events")

    ledger = client.get_ledger(budget_id=fleet_budget.id, page=0, size=20)
    state_icons = {
        "AUTHORIZED": "○ AUTHORIZED",
        "CONFIRMED":  "✓ CONFIRMED ",
        "DENIED":     "✗ DENIED    ",
        "VOIDED":     "∅ VOIDED    ",
        "FAILED":     "✗ FAILED    ",
    }
    print(f"  {'DECISION':<14} {'AMOUNT':>10}  {'AGENT':<30}  DENIAL")
    print(f"  {'─'*14} {'─'*10}  {'─'*30}  {'─'*25}")
    for ev in ledger.events:
        icon = state_icons.get(ev.decision, ev.decision)
        denial = ev.denial_reason or ""
        agent = (ev.agent_id or "")[:30]
        print(f"  {icon}  {ev.requested_quantity:>10.2f}  {agent:<30}  {denial}")

    # ── 9. Budget summary ─────────────────────────────────────────────────────
    section("9 / Budget summary")

    fb = client.get_budget(fleet_budget.id)
    ab = client.get_budget(api_budget.id)

    ok(f"Fleet budget   — spent: USD {fb.quantity_spent:.2f}  "
       f"reserved: USD {fb.quantity_reserved:.2f}  "
       f"remaining: USD {fb.available_quantity:.2f}")
    ok(f"API call budget — spent: {ab.quantity_spent:.0f}  "
       f"reserved: {ab.quantity_reserved:.0f}  "
       f"remaining: {ab.available_quantity:.0f}")

    info("")
    info(f"Dashboard  → {base_url}/ui")
    info(f"Fleet ID   → {fleet_budget.id}")
    info(f"API ID     → {api_budget.id}")
    info(f"Trace ID   → {trace_id}")


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="FiGuard fleet refund demo")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--api-key", default="fg_live_demo")
    args = parser.parse_args()
    run(base_url=args.base_url, api_key=args.api_key)
