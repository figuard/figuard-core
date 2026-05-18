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

figuard = FiGuardClient(
    api_key="ab_live_demo",  # sandbox: use "sb_live_demo"
    base_url="http://localhost:8080",  # sandbox: use "https://figuard-sandbox-1.onrender.com"
)

budget = figuard.create_budget(
    user_id="supervisor",
    total_limit=1000.00,
    currency="USD",
    expires_in="1h",
)

results: list[tuple[int, str, str | None]] = []
lock = threading.Lock()


def agent_spend(agent_id: int) -> None:
    auth = figuard.authorize(
        session_token=budget.primary_token.session_token,
        agent_id=f"research_agent_{agent_id}",
        action_type="COMPUTE",
        description=f"Research subtask {agent_id}",
        requested_quantity=200.00,
        idempotency_key=f"research-task-{agent_id}",
    )
    with lock:
        results.append((agent_id, auth.decision,
                        auth.denial_reason if not auth.is_authorized else None))
        status = "✓ AUTHORIZED" if auth.is_authorized else "✗ DENIED    "
        reason = f"[{auth.denial_reason}]" if not auth.is_authorized else ""
        print(f"Agent {agent_id:2d}: {status}  $200.00  {reason}")


print(f"Budget: ${budget.total_limit:.2f}  |  10 agents × $200 = $2,000 requested")
print()

threads = [threading.Thread(target=agent_spend, args=(i,)) for i in range(10)]
for t in threads:
    t.start()
for t in threads:
    t.join()

authorized = [r for r in results if r[1] == "AUTHORIZED"]
total = len(authorized) * 200.0

print()
print(f"✓ Authorized: {len(authorized)}/10 agents  (${total:.2f} of ${budget.total_limit:.2f})")
print(f"  Budget never exceeded: {total <= budget.total_limit}")
