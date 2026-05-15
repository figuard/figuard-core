"""
Scenario 4 — The Rogue Sub-Agent In A Fleet (WITH FiGuard)

Each sub-agent gets a scoped delegation token with its own spend cap.
The researcher hallucinates and loops — but it can only consume its own
$200 cap. The analyst and writer never lose access to their allocations.

Run against the sandbox — no local setup required:
    pip install figuard
    python scenario_4_rogue_subagent_fleet.py
"""

from figuard import FiGuardClient

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
)

# Fleet budget
fleet = figuard.create_budget(
    user_id="crew_manager",
    total_limit=1000.00,
    currency="USD",
    expires_in="2h",
)

# Scoped delegation tokens — each agent has its own hard cap
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

print(f"Fleet budget:   ${fleet.total_limit:.2f}")
print(f"Researcher cap: $200.00  (search_api)")
print(f"Analyst cap:    $300.00  (llm_calls)")
print(f"Writer cap:     $200.00  (llm_calls)")
print()
print("Simulating researcher going rogue (tight API loop)...")
print()

researcher_spent = 0.0
call = 0

while True:
    call += 1
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
        print(
            f"✓ Researcher stopped at call {call}: {auth.denial_reason}\n"
            f"  Researcher spent: ${researcher_spent:.2f} of $200.00 cap"
        )
        break

    researcher_spent += 5.00
    if call % 10 == 0 or call <= 3:
        print(
            f"  Researcher call {call:3d}: AUTHORIZED  "
            f"(${researcher_spent:.2f} of $200.00)"
        )

print()
print("Checking other agents are unaffected...")
print()

analyst_auth = figuard.authorize(
    session_token=analyst_token.session_token,
    agent_id="analyst",
    action_type="LLM_CALL",
    description="Analyze research findings",
    requested_quantity=50.00,
    claimed_category="llm_calls",
    idempotency_key="analyst-task-001",
)
print(f"Analyst:  {analyst_auth.decision} — $50.00")

writer_auth = figuard.authorize(
    session_token=writer_token.session_token,
    agent_id="writer",
    action_type="LLM_CALL",
    description="Write final report",
    requested_quantity=40.00,
    claimed_category="llm_calls",
    idempotency_key="writer-task-001",
)
print(f"Writer:   {writer_auth.decision} — $40.00")

print()
print("Fleet completed despite rogue researcher.")
print(f"Without delegation tokens: entire ${fleet.total_limit:.2f} fleet budget at risk.")
print("With delegation tokens: researcher capped at $200, fleet continues.")
