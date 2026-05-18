"""
Scenario 1 — The Infinite Quality Loop

THE INCIDENT
A quality-checking agent evaluated content in a loop until its score exceeded 0.95.
The score oscillated between 0.82 and 0.91 and never reached the threshold.
The agent ran 847 iterations overnight before someone noticed.
Cost: $16.94. Time: ~14 hours. No alert fired.

THE FIX
A $5.00 budget on the task. At $0.02/call that allows 250 iterations maximum.
When the budget is exhausted FiGuard returns BUDGET_EXHAUSTED and the loop
stops cleanly — no exception, no crash, full audit trail.

Run:
    pip install figuard
    python scenario_1_infinite_loop.py
"""

from figuard import FiGuardClient

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
)

cost_per_call = 0.02

budget = figuard.create_budget(
    user_id="quality_checker",
    total_limit=5.00,
    currency="USD",
    expires_in="1h",
    authorization_expiry_seconds=300,
    intent_context="content quality check — single document",
)

print(f"Budget: ${budget.total_limit:.2f}  "
      f"(max {int(budget.total_limit / cost_per_call)} iterations at ${cost_per_call}/call)")
print()

iteration = 0
total_cost = 0.0

while True:
    iteration += 1

    auth = figuard.authorize(
        session_token=budget.primary_token.session_token,
        agent_id="quality_checker",
        action_type="LLM_CALL",
        description=f"Claude quality evaluation iteration {iteration}",
        requested_quantity=cost_per_call,
        idempotency_key=f"quality-iter-{iteration}",
    )

    if not auth.is_authorized:
        print(f"\n✓ Stopped at iteration {iteration}: {auth.denial_reason}")
        print(f"  Spent: ${total_cost:.2f} of ${budget.total_limit:.2f}")
        print(f"  Saved vs 847 iterations: ${(847 - iteration) * cost_per_call:.2f}")
        break

    # Simulated LLM call — score oscillates 0.82–0.91, never reaches the 0.95 threshold
    figuard.confirm_event(auth.event_id, confirmed_quantity=cost_per_call)
    total_cost += cost_per_call

    score = 0.85 + (iteration % 7) * 0.01  # oscillates, never reaches 0.95
    print(f"Iteration {iteration:4d}: score={score:.2f}  "
          f"spent=${total_cost:.2f} of ${budget.total_limit:.2f}")
