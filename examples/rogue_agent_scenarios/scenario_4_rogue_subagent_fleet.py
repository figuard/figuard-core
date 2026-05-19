"""
Scenario 4 — The Rogue Sub-Agent In A Fleet

THE INCIDENT
A CrewAI research fleet shared a $1,000 budget. One sub-agent hallucinated
a tool parameter and called a search API in a tight loop. It exhausted the
entire fleet budget. The analyst and writer agents couldn't complete their tasks.

THE FIX
Each sub-agent gets a scoped delegation token with its own hard cap.
The researcher can only consume its $200 allocation — the rest of the fleet
is unaffected regardless of what the researcher does.

Run:
    pip install figuard
    python scenario_4_rogue_subagent_fleet.py
"""

from figuard import FiGuardClient

# ── ANSI colours ──────────────────────────────────────────────────────────────
RED    = "\033[91m"
GREEN  = "\033[32m"
YELLOW = "\033[93m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

figuard = FiGuardClient(
    api_key="ab_live_demo",            # sandbox: use "sb_live_demo"
    base_url="http://localhost:8080",  # sandbox: use "https://figuard-sandbox-1.onrender.com"
)

FLEET_BUDGET      = 1000.00
RESEARCHER_CAP    = 200.00
ANALYST_CAP       = 300.00
WRITER_CAP        = 200.00
SEARCH_CALL_COST  = 5.00

# ── WITHOUT FIGUARD ───────────────────────────────────────────────────────────
print(f"\n{BOLD}{RED}━━━  WITHOUT FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
print(f"  Fleet budget: ${FLEET_BUDGET:,.2f}  |  researcher / analyst / writer share one token")
print()
rogue_calls = int(FLEET_BUDGET / SEARCH_CALL_COST)
for call in [1, 2, 10, 20, 50, 100, rogue_calls]:
    spent = call * SEARCH_CALL_COST
    print(f"  {RED}call {call:>4d}: researcher AUTHORIZED  (${spent:.2f} of ${FLEET_BUDGET:,.2f}){RESET}")
print()
print(f"  {RED}✗  Researcher exhausted full ${FLEET_BUDGET:,.2f} fleet budget{RESET}")
print(f"  {RED}✗  Analyst:  DENIED — no funds left{RESET}")
print(f"  {RED}✗  Writer:   DENIED — no funds left{RESET}")

# ── WITH FIGUARD ──────────────────────────────────────────────────────────────
print(f"\n{BOLD}{GREEN}━━━  WITH FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")

fleet = figuard.create_budget(
    user_id="crew_manager",
    total_limit=FLEET_BUDGET,
    currency="USD",
    expires_in="2h",
)

researcher_token = figuard.create_delegation_token(
    budget_id=fleet.id,
    label="researcher",
    caps=[{"category": "search_api", "limit": RESEARCHER_CAP}],
)
analyst_token = figuard.create_delegation_token(
    budget_id=fleet.id,
    label="analyst",
    caps=[{"category": "llm_calls", "limit": ANALYST_CAP}],
)
writer_token = figuard.create_delegation_token(
    budget_id=fleet.id,
    label="writer",
    caps=[{"category": "llm_calls", "limit": WRITER_CAP}],
)

print(f"  Fleet: {BOLD}${fleet.total_limit:,.2f}{RESET}  |  "
      f"researcher {YELLOW}${RESEARCHER_CAP:.0f} cap{RESET}  "
      f"analyst {YELLOW}${ANALYST_CAP:.0f} cap{RESET}  "
      f"writer {YELLOW}${WRITER_CAP:.0f} cap{RESET}")
print(f"  {DIM}Each sub-agent holds a scoped delegation token — caps enforced independently{RESET}")
print()
print(f"  {YELLOW}Researcher goes rogue (tight search API loop)...{RESET}")
print()

spent = 0.0
for call in range(1, 1000):
    auth = figuard.authorize(
        session_token=researcher_token.session_token,
        agent_id="researcher",
        action_type="TOOL_CALL",
        description=f"Search API call {call}",
        requested_quantity=SEARCH_CALL_COST,
        claimed_category="search_api",
        idempotency_key=f"search-{call}",
    )
    if not auth.is_authorized:
        print(f"  {BOLD}{RED}call {call:>4d}: ✗ {auth.denial_reason} — researcher stopped here  ◄◄◄{RESET}")
        print()
        print(f"  {DIM}Researcher burned ${spent:.2f} of its ${RESEARCHER_CAP:.2f} cap — fleet still has "
              f"${FLEET_BUDGET - spent:.2f} for others{RESET}")
        break

    spent += SEARCH_CALL_COST
    if call in (1, 2) or call % 10 == 0:
        bar = "█" * int(spent / RESEARCHER_CAP * 20)
        pct = int(spent / RESEARCHER_CAP * 100)
        print(f"  {GREEN}call {call:>4d}: ✓ AUTHORIZED  ${spent:.2f} of ${RESEARCHER_CAP:.2f}  "
              f"[{bar:<20}] {pct}%{RESET}")

print()

analyst_auth = figuard.authorize(
    session_token=analyst_token.session_token,
    agent_id="analyst",
    action_type="LLM_CALL",
    description="Analyze findings",
    requested_quantity=50.00,
    claimed_category="llm_calls",
    idempotency_key="analyst-001",
)
if analyst_auth.is_authorized:
    print(f"  {GREEN}Analyst:  ✓ AUTHORIZED   $50.00  (fleet has budget — researcher cap isolated){RESET}")
else:
    print(f"  {RED}Analyst:  ✗ {analyst_auth.denial_reason}{RESET}")

writer_auth = figuard.authorize(
    session_token=writer_token.session_token,
    agent_id="writer",
    action_type="LLM_CALL",
    description="Write report",
    requested_quantity=40.00,
    claimed_category="llm_calls",
    idempotency_key="writer-001",
)
if writer_auth.is_authorized:
    print(f"  {GREEN}Writer:   ✓ AUTHORIZED   $40.00  (fleet has budget — researcher cap isolated){RESET}")
else:
    print(f"  {RED}Writer:   ✗ {writer_auth.denial_reason}{RESET}")

print()
print(f"  {GREEN}✓  Rogue researcher capped at ${RESEARCHER_CAP:.0f} — rest of fleet unaffected{RESET}")
