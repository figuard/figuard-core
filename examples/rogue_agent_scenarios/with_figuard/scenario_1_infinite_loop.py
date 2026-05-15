"""
Scenario 1 — The Infinite Quality Loop (WITH FiGuard)

A hard $5.00 ceiling on the task. At $0.02/call that allows 250 iterations.
When the budget is exhausted FiGuard returns a structured denial and the loop
stops cleanly — no exception, no crash.

Run against the sandbox — no local setup required:
    pip install figuard anthropic
    python scenario_1_infinite_loop.py
"""

import anthropic
from figuard import FiGuardClient

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
)
claude = anthropic.Anthropic()

# Hard ceiling on this task: $5.00
# At $0.02/call that is 250 iterations maximum
budget = figuard.create_budget(
    user_id="quality_checker",
    total_limit=5.00,
    currency="USD",
    expires_in="1h",
    authorization_expiry_seconds=300,
    intent_context="content quality check — single document",
)

cost_per_call = 0.02
max_iterations = int(budget.total_limit / cost_per_call)

print(f"Budget created: ${budget.total_limit:.2f} limit")
print(f"At ${cost_per_call}/call: maximum {max_iterations} iterations")
print()

iteration = 0
total_cost = 0.0

while True:
    iteration += 1

    # Ask permission before the API call
    auth = figuard.authorize(
        session_token=budget.session_token,
        agent_id="quality_checker",
        action_type="LLM_CALL",
        description=f"Claude quality evaluation iteration {iteration}",
        requested_quantity=cost_per_call,
        idempotency_key=f"quality-iter-{iteration}",
    )

    if not auth.is_authorized:
        print(f"\n✓ FiGuard stopped the loop at iteration {iteration}")
        print(f"  Denial reason:  {auth.denial_reason}")
        print(f"  Total spent:    ${total_cost:.2f}")
        print(f"  Budget limit:   ${budget.total_limit:.2f}")
        print(
            f"  Saved vs 847 iterations: "
            f"${(847 - iteration) * cost_per_call:.2f}"
        )
        break

    # Authorized — make the real call
    claude.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=100,
        messages=[{
            "role": "user",
            "content": "Rate quality 0-1: example content",
        }],
    )

    figuard.confirm_event(auth.event_id, confirmed_quantity=cost_per_call)
    total_cost += cost_per_call

    score = 0.85 + (iteration % 7) * 0.01  # oscillates, never reaches 0.95
    print(
        f"Iteration {iteration:4d}: score={score:.2f}  "
        f"spent=${total_cost:.2f} of ${budget.total_limit:.2f}"
    )
