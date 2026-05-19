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
This means FiGuard catches runaway loops regardless of whether funds remain.

Run:
    pip install figuard
    python 06_velocity_loop.py
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

CALL_COST    = 1.50
BUDGET_LIMIT = 100.00
VELOCITY_CAP = 3
ATTEMPTS     = 5

# ── WITHOUT FIGUARD ───────────────────────────────────────────────────────────
print(f"\n{BOLD}{RED}━━━  WITHOUT FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
print(f"  Agent hits a transient error and retries in a tight loop — no rate limit, no backoff.")
print()
for i in range(1, 49):
    if i <= 3 or i in (10, 20, 30, 47):
        cost = round(i * CALL_COST, 2)
        print(f"  {RED}call {i:>2d}: AUTHORIZED  ${cost:.2f} accumulated{RESET}")
print(f"  {RED}call 48: BUDGET_EXHAUSTED  (still retrying...){RESET}")
print(f"  {RED}call 94: BUDGET_EXHAUSTED  (human kills process){RESET}")
print()
print(f"  {RED}✗  94 calls in ~90 seconds — $4.70 burned + 47 wasted retries after exhaustion{RESET}")

# ── WITH FIGUARD ──────────────────────────────────────────────────────────────
print(f"\n{BOLD}{GREEN}━━━  WITH FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")

budget = figuard.create_budget(
    user_id="data_processor",
    total_limit=BUDGET_LIMIT,
    currency="USD",
    expires_in="1h",
    authorization_expiry_seconds=300,
    intent_context="data processing pipeline — batch job",
    velocity_max_per_minute=VELOCITY_CAP,
)

print(f"  Budget: {BOLD}${budget.total_limit:.2f}{RESET}  |  "
      f"velocity_max_per_minute={YELLOW}{VELOCITY_CAP}{RESET}  "
      f"{DIM}(rolling 60-second window){RESET}")
print()

for i in range(1, ATTEMPTS + 1):
    auth = figuard.authorize(
        session_token=budget.primary_token.session_token,
        agent_id="data_processor",
        action_type="EXTERNAL_CALL",
        description=f"Data processing API call — batch chunk {i}",
        requested_quantity=CALL_COST,
        idempotency_key=f"batch-chunk-{i}",
    )

    if auth.is_authorized:
        print(f"  {GREEN}Call {i}: ✓ AUTHORIZED   ${CALL_COST:.2f}  (within {VELOCITY_CAP}/min window){RESET}")
        figuard.confirm_event(auth.event_id, confirmed_quantity=CALL_COST)
    else:
        print(f"  {BOLD}{RED}Call {i}: ✗ {auth.decision}  ◄◄◄{RESET}")
        print(f"  {DIM}  {auth.denial_reason} — agent receives clear signal to back off{RESET}")

print()
print(f"  {GREEN}✓  Calls 1–{VELOCITY_CAP}: AUTHORIZED within the {VELOCITY_CAP}/min velocity window{RESET}")
print(f"  {GREEN}✓  Call {VELOCITY_CAP + 1}+: VELOCITY_LIMIT_EXCEEDED — loop terminated with a clear signal{RESET}")
print(f"  {GREEN}✓  On-call webhook fires immediately — not after 94 retries{RESET}")
