#!/usr/bin/env python3
"""
Scenario: LangGraph Supervisor Fleet → Agent Cost Bleed-Through

THE PROBLEM
-----------
The standard LangGraph multi-agent pattern is a supervisor that routes
tasks to specialised sub-agents. When routing logic has a bug — or when
the LLM supervisor decides a task needs multiple agents — the same task
flows through several agents sequentially. Each agent makes its own LLM
calls and tool calls. Per-task cost multiplies silently.

One agent misbehaving (stuck in a sub-loop, calling expensive tools
repeatedly) drains the shared budget. The other agents get blocked even
though they did nothing wrong.

THE FIX
-------
FiGuard issues a delegation token per sub-agent. Each token has a hard
cap. When the researcher blows its $3.00 cap the billing and writer
agents are unaffected — they have their own tokens with their own limits.
The spend tree shows exactly which agent caused the overrun.

MODES
-----
simulation  No API keys needed. Agent decisions are pre-scripted.
            FiGuard runs against the live sandbox.

real        Uses a real LangGraph supervisor + sub-agents (OpenAI).
            Requires OPENAI_API_KEY.

USAGE
-----
python langgraph_supervisor_fleet.py
python langgraph_supervisor_fleet.py --mode real --openai-key sk-...

DASHBOARD
---------
https://figuard-sandbox-1.onrender.com/ui
Open the fleet budget → Spend Tree to see the per-agent causal chain.
"""

import argparse
import sys
import uuid
from typing import Optional

# ── Per-agent delegation caps ─────────────────────────────────────────────────
FLEET_LIMIT       = 10.00   # total fleet budget
BILLING_CAP       = 4.00    # billing agent cap
RESEARCHER_CAP    = 3.00    # researcher cap — this agent overspends in the demo
WRITER_CAP        = 2.00    # writer cap

# Cost per tool call (simulated)
COST_PER_CALL     = 0.50

# How many tool calls the rogue researcher makes
RESEARCHER_CALLS  = 9       # 9 × $0.50 = $4.50 → exceeds $3.00 cap at call 7

FIGUARD_BASE_URL  = "https://figuard-sandbox-1.onrender.com"
FIGUARD_API_KEY   = "sb_live_demo"


# ── Display helpers ────────────────────────────────────────────────────────────

def section(title: str) -> None:
    print(f"\n{'═' * 64}")
    print(f"  {title}")
    print(f"{'═' * 64}")

def ok(msg: str)   -> None: print(f"  ✓  {msg}")
def bad(msg: str)  -> None: print(f"  ✗  {msg}")
def info(msg: str) -> None: print(f"     {msg}")
def step(msg: str) -> None: print(f"  →  {msg}")
def agent(name: str, msg: str) -> None:
    print(f"  [{name:<12}]  {msg}")


# ── Simulated agent actions ────────────────────────────────────────────────────

def run_billing_agent(
    n_calls: int = 2,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
) -> tuple[int, float]:
    """Billing agent: processes invoices. Well-behaved, stays in budget."""
    spent = 0.0
    for i in range(1, n_calls + 1):
        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="billing_agent",
                action_type="INVOICE_PROCESS",
                description=f"Process invoice batch {i}",
                requested_quantity=COST_PER_CALL,
                idempotency_key=f"{label}-billing-{i}",
            )
            if not auth.is_authorized:
                agent("billing", f"call {i} denied — {auth.denial_reason}")
                return i - 1, spent
            agent("billing", f"call {i} authorized (event {auth.event_id[:8]}…)")
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_CALL)
        else:
            agent("billing", f"call {i} — processing invoice batch")
        spent += COST_PER_CALL
    return n_calls, spent


def run_researcher_agent(
    n_calls: int = RESEARCHER_CALLS,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
) -> tuple[int, float]:
    """Researcher agent: makes too many search calls on an ambiguous query."""
    spent = 0.0
    for i in range(1, n_calls + 1):
        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="researcher_agent",
                action_type="SEARCH",
                description=f"Research query iteration {i}",
                requested_quantity=COST_PER_CALL,
                idempotency_key=f"{label}-research-{i}",
            )
            if not auth.is_authorized:
                agent("researcher", f"call {i} denied — {auth.denial_reason}")
                agent("researcher", "cap hit — stopping. other agents unaffected.")
                return i - 1, spent
            agent("researcher", f"call {i} authorized (event {auth.event_id[:8]}…)")
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_CALL)
        else:
            agent("researcher", f"call {i} — searching…")
        spent += COST_PER_CALL
    return n_calls, spent


def run_writer_agent(
    n_calls: int = 2,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
) -> tuple[int, float]:
    """Writer agent: generates report. Well-behaved."""
    spent = 0.0
    for i in range(1, n_calls + 1):
        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="writer_agent",
                action_type="GENERATE",
                description=f"Report section {i}",
                requested_quantity=COST_PER_CALL,
                idempotency_key=f"{label}-writer-{i}",
            )
            if not auth.is_authorized:
                agent("writer", f"call {i} denied — {auth.denial_reason}")
                return i - 1, spent
            agent("writer", f"call {i} authorized (event {auth.event_id[:8]}…)")
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_CALL)
        else:
            agent("writer", f"call {i} — writing report section")
        spent += COST_PER_CALL
    return n_calls, spent


# ── Scenario runner ────────────────────────────────────────────────────────────

def run(mode: str, openai_key: Optional[str]) -> None:

    try:
        from figuard import FiGuardClient
    except ImportError:
        print("Install FiGuard: pip install figuard")
        sys.exit(1)

    figuard = FiGuardClient(api_key=FIGUARD_API_KEY, base_url=FIGUARD_BASE_URL)

    print("\nConnecting to FiGuard sandbox…", end=" ", flush=True)
    run_id = uuid.uuid4().hex[:8]
    # Create fleet budget upfront — keeps sandbox warm through Part 1.
    fleet_budget = figuard.create_budget(
        user_id="orchestrator",
        total_limit=FLEET_LIMIT,
        currency="USD",
        expires_in="1h",
    )
    fleet_token = fleet_budget.session_token
    print("ready.")

    print(f"\nMode         : {mode}")
    print(f"Fleet limit  : ${FLEET_LIMIT:.2f}")
    print(f"Caps         : billing=${BILLING_CAP:.2f}  "
          f"researcher=${RESEARCHER_CAP:.2f}  writer=${WRITER_CAP:.2f}")
    print(f"Rogue calls  : researcher will attempt {RESEARCHER_CALLS} × "
          f"${COST_PER_CALL:.2f} = ${RESEARCHER_CALLS * COST_PER_CALL:.2f}")
    print(f"Dashboard    : {FIGUARD_BASE_URL}/ui")

    # ── PART 1: Without FiGuard ────────────────────────────────────────────────

    section("PART 1 — Without FiGuard")
    info("Supervisor routes task to all three agents.")
    info("Researcher loops on ambiguous query — no cap stops it.")
    info("All costs charged to the shared fleet budget.\n")

    b_calls, b_cost = run_billing_agent(n_calls=2)
    r_calls, r_cost = run_researcher_agent()
    w_calls, w_cost = run_writer_agent(n_calls=2)

    total = b_cost + r_cost + w_cost
    print()
    info(f"  billing    : {b_calls} calls  ${b_cost:.2f}")
    info(f"  researcher : {r_calls} calls  ${r_cost:.2f}  ← runaway")
    info(f"  writer     : {w_calls} calls  ${w_cost:.2f}")
    bad(f"Total fleet cost: ${total:.2f}  (budget was ${FLEET_LIMIT:.2f})")
    bad("No per-agent visibility. No way to know which agent overspent.")

    # ── PART 2: With FiGuard delegation tokens ─────────────────────────────────

    section("PART 2 — With FiGuard delegation tokens")
    info("Same fleet budget. Each agent gets a delegation token with a hard cap.")
    info("Researcher hits its cap. Billing and writer are unaffected.\n")

    # Issue one delegation token per sub-agent.
    billing_token = figuard.create_delegation_token(
        budget_id=fleet_budget.id,
        label="billing-agent",
        caps=[{"category": "billing", "limit": BILLING_CAP}],
    )
    researcher_token = figuard.create_delegation_token(
        budget_id=fleet_budget.id,
        label="researcher-agent",
        caps=[{"category": "research", "limit": RESEARCHER_CAP}],
    )
    writer_token = figuard.create_delegation_token(
        budget_id=fleet_budget.id,
        label="writer-agent",
        caps=[{"category": "writer", "limit": WRITER_CAP}],
    )

    ok(f"Fleet budget : {fleet_budget.id}")
    ok(f"billing      : cap ${BILLING_CAP:.2f}  "
       f"token {billing_token.session_token[:12]}…")
    ok(f"researcher   : cap ${RESEARCHER_CAP:.2f}  "
       f"token {researcher_token.session_token[:12]}…")
    ok(f"writer       : cap ${WRITER_CAP:.2f}  "
       f"token {writer_token.session_token[:12]}…")
    info("")

    b_calls, b_cost = run_billing_agent(
        n_calls=2, figuard=figuard,
        token=billing_token.session_token, label=run_id,
    )
    r_calls, r_cost = run_researcher_agent(
        figuard=figuard,
        token=researcher_token.session_token, label=run_id,
    )
    w_calls, w_cost = run_writer_agent(
        n_calls=2, figuard=figuard,
        token=writer_token.session_token, label=run_id,
    )

    total_guarded = b_cost + r_cost + w_cost
    print()
    ok(f"  billing    : {b_calls} calls  ${b_cost:.2f}  — completed")
    bad(f"  researcher : {r_calls} calls  ${r_cost:.2f}  "
        f"— capped at ${RESEARCHER_CAP:.2f} (saved "
        f"${(RESEARCHER_CALLS * COST_PER_CALL - r_cost):.2f})")
    ok(f"  writer     : {w_calls} calls  ${w_cost:.2f}  — completed")
    ok(f"Total fleet cost: ${total_guarded:.2f}  "
       f"(saved ${(total - total_guarded):.2f} vs unguarded)")

    # ── Budget summary ─────────────────────────────────────────────────────────

    b = figuard.get_budget(fleet_budget.id)

    section("What FiGuard recorded")
    info(f"Dashboard : {FIGUARD_BASE_URL}/ui")
    info(f"Budget    : {fleet_budget.id}")
    info("")
    ok(f"Fleet spent : ${b.quantity_spent:.2f} of ${FLEET_LIMIT:.2f}")
    ok(f"Remaining   : ${b.available_quantity:.2f}")
    info("")
    info("Open the budget → Spend Tree.")
    info("You'll see three sub-trees — one per agent.")
    info("The researcher sub-tree ends with DENIED / DELEGATE_CAP_EXCEEDED.")
    info("Billing and writer sub-trees end with CONFIRMED.")
    info("")
    info("Without delegation tokens: one flat ledger, no attribution.")
    info("With delegation tokens: per-agent spend is visible and bounded.")


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="LangGraph supervisor fleet — agent bleed-through scenario"
    )
    parser.add_argument(
        "--mode", choices=["simulation", "real"], default="simulation",
    )
    parser.add_argument("--openai-key", default=None, help="Required for --mode real")
    args = parser.parse_args()

    if args.mode == "real" and not args.openai_key:
        print("--openai-key required for real mode")
        sys.exit(1)

    run(mode=args.mode, openai_key=args.openai_key)
