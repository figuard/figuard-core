"""
Scenario 3 — The Concurrent Fleet Overspend

THE INCIDENT
A LangGraph supervisor spawned 10 research sub-agents simultaneously.
Each read the same $1,000 available balance. All 10 saw enough funds.
All 10 were approved. Total spend: $2,000. Budget exceeded by $1,000.

THE FIX
FiGuard uses SERIALIZABLE isolation on every authorize() write.
A pessimistic write lock on the budget row means each agent's read-modify-write
is atomic. Exactly 5 are authorized ($1,000), 5 are denied. Budget never exceeded.

Run:
    pip install figuard
    python scenario_3_concurrent_overspend.py
"""

import threading
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

BUDGET_LIMIT  = 1000.00
AGENTS        = 10
SPEND_EACH    = 200.00

# ── WITHOUT FIGUARD ───────────────────────────────────────────────────────────
print(f"\n{BOLD}{RED}━━━  WITHOUT FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
print(f"  10 agents launch simultaneously, each reads balance = ${BUDGET_LIMIT:,.2f}")
print()
for i in range(AGENTS):
    print(f"  {RED}Agent {i:2d}: reads ${BUDGET_LIMIT:,.2f} available → AUTHORIZED  ${SPEND_EACH:.2f}{RESET}")
print()
overspend = AGENTS * SPEND_EACH
print(f"  {RED}✗  Total committed: ${overspend:,.2f} against a ${BUDGET_LIMIT:,.2f} budget — ${overspend - BUDGET_LIMIT:,.2f} over{RESET}")

# ── WITH FIGUARD ──────────────────────────────────────────────────────────────
print(f"\n{BOLD}{GREEN}━━━  WITH FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")

budget = figuard.create_budget(
    user_id="supervisor",
    total_limit=BUDGET_LIMIT,
    currency="USD",
    expires_in="1h",
)

print(f"  Budget: {BOLD}${budget.total_limit:,.2f}{RESET}  |  {AGENTS} agents × ${SPEND_EACH:.0f} = ${AGENTS * SPEND_EACH:,.0f} requested")
print(f"  {DIM}SERIALIZABLE isolation — pessimistic write lock per authorize(){RESET}")
print()

results: list[tuple[int, str, str | None]] = []
lock = threading.Lock()


def agent_spend(agent_id: int) -> None:
    auth = figuard.authorize(
        session_token=budget.primary_token.session_token,
        agent_id=f"research_agent_{agent_id}",
        action_type="COMPUTE",
        description=f"Research subtask {agent_id}",
        requested_quantity=SPEND_EACH,
        idempotency_key=f"research-task-{agent_id}",
    )
    with lock:
        results.append((agent_id, auth.decision,
                        auth.denial_reason if not auth.is_authorized else None))
        if auth.is_authorized:
            print(f"  {GREEN}Agent {agent_id:2d}: ✓ AUTHORIZED   ${SPEND_EACH:.2f}{RESET}")
        else:
            print(f"  {RED}Agent {agent_id:2d}: ✗ DENIED   ${SPEND_EACH:.2f}  [{auth.denial_reason}]{RESET}")


threads = [threading.Thread(target=agent_spend, args=(i,)) for i in range(AGENTS)]
for t in threads:
    t.start()
for t in threads:
    t.join()

authorized = [r for r in results if r[1] == "AUTHORIZED"]
total = len(authorized) * SPEND_EACH

print()
print(f"  {GREEN}✓  {len(authorized)}/{AGENTS} agents authorized — ${total:,.2f} of ${BUDGET_LIMIT:,.2f}{RESET}")
print(f"  {GREEN}✓  Budget never exceeded: {total <= BUDGET_LIMIT}{RESET}")
