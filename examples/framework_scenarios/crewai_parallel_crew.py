#!/usr/bin/env python3
"""
Scenario: CrewAI Parallel Crew → Per-Agent Cost Invisibility

THE PROBLEM
-----------
CrewAI's parallel process runs all agents simultaneously. Each agent uses
its own tools independently. A market researcher on an ambiguous brief
makes 40 API calls to a paid data source instead of the expected 5. You
have no way to know this until the invoice arrives — CrewAI doesn't track
per-agent spend, and the costs are spread across tool invocations that
each look individually reasonable.

The financial analyst and report writer, whose work was fine, get no
special treatment. All three agents share one implicit "unlimited" budget.

THE FIX
-------
FiGuard issues a delegation token per crew member before the crew runs.
Each token has a per-agent cap. When the researcher exceeds its cap the
remaining calls are denied — the analyst and writer keep running on their
own tokens, unaffected. The spend tree shows every call by every agent.

MODES
-----
simulation  No API keys needed. Agent behaviour is pre-scripted.
            FiGuard runs against the live sandbox.

real        Runs a real CrewAI crew (OpenAI). Requires OPENAI_API_KEY.
            Simulated API costs are still mocked — no real data API key
            needed. The FiGuard enforcement is real.

USAGE
-----
python crewai_parallel_crew.py
python crewai_parallel_crew.py --mode real --openai-key sk-...

DASHBOARD
---------
https://figuard-sandbox-g1ha.onrender.com/ui
"""

import argparse
import sys
import uuid
from typing import Optional

# ── Scenario tuning ────────────────────────────────────────────────────────────
CREW_BUDGET          = 5.00    # total budget for the crew run
RESEARCHER_CAP       = 2.00    # market researcher cap
ANALYST_CAP          = 2.00    # financial analyst cap
WRITER_CAP           = 1.00    # report writer cap

COST_PER_DATA_CALL   = 0.10    # cost per data API call
RESEARCHER_CALLS     = 25      # researcher makes 25 calls (expects ~5) → $2.50 > $2.00 cap
ANALYST_CALLS        = 8       # analyst: 8 × $0.10 = $0.80
WRITER_CALLS         = 4       # writer: 4 × $0.10 = $0.40

FIGUARD_BASE_URL     = "https://figuard-sandbox-g1ha.onrender.com"
FIGUARD_API_KEY      = "sb_live_demo"


# ── Display helpers ────────────────────────────────────────────────────────────

def section(title: str) -> None:
    print(f"\n{'═' * 64}")
    print(f"  {title}")
    print(f"{'═' * 64}")

def ok(msg: str)   -> None: print(f"  ✓  {msg}")
def bad(msg: str)  -> None: print(f"  ✗  {msg}")
def info(msg: str) -> None: print(f"     {msg}")
def step(msg: str) -> None: print(f"  →  {msg}")
def crew(role: str, msg: str) -> None:
    print(f"  [{role:<18}]  {msg}")


# ── Simulated crew member work ─────────────────────────────────────────────────

def run_market_researcher(
    n_calls: int = RESEARCHER_CALLS,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
) -> tuple[int, float]:
    """
    Makes repeated calls to a paid market data API.
    On an ambiguous brief it keeps refining the query — each attempt
    looks like a legitimate search but the total is far above budget.
    """
    spent = 0.0
    for i in range(1, n_calls + 1):
        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="market_researcher",
                action_type="DATA_FETCH",
                description=f"Market data API call {i} — refining query",
                requested_quantity=COST_PER_DATA_CALL,
                idempotency_key=f"{label}-researcher-{i}",
            )
            if not auth.is_authorized:
                crew("market_researcher",
                     f"call {i} denied — {auth.denial_reason}. stopping.")
                return i - 1, spent
            crew("market_researcher",
                 f"call {i:>2} authorized (event {auth.event_id[:8]}…)")
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_DATA_CALL)
        else:
            crew("market_researcher", f"call {i:>2} — fetching market data…")
        spent += COST_PER_DATA_CALL
    return n_calls, spent


def run_financial_analyst(
    n_calls: int = ANALYST_CALLS,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
) -> tuple[int, float]:
    """Financial analyst: well-scoped task, predictable call count."""
    spent = 0.0
    for i in range(1, n_calls + 1):
        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="financial_analyst",
                action_type="DATA_FETCH",
                description=f"Financial data lookup {i}",
                requested_quantity=COST_PER_DATA_CALL,
                idempotency_key=f"{label}-analyst-{i}",
            )
            if not auth.is_authorized:
                crew("financial_analyst",
                     f"call {i} denied — {auth.denial_reason}")
                return i - 1, spent
            crew("financial_analyst",
                 f"call {i:>2} authorized (event {auth.event_id[:8]}…)")
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_DATA_CALL)
        else:
            crew("financial_analyst", f"call {i:>2} — pulling financials…")
        spent += COST_PER_DATA_CALL
    return n_calls, spent


def run_report_writer(
    n_calls: int = WRITER_CALLS,
    figuard=None,
    token: Optional[str] = None,
    label: str = "",
) -> tuple[int, float]:
    """Report writer: cheapest crew member, uses only LLM calls."""
    spent = 0.0
    for i in range(1, n_calls + 1):
        if figuard and token:
            auth = figuard.authorize(
                session_token=token,
                agent_id="report_writer",
                action_type="GENERATE",
                description=f"Draft report section {i}",
                requested_quantity=COST_PER_DATA_CALL,
                idempotency_key=f"{label}-writer-{i}",
            )
            if not auth.is_authorized:
                crew("report_writer",
                     f"call {i} denied — {auth.denial_reason}")
                return i - 1, spent
            crew("report_writer",
                 f"call {i:>2} authorized (event {auth.event_id[:8]}…)")
            figuard.confirm_event(auth.event_id, confirmed_quantity=COST_PER_DATA_CALL)
        else:
            crew("report_writer", f"call {i:>2} — writing section…")
        spent += COST_PER_DATA_CALL
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
    crew_budget = figuard.create_budget(
        user_id="crew_orchestrator",
        total_limit=CREW_BUDGET,
        currency="USD",
        expires_in="1h",
    )
    fleet_token = crew_budget.session_token
    print("ready.")

    print(f"\nMode             : {mode}")
    print(f"Crew budget      : ${CREW_BUDGET:.2f}")
    print(f"researcher cap   : ${RESEARCHER_CAP:.2f}  "
          f"({int(RESEARCHER_CAP / COST_PER_DATA_CALL)} calls max)")
    print(f"analyst cap      : ${ANALYST_CAP:.2f}")
    print(f"writer cap       : ${WRITER_CAP:.2f}")
    print(f"researcher plans : {RESEARCHER_CALLS} calls × "
          f"${COST_PER_DATA_CALL:.2f} = "
          f"${RESEARCHER_CALLS * COST_PER_DATA_CALL:.2f}  ← over cap")
    print(f"Dashboard        : {FIGUARD_BASE_URL}/ui")

    # ── PART 1: Without FiGuard ────────────────────────────────────────────────

    section("PART 1 — Without FiGuard")
    info("All three crew members run in parallel.")
    info("Market researcher keeps refining its query — 25 data API calls.")
    info("No per-agent visibility. Costs aggregate invisibly.\n")

    r_calls, r_cost = run_market_researcher()
    a_calls, a_cost = run_financial_analyst()
    w_calls, w_cost = run_report_writer()

    total = r_cost + a_cost + w_cost
    print()
    bad(f"  market_researcher  : {r_calls} calls  ${r_cost:.2f}  ← runaway")
    info(f"  financial_analyst  : {a_calls} calls  ${a_cost:.2f}")
    info(f"  report_writer      : {w_calls} calls  ${w_cost:.2f}")
    bad(f"\nTotal crew cost: ${total:.2f}  (budget was ${CREW_BUDGET:.2f})")
    bad("No attribution. You only find out at billing time.")

    # ── PART 2: With FiGuard ───────────────────────────────────────────────────

    section("PART 2 — With FiGuard delegation tokens")
    info("Same crew, same brief. One delegation token per crew member.")
    info("Researcher hits its cap. Analyst and writer complete normally.\n")

    researcher_token = figuard.create_delegation_token(
        budget_id=crew_budget.id,
        label="market-researcher",
        caps=[{"category": "research", "limit": RESEARCHER_CAP}],
    )
    analyst_token = figuard.create_delegation_token(
        budget_id=crew_budget.id,
        label="financial-analyst",
        caps=[{"category": "analysis", "limit": ANALYST_CAP}],
    )
    writer_token = figuard.create_delegation_token(
        budget_id=crew_budget.id,
        label="report-writer",
        caps=[{"category": "writing", "limit": WRITER_CAP}],
    )

    ok(f"Crew budget : {crew_budget.id}")
    ok(f"researcher  : cap ${RESEARCHER_CAP:.2f}  "
       f"token {researcher_token.session_token[:12]}…")
    ok(f"analyst     : cap ${ANALYST_CAP:.2f}  "
       f"token {analyst_token.session_token[:12]}…")
    ok(f"writer      : cap ${WRITER_CAP:.2f}  "
       f"token {writer_token.session_token[:12]}…")
    info("")

    r_calls, r_cost = run_market_researcher(
        figuard=figuard, token=researcher_token.session_token, label=run_id,
    )
    a_calls, a_cost = run_financial_analyst(
        figuard=figuard, token=analyst_token.session_token, label=run_id,
    )
    w_calls, w_cost = run_report_writer(
        figuard=figuard, token=writer_token.session_token, label=run_id,
    )

    total_guarded = r_cost + a_cost + w_cost
    saved = (RESEARCHER_CALLS * COST_PER_DATA_CALL) - r_cost
    print()
    bad(f"  market_researcher  : {r_calls} calls  ${r_cost:.2f}  "
        f"— capped (saved ${saved:.2f})")
    ok(f"  financial_analyst  : {a_calls} calls  ${a_cost:.2f}  — completed")
    ok(f"  report_writer      : {w_calls} calls  ${w_cost:.2f}  — completed")
    ok(f"\nTotal crew cost: ${total_guarded:.2f}  (saved ${(total - total_guarded):.2f})")
    ok("Analyst and writer were never blocked by the researcher's runaway.")

    # ── Summary ────────────────────────────────────────────────────────────────

    b = figuard.get_budget(crew_budget.id)

    section("What FiGuard recorded")
    info(f"Dashboard : {FIGUARD_BASE_URL}/ui")
    info(f"Budget    : {crew_budget.id}")
    info("")
    ok(f"Fleet spent : ${b.quantity_spent:.2f} of ${CREW_BUDGET:.2f}")
    ok(f"Remaining   : ${b.available_quantity:.2f}")
    info("")
    info("Open the budget → Spend Tree.")
    info("Three delegation sub-trees, one per crew member.")
    info("Researcher sub-tree: CONFIRMED × 20, then DENIED / DELEGATE_CAP_EXCEEDED.")
    info("Analyst and writer sub-trees: all CONFIRMED.")
    info("")
    info("This is the per-agent attribution CrewAI doesn't give you by default.")


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="CrewAI parallel crew — per-agent cost invisibility scenario"
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
