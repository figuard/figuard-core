"""
Scenario 1 — The Infinite Loop

THE INCIDENT
A ReAct agent was scoring search results in a loop. The "stop when score > 0.95"
condition was never met (hallucinated threshold). Without a budget it would run
forever, calling the LLM on every iteration. A human noticed the bill hours later.

THE FIX
A hard spend limit on the budget. FiGuard stops the loop at iteration 251 with
INSUFFICIENT_FUNDS. The agent gets a clear, machine-readable signal to stop.

Run:
    pip install figuard
    python scenario_1_infinite_loop.py
"""

import random
from figuard import FiGuardClient

# ── ANSI colours ──────────────────────────────────────────────────────────────
RED    = "\033[91m"
GREEN  = "\033[32m"
YELLOW = "\033[93m"
CYAN   = "\033[96m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

figuard = FiGuardClient(
    api_key="fg_live_demo",            # sandbox: use "sb_live_demo"
    base_url="http://localhost:8080",  # sandbox: use "https://figuard-sandbox-g1ha.onrender.com"
)

CALL_COST    = 0.02
BUDGET_LIMIT = 5.00

# ── WITHOUT FIGUARD ───────────────────────────────────────────────────────────
print(f"\n{BOLD}{RED}━━━  WITHOUT FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
print(f"  Agent loops forever — no budget, no stop signal.")
print()
uncapped_iters = int(BUDGET_LIMIT / CALL_COST) + random.randint(400, 600)
uncapped_cost  = round(uncapped_iters * CALL_COST, 2)
for i in [1, 50, 100, 200, uncapped_iters]:
    cost_so_far = round(i * CALL_COST, 2)
    print(f"  {RED}iter {i:>4d}: LLM call fired — ${cost_so_far:.2f} accumulated{RESET}")
print()
print(f"  {RED}✗  Ran {uncapped_iters} iterations — ${uncapped_cost:.2f} charged before a human killed it{RESET}")

# ── WITH FIGUARD ──────────────────────────────────────────────────────────────
print(f"\n{BOLD}{GREEN}━━━  WITH FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")

budget = figuard.create_budget(
    user_id="react_agent",
    total_limit=BUDGET_LIMIT,
    currency="USD",
    expires_in="1h",
)

cap_iters = int(BUDGET_LIMIT / CALL_COST)
print(f"  Budget: {BOLD}${budget.total_limit:.2f}{RESET}  (hard cap — {cap_iters} iterations at ${CALL_COST}/call)")
print()

spent = 0.0
for iteration in range(1, 1000):
    auth = figuard.authorize(
        session_token=budget.primary_token.session_token,
        agent_id="react_agent",
        action_type="LLM_CALL",
        description=f"Score search results — iteration {iteration}",
        requested_quantity=CALL_COST,
        idempotency_key=f"iter-{iteration}",
    )

    if not auth.is_authorized:
        print(f"  {BOLD}{RED}iter {iteration:>4d}: ✗ {auth.denial_reason} — loop terminated  ◄◄◄{RESET}")
        print()
        saved = round(uncapped_cost - spent, 2)
        print(f"  {GREEN}✓  Stopped at iteration {iteration} — ${spent:.2f} of ${BUDGET_LIMIT:.2f} spent{RESET}")
        print(f"  {GREEN}✓  Saved ${saved:.2f} vs the uncapped run ({uncapped_iters} iterations){RESET}")
        break

    spent = round(spent + CALL_COST, 2)
    if iteration in (1, 50, 100, 200) or iteration == cap_iters:
        bar = "█" * int(spent / BUDGET_LIMIT * 20)
        print(f"  {GREEN}iter {iteration:>4d}: ✓ AUTHORIZED  ${spent:.2f} spent  [{bar:<20}]{RESET}")
