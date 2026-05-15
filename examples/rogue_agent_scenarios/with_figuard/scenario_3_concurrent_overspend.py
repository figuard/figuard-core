"""
Scenario 3 — The Concurrent Fleet Overspend (WITH FiGuard)

FiGuard uses SERIALIZABLE isolation on every authorize() write.
10 agents fire simultaneously. Each sees the same available balance,
but FiGuard's pessimistic write lock means exactly 5 are authorized
and 5 are denied. The budget is never exceeded.

Run against the sandbox — no local setup required:
    pip install figuard
    python scenario_3_concurrent_overspend.py
"""

import threading
from figuard import FiGuardClient

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
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
        session_token=budget.session_token,
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


print(f"Budget limit:  ${budget.total_limit:.2f}")
print(f"Agents:        10  (each requests $200.00)")
print(f"Total asked:   $2,000.00")
print()

threads = [threading.Thread(target=agent_spend, args=(i,)) for i in range(10)]
for t in threads:
    t.start()
for t in threads:
    t.join()

authorized = [r for r in results if r[1] == "AUTHORIZED"]
denied = [r for r in results if r[1] != "AUTHORIZED"]
total_authorized = len(authorized) * 200.00

print()
print(f"Authorized: {len(authorized)} agents  (${total_authorized:.2f})")
print(f"Denied:     {len(denied)} agents")
print(f"Budget used: ${total_authorized:.2f} of ${budget.total_limit:.2f}")
print(f"Never exceeded: {total_authorized <= budget.total_limit}")
