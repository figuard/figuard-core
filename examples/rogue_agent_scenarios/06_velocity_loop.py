"""
Scenario 6 — The Velocity Loop

THE INCIDENT
A data-processing agent entered a retry loop after hitting a transient API error.
The retry logic had no backoff and no loop guard. Within 60 seconds it had fired
47 authorize calls, exhausted the budget, and continued retrying against the
exhausted budget — generating 47 more BUDGET_EXHAUSTED responses before a human
noticed the alert flood and killed the process.

Total calls: 94 in ~90 seconds. Cost (at $0.05/call): $4.70 before budget ran out,
plus 47 wasted round-trips after. No circuit breaker. No alert before the flood.

THE FIX
velocity_max_per_minute=3 on the budget. FiGuard returns VELOCITY_LIMIT_EXCEEDED
after the 3rd call in any 60-second rolling window. The agent gets a clear signal
to back off — no guesswork about whether it's a spend limit or a rate limit.

KEY INSIGHT
Velocity controls count ALL authorize attempts, not just approved ones. Even after
the budget is exhausted, a retrying agent keeps incrementing the velocity counter.
This means FiGuard catches runaway loops regardless of whether funds remain —
there is no way for an agent to "escape" velocity enforcement by burning through
the budget first.

Run:
    pip install figuard
    python 06_velocity_loop.py
"""

from figuard import FiGuardClient

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://sandbox.figuard.io",
)

budget = figuard.create_budget(
    user_id="data_processor",
    total_limit=100.00,
    currency="USD",
    expires_in="1h",
    authorization_expiry_seconds=300,
    intent_context="data processing pipeline — batch job",
    velocity_max_per_minute=3,   # at most 3 authorize calls per 60-second rolling window
)

print(f"Budget: ${budget.total_limit:.2f}  |  velocity_max_per_minute=3")
print()

call_cost = 1.50
attempts = 5   # agent fires 5 rapid calls — first 3 succeed, 4th and 5th are denied

for i in range(1, attempts + 1):
    auth = figuard.authorize(
        session_token=budget.primary_token.session_token,
        agent_id="data_processor",
        action_type="EXTERNAL_CALL",
        description=f"Data processing API call — batch chunk {i}",
        requested_quantity=call_cost,
        idempotency_key=f"batch-chunk-{i}",
    )

    status = auth.decision
    if auth.is_authorized:
        print(f"Call {i}: {status} — ${call_cost:.2f}")
        figuard.confirm_event(auth.event_id, confirmed_quantity=call_cost)
    else:
        print(f"Call {i}: {status} — {auth.denial_reason}")

print()
print("Calls 1-3:  AUTHORIZED — within the 3/min velocity window")
print("Call 4:     VELOCITY_LIMIT_EXCEEDED — window exhausted, webhook fired")
print("Call 5:     VELOCITY_LIMIT_EXCEEDED — silent denial (no new ledger entry)")
print()
print("Without velocity controls the agent would have retried indefinitely.")
print("With velocity_max_per_minute=3, FiGuard stops the loop after the 3rd call")
print("and fires a VELOCITY_LIMIT_EXCEEDED webhook so the on-call engineer is")
print("notified immediately — not after 94 failed calls.")
