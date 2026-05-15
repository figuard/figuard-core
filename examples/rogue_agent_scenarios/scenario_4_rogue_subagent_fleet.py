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

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
)

fleet = figuard.create_budget(
    user_id="crew_manager",
    total_limit=1000.00,
    currency="USD",
    expires_in="2h",
)

researcher_token = figuard.create_delegation_token(
    budget_id=fleet.id,
    session_token=fleet.session_token,
    label="researcher",
    caps=[{"category": "search_api", "limit": 200.00}],
    expires_in="2h",
)
analyst_token = figuard.create_delegation_token(
    budget_id=fleet.id,
    session_token=fleet.session_token,
    label="analyst",
    caps=[{"category": "llm_calls", "limit": 300.00}],
    expires_in="2h",
)
writer_token = figuard.create_delegation_token(
    budget_id=fleet.id,
    session_token=fleet.session_token,
    label="writer",
    caps=[{"category": "llm_calls", "limit": 200.00}],
    expires_in="2h",
)

print(f"Fleet: ${fleet.total_limit:.2f}  |  researcher $200  analyst $300  writer $200")
print()
print("Researcher goes rogue (tight search API loop)...")

spent = 0.0
for call in range(1, 1000):
    auth = figuard.authorize(
        session_token=researcher_token.session_token,
        agent_id="researcher",
        action_type="TOOL_CALL",
        description=f"Search API call {call}",
        requested_quantity=5.00,
        claimed_category="search_api",
        idempotency_key=f"search-{call}",
    )
    if not auth.is_authorized:
        print(f"✓ Researcher stopped at call {call}: {auth.denial_reason}")
        print(f"  Researcher spent: ${spent:.2f} of $200.00 cap")
        break
    spent += 5.00
    if call % 10 == 0 or call <= 2:
        print(f"  call {call:3d}: AUTHORIZED  (${spent:.2f} of $200.00)")

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
print(f"Analyst: {analyst_auth.decision} — $50.00")

writer_auth = figuard.authorize(
    session_token=writer_token.session_token,
    agent_id="writer",
    action_type="LLM_CALL",
    description="Write report",
    requested_quantity=40.00,
    claimed_category="llm_calls",
    idempotency_key="writer-001",
)
print(f"Writer:  {writer_auth.decision} — $40.00")

print()
print("✓ Fleet completed. Rogue researcher capped at $200, rest of fleet unaffected.")
