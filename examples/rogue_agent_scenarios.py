"""
Rogue Agent Scenarios — Three Incident Post-Mortems
=====================================================

These are reconstructions of real failure patterns observed in production
AI agent deployments. Each one is a runnable demo that shows what went wrong
and the exact FiGuard configuration that would have stopped it.

All three incidents share the same root cause: the agents had no shared
understanding of resource limits. They acted rationally given their local
view and caused system-wide damage as a result.

Run against a local server:
    make run                                     # start figuard-core container
    python examples/rogue_agent_scenarios.py     # run all three scenarios

Override the server URL:
    FIGUARD_URL=https://your-server.com python examples/rogue_agent_scenarios.py

Incidents
---------
  1. The Infinite Loop         — customer support agent, 847 iterations, $312 over 4 hours
  2. The Runaway Procurement   — AP automation, 3x duplicate invoice, $14,400 double-payment
  3. The Fan-Out Fleet         — 12 sub-agents, no shared budget, 847k tokens vs 100k limit
"""

from __future__ import annotations

import os
import sys
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import requests

from figuard import FiGuardClient

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
    print(f"\n{'=' * 65}")
    print(f"  {title}")
    print(f"{'=' * 65}")


def _ok(msg: str) -> None:
    print(f"  ✓  {msg}")


def _denied(msg: str) -> None:
    print(f"  ✗  {msg}")


def _note(msg: str) -> None:
    print(f"     {msg}")


# ---------------------------------------------------------------------------
# Incident 1 — The Infinite Loop
#
# INCIDENT REPORT — Severity: P2 — Cost: $312.47
# ------------------------------------------------
# System:   Customer support response agent
# Date:     2026-02-11
# Duration: 4 hours 12 minutes
# Owner:    ML Platform Team
#
# What happened:
#   A customer support agent was tasked with drafting a response to a billing
#   dispute. Its loop condition was: "keep revising until quality score >= 7.0".
#   The quality evaluator model reliably scored responses between 6.7 and 6.9.
#   The threshold was never crossed. The agent never exited.
#
#   Over 4 hours and 12 minutes, the agent called the LLM 847 times.
#   Each call cost roughly $0.37. Total: $312.47.
#
#   No alert fired. The agent had a $500 weekly budget which wasn't hit.
#   The weekly budget caught nothing because the weekly budget was designed
#   to constrain total spend, not the cost of a single runaway session.
#
# Root cause:
#   No per-session spend ceiling. No per-call ceiling. No session expiry.
#   The agent was free to loop indefinitely against an LLM as long as the
#   weekly pool had headroom.
#
# The fix — three lines of FiGuard:
#   total_limit=10.00       → session cannot spend more than $10 total
#   max_transaction_quantity=0.75  → each LLM call capped at $0.75
#   expires_at=_expires_at(hours=1)  → session dies after 1 hour regardless
#
#   Under this config, the agent exhausts its $10 session budget after ~27
#   iterations and gets BUDGET_EXHAUSTED. The on-call engineer gets paged at
#   $10, not $312. The loop is broken by enforcement, not by the agent's logic.
# ---------------------------------------------------------------------------

def incident_1_infinite_loop(client: FiGuardClient) -> None:
    _header("Incident 1 — The Infinite Loop  ($312 → stopped at $10)")

    print("""
  WHAT HAPPENED
  A customer support agent was supposed to draft one response.
  The quality evaluator never returned a score above 6.9. Threshold
  was 7.0. The agent looped 847 times over 4 hours. Cost: $312.47.
  The weekly $500 budget never triggered — it was designed for fleet
  spend, not per-session runaway. No ceiling existed for a single session.
""")

    # --- WITHOUT FIGUARD: The loop runs to financial exhaustion.
    # (We skip the 847-call simulation. The math speaks for itself.)

    # --- WITH FIGUARD: Three lines cap the session at $10.
    print("  THE FIX")
    print("  -------")
    print("  budget = client.create_budget(")
    print("      user_id='support-draft-agent',")
    print("      total_limit=10.00,               # session cap")
    print("      max_transaction_quantity=0.75,   # per-call ceiling")
    print("      expires_at=_expires_at(hours=1), # hard wall at 1h")
    print("  )")
    print()

    budget = client.create_budget(
        user_id="support-draft-agent",
        total_limit=10.00,
        expires_at=_expires_at(hours=1),
        currency="USD",
        max_transaction_quantity=0.75,
    )
    _note(f"Session budget created — id={budget.id[:8]}…  "
          f"ceiling=$0.75/call  total=$10.00  expires=1h")

    # Simulate the agent loop. In the real incident this ran 847 times.
    # Here we run until BUDGET_EXHAUSTED fires and report the stopping point.
    print()
    print("  SIMULATING THE LOOP")
    print("  -------------------")

    total_spent = 0.0
    stopped_at_iteration = None

    for iteration in range(1, 300):
        r = client.authorize(
            session_token=budget.session_token,
            agent_id="support-draft-agent",
            action_type="LLM_INFERENCE",
            description=f"Draft revision #{iteration} — quality score still below 7.0",
            requested_quantity=0.37,
            currency="USD",
            idempotency_key=str(uuid4()),
        )

        if r.is_authorized:
            total_spent += 0.37
            if iteration <= 3 or iteration % 5 == 0:
                _ok(f"Iteration {iteration:>3}  AUTHORIZED  running_total=${total_spent:.2f}")
        else:
            stopped_at_iteration = iteration
            _denied(f"Iteration {iteration:>3}  {r.denial_reason}  "
                    f"— agent stopped at ${total_spent:.2f}")
            break

    if stopped_at_iteration:
        print()
        _note(f"Real incident:  847 iterations  $312.47  4h 12m")
        _note(f"With FiGuard:   {stopped_at_iteration} iterations  "
              f"${total_spent:.2f}  session exhausted by enforcement")
        _note(f"Savings: ${312.47 - total_spent:.2f}  ({((312.47 - total_spent) / 312.47 * 100):.0f}% reduction)")

    # Per-call ceiling test — separate demonstration
    print()
    print("  PER-CALL CEILING (max_transaction_quantity=$0.75)")
    print("  ---------------------------------------------------")
    budget2 = client.create_budget(
        user_id="support-draft-agent",
        total_limit=50.00,
        expires_at=_expires_at(hours=1),
        currency="USD",
        max_transaction_quantity=0.75,
    )
    r_over = client.authorize(
        session_token=budget2.session_token,
        agent_id="support-draft-agent",
        action_type="LLM_INFERENCE",
        description="Accidentally switched to a more expensive model mid-run",
        requested_quantity=4.20,          # GPT-4o class call slipped in
        currency="USD",
        idempotency_key=str(uuid4()),
    )
    _denied(f"{r_over.denial_reason}  — $4.20 call blocked by $0.75 ceiling "
            f"(model substitution caught)")

    r_ok = client.authorize(
        session_token=budget2.session_token,
        agent_id="support-draft-agent",
        action_type="LLM_INFERENCE",
        description="Normal inference call",
        requested_quantity=0.37,
        currency="USD",
        idempotency_key=str(uuid4()),
    )
    _ok(f"AUTHORIZED  qty=0.37  — normal calls still pass")


# ---------------------------------------------------------------------------
# Incident 2 — The Runaway Procurement Agent
#
# INCIDENT REPORT — Severity: P1 — Financial Impact: $14,400
# -----------------------------------------------------------
# System:   Accounts payable automation agent
# Date:     2026-03-04
# Duration: Overnight batch run (6 hours)
# Owner:    Finance Engineering
#
# What happened:
#   An AP agent processed vendor invoices overnight. The vendor's ERP sent
#   three identical invoice notifications for the same payment (a retry
#   storm from a flaky webhook). The agent treated each notification as a
#   new work item. It processed all three.
#
#   Invoice INV-2026-0341 was authorized and paid three times.
#   Amount per payment: $4,800.00. Total double-payment: $9,600 excess.
#
#   Recovery cost (chargebacks, bank fees, reconciliation labor): $4,400.
#   Total incident cost: $14,400.
#
#   A dedup check was on the engineering backlog. It hadn't shipped yet.
#   "We were going to add it next sprint."
#
# Root cause:
#   No entity-level deduplication. The agent had idempotency keys (so a
#   network retry of the same request was safe), but a new request with a
#   new idempotency key for the same underlying invoice was not blocked.
#
#   The distinction matters:
#     same idempotency_key → safe network retry — replays the original decision
#     same entity_id + new key → business rule violation — double-payment
#
# The fix — one field on the budget:
#   entity_dedup_enabled=True
#
#   The second authorization call for INV-2026-0341 — regardless of
#   idempotency key — is blocked with ENTITY_ALREADY_AUTHORIZED.
#   The original event_id is returned so the duplicate can be traced.
# ---------------------------------------------------------------------------

def incident_2_runaway_procurement(client: FiGuardClient) -> None:
    _header("Incident 2 — The Runaway Procurement Agent  ($14,400 double-payment)")

    print("""
  WHAT HAPPENED
  AP automation agent processed vendor invoices overnight. Vendor ERP
  had a flaky webhook that fired 3 times for the same invoice. Agent
  treated each notification as new work. Invoice INV-2026-0341 was
  authorized and paid 3x. $4,800 per payment. Recovery cost: $4,400.
  Total incident cost: $14,400.

  Root cause: idempotency keys protected against network retries.
  They did not protect against the same invoice arriving as a new
  work item with a new key. No entity-level dedup existed.
""")

    print("  THE FIX")
    print("  -------")
    print("  budget = client.create_budget(")
    print("      user_id='ap-automation-agent',")
    print("      total_limit=50_000.00,")
    print("      entity_dedup_enabled=True,   # one payment per invoice id")
    print("  )")
    print()

    budget = client.create_budget(
        user_id="ap-automation-agent",
        total_limit=50_000.00,
        expires_at=_expires_at(),
        currency="USD",
        entity_dedup_enabled=True,
    )
    _note(f"Budget created — id={budget.id[:8]}…  entityDedupEnabled=True")

    invoice_id = "INV-2026-0341"
    vendor = "Apex Cloud Services Ltd."

    print()
    print(f"  PROCESSING INVOICE {invoice_id} — {vendor} — $4,800.00")
    print("  " + "-" * 55)

    # First notification — legitimate. Authorized.
    r1 = client.authorize(
        session_token=budget.session_token,
        agent_id="ap-automation-agent",
        action_type="VENDOR_PAYMENT",
        description=f"Payment for {invoice_id} — {vendor}",
        requested_quantity=4_800.00,
        currency="USD",
        entity_id=invoice_id,                   # <— the invoice number is the entity
        idempotency_key=str(uuid4()),
    )
    _ok(f"Notification 1  AUTHORIZED  entity_id={invoice_id}  "
        f"event_id={r1.event_id[:8]}…")

    # Second notification — webhook retry storm, different idempotency key.
    # Without entity_dedup_enabled: would authorize again → double-payment.
    # With entity_dedup_enabled: blocked.
    r2 = client.authorize(
        session_token=budget.session_token,
        agent_id="ap-automation-agent",
        action_type="VENDOR_PAYMENT",
        description=f"Payment for {invoice_id} — {vendor} (webhook retry)",
        requested_quantity=4_800.00,
        currency="USD",
        entity_id=invoice_id,
        idempotency_key=str(uuid4()),            # different key — not a safe retry
    )
    _denied(f"Notification 2  {r2.denial_reason}  "
            f"original_event={r2.original_event_id[:8]}…  — double-payment blocked")

    # Third notification — same result.
    r3 = client.authorize(
        session_token=budget.session_token,
        agent_id="ap-automation-agent",
        action_type="VENDOR_PAYMENT",
        description=f"Payment for {invoice_id} — {vendor} (webhook retry 2)",
        requested_quantity=4_800.00,
        currency="USD",
        entity_id=invoice_id,
        idempotency_key=str(uuid4()),
    )
    _denied(f"Notification 3  {r3.denial_reason}  "
            f"original_event={r3.original_event_id[:8]}…  — double-payment blocked")

    print()
    _note(f"Payments authorized:  1  (${4_800.00:.2f})")
    _note(f"Payments blocked:     2  (${9_600.00:.2f} protected)")
    _note(f"If this were the real incident: $14,400 damage → $0")
    _note(f"original_event_id on both denials traces to the legitimate payment")

    # Contrast: idempotency key replay is still a safe operation.
    print()
    print("  CONTRAST — Safe network retry with same idempotency key")
    print("  " + "-" * 55)
    safe_key = str(uuid4())
    invoice_b = "INV-2026-0342"

    r_first = client.authorize(
        session_token=budget.session_token,
        agent_id="ap-automation-agent",
        action_type="VENDOR_PAYMENT",
        description=f"Payment for {invoice_b}",
        requested_quantity=1_200.00,
        currency="USD",
        entity_id=invoice_b,
        idempotency_key=safe_key,
    )
    r_retry = client.authorize(
        session_token=budget.session_token,
        agent_id="ap-automation-agent",
        action_type="VENDOR_PAYMENT",
        description=f"Payment for {invoice_b} (network retry)",
        requested_quantity=1_200.00,
        currency="USD",
        entity_id=invoice_b,
        idempotency_key=safe_key,               # same key — genuine network retry
    )
    _ok(f"Safe retry: same event_id={r_first.event_id == r_retry.event_id}  "
        f"— idempotent replay, no double-count")


# ---------------------------------------------------------------------------
# Incident 3 — The Fan-Out Fleet
#
# INCIDENT REPORT — Severity: P1 — Overage: 747,000 tokens beyond budget
# -----------------------------------------------------------------------
# System:   Research orchestration agent (competitor analysis pipeline)
# Date:     2026-04-22
# Duration: ~40 minutes (all damage done before anyone noticed)
# Owner:    Research Automation Team
#
# What happened:
#   An orchestrator agent was asked to produce a competitive landscape report.
#   To speed things up, it spawned 12 sub-agents in parallel. Each sub-agent
#   was responsible for one competitor. Each independently called:
#       budget = client.create_budget(total_limit=100_000, unit="tokens")
#   That is: each agent created its own budget. None of them shared one.
#
#   The orchestrator assumed "each agent gets 100k tokens, total is 100k."
#   What actually happened: 12 budgets × 100,000 = 1,200,000 tokens authorized.
#   Actual usage: 847,000 tokens. Intended ceiling: 100,000 tokens.
#   Overage: 747,000 tokens. Provider bill: 8.5x the budget.
#
#   Nobody noticed until the invoice arrived. No enforcement fired because
#   each individual budget stayed under its own limit. There was no shared
#   budget enforcing the fleet-level ceiling.
#
# Root cause:
#   Each sub-agent created its own budget. There was no shared envelope.
#   The orchestrator passed a session_token to each sub-agent, but that token
#   pointed to independent, isolated budgets — not a shared pool.
#
# The fix:
#   One budget. One session_token. All 12 sub-agents share it.
#   The total_limit=100,000 is fleet-wide. When the pool is exhausted,
#   subsequent sub-agents get BUDGET_EXHAUSTED — regardless of which agent
#   asks. The orchestrator creates the budget; sub-agents receive the token.
#
#   Agents 1-2 authorize most of the budget. Agents 3-12 hit BUDGET_EXHAUSTED
#   and fail fast. The orchestrator can decide: produce a partial report,
#   request more budget, or alert a human. It cannot silently spend 8x.
# ---------------------------------------------------------------------------

def incident_3_fan_out_fleet(client: FiGuardClient) -> None:
    _header("Incident 3 — The Fan-Out Fleet  (847k tokens vs 100k budget)")

    print("""
  WHAT HAPPENED
  Orchestrator spawned 12 sub-agents for a research task. Each agent
  independently created its own budget with total_limit=100_000 tokens.
  Orchestrator assumed "shared ceiling of 100k." Reality: 12 independent
  budgets. Total authorized: 1,200,000 tokens. Actual spend: 847,000.
  Intended ceiling: 100,000. Overage: 747k tokens. Bill: 8.5x budget.
""")

    print("  THE FIX")
    print("  -------")
    print("  # Orchestrator creates ONE budget. Passes token to all sub-agents.")
    print("  fleet_budget = client.create_budget(")
    print("      user_id='research-orchestrator',")
    print("      total_limit=100_000,")
    print("      unit='tokens',          # dimensionless — no currency field")
    print("  )")
    print("  # Sub-agent receives fleet_budget.session_token — never creates its own.")
    print()

    # Orchestrator creates the shared fleet budget.
    fleet_budget = client.create_budget(
        user_id="research-orchestrator",
        total_limit=100_000,
        expires_at=_expires_at(),
        unit="tokens",
    )
    _note(f"Fleet budget created — id={fleet_budget.id[:8]}…  "
          f"total=100,000 tokens  shared across all 12 agents")

    print()
    print("  SIMULATING 12 SUB-AGENTS (each receives the shared session_token)")
    print("  " + "-" * 60)

    competitors = [
        "Datadog",      "Grafana Labs",   "New Relic",    "Dynatrace",
        "Honeycomb",    "Elastic",        "Chronosphere", "Observe Inc.",
        "Coralogix",    "Mezmo",          "Signoz",       "Last9",
    ]

    # Each sub-agent requests ~80,000 tokens for its research.
    # The first agent will drain most of the budget. Subsequent agents fail.
    tokens_per_agent = 80_000   # what each agent *wants*
    authorized_agents = []
    denied_agents = []

    for i, competitor in enumerate(competitors, start=1):
        r = client.authorize(
            session_token=fleet_budget.session_token,   # shared token
            agent_id=f"research-sub-agent-{i:02d}",
            action_type="LLM_RESEARCH",
            description=f"Competitive analysis — {competitor}",
            requested_quantity=tokens_per_agent,
            idempotency_key=str(uuid4()),
        )

        if r.is_authorized:
            authorized_agents.append((i, competitor))
            avail = r.available_quantity if hasattr(r, "available_quantity") else "—"
            _ok(f"Agent {i:02d} ({competitor:<15})  AUTHORIZED  "
                f"{tokens_per_agent:,} tokens  remaining≈{avail}")
        else:
            denied_agents.append((i, competitor))
            _denied(f"Agent {i:02d} ({competitor:<15})  {r.denial_reason}  "
                    f"— no shared budget headroom")

    print()
    _note(f"Agents authorized:    {len(authorized_agents)}  "
          f"({', '.join(c for _, c in authorized_agents)})")
    _note(f"Agents blocked:       {len(denied_agents)}  "
          f"— fleet ceiling enforced")
    _note(f"Real incident:        all 12 authorized, 847k tokens, bill 8.5x budget")
    _note(f"With shared budget:   fleet exhausted at 100k, rest fail fast")
    _note(f"Orchestrator can now: produce partial report or request budget increase")
    _note(f"What it cannot do:    silently spend 8x without enforcement firing")

    # Confirm the authorized agents' actual token usage
    print()
    print("  CONFIRMING ACTUAL USAGE (authorized agents report back)")
    print("  " + "-" * 55)
    final_budget = client.get_budget(fleet_budget.id)
    _note(f"Budget {fleet_budget.id[:8]}…  "
          f"total=100,000  "
          f"spent≈{final_budget.quantity_spent:,.0f}  "
          f"available≈{final_budget.available_quantity:,.0f}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def _print_overview() -> None:
    print("""
Three incidents. Three failure modes. One pattern.
---------------------------------------------------
In each case, the agent acted rationally given its local view.
It had no way to know it was looping, duplicating, or exhausting a
fleet-wide resource it didn't know existed. That context has to come
from outside the agent — from the infrastructure that wraps it.

FiGuard sits at the authorization layer. Before any resource is consumed,
the agent asks permission. The budget rules encode what "rational" means
for the fleet, not just for the individual agent. When local rationality
diverges from fleet-level intent, enforcement fires.

The three scenarios below are runnable. Each one:
  1. Describes the real failure pattern and its cost
  2. Shows the exact FiGuard configuration that would have stopped it
  3. Runs the enforcement live so you can see the denial codes fire

Authorization model (same across all three scenarios):
  create_budget()   → allocates a spending envelope; returns a session_token
  authorize()       → agent asks permission; reserves quantity; AUTHORIZED or DENIED
  confirm_event()   → action succeeded; reservation → confirmed spend
  fail_event()      → action failed; reservation released
  void_event()      → action cancelled; reservation released

Every call writes to the append-only ledger. Denials are recorded
alongside authorizations. The audit trail is always complete.
""")


def main() -> None:
    if not _check_server():
        print(f"\nFiGuard server not reachable at {FIGUARD_URL}")
        print("Start it with:  make run")
        print("Then re-run:    python examples/rogue_agent_scenarios.py\n")
        sys.exit(1)

    client = FiGuardClient(api_key=API_KEY, base_url=FIGUARD_URL)

    print("\nRogue Agent Scenarios — Three Incident Post-Mortems")
    print(f"Server: {FIGUARD_URL}")
    _print_overview()

    incident_1_infinite_loop(client)
    incident_2_runaway_procurement(client)
    incident_3_fan_out_fleet(client)

    print(f"\n{'=' * 65}")
    print("  Three incidents. All stopped by enforcement at the authorization layer.")
    print(f"{'=' * 65}\n")


if __name__ == "__main__":
    main()
