"""
Scenario 3 — The Concurrent Fleet Overspend (WITHOUT FiGuard)

The problem: a LangGraph supervisor spawns 10 research sub-agents simultaneously.
Each reads the same $1,000 available balance. All 10 see enough funds.
All 10 get approved. Total spend: $2,000.

Classic read-modify-write race condition on a shared balance.
"""

import threading
import time

# Shared budget state — no locking, no isolation
budget = {"available": 1000.00, "spent": 0.0}
lock = threading.Lock()


def agent_spend(agent_id: int, amount: float) -> None:
    # All 10 agents read the balance before any write lands
    available = budget["available"]

    if available >= amount:
        time.sleep(0.001)  # simulate processing delay — makes the race worse

        # By the time we write, others have already written
        budget["available"] -= amount
        budget["spent"] += amount

        with lock:
            print(
                f"Agent {agent_id:2d}: APPROVED  ${amount:.2f}  "
                f"(balance now: ${budget['available']:.2f})"
            )
    else:
        with lock:
            print(f"Agent {agent_id:2d}: REJECTED  ${amount:.2f}")


amount_per_agent = 200.00
num_agents = 10

print(f"Budget limit:  $1,000.00")
print(f"Agents:        {num_agents}")
print(f"Each requests: ${amount_per_agent:.2f}")
print(f"Total asked:   ${num_agents * amount_per_agent:.2f}")
print()

threads = [
    threading.Thread(target=agent_spend, args=(i, amount_per_agent))
    for i in range(num_agents)
]
for t in threads:
    t.start()
for t in threads:
    t.join()

print()
print(f"Total spent:   ${budget['spent']:.2f}")
print(f"Budget limit:  $1,000.00")
overspend = max(0, budget["spent"] - 1000.0)
print(f"Overspend:     ${overspend:.2f}")
print()
if overspend > 0:
    print(f"Budget exceeded by ${overspend:.2f}.")
    print("Root cause: no serializable isolation — all reads happened before any writes.")
