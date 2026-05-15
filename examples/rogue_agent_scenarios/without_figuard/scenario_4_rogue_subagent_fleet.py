"""
Scenario 4 — The Rogue Sub-Agent In A Fleet (WITHOUT FiGuard)

The problem: a research fleet has a shared budget and a simple per-agent
spend tracker. One sub-agent hallucinates a tool parameter and calls a
search API in a tight loop. It exhausts the shared pool. The analyst and
writer agents can't complete their tasks.

Without scoped delegation tokens there is no per-agent ceiling —
one bad actor takes the whole fleet down.
"""

import threading

# Shared fleet budget — one pool, no per-agent caps
fleet_budget = {"available": 1000.00, "spent": 0.0}
per_agent_spent: dict[str, float] = {
    "researcher": 0.0,
    "analyst":    0.0,
    "writer":     0.0,
}
lock = threading.Lock()


def spend(agent_id: str, amount: float, description: str) -> bool:
    """Returns True if approved, False if budget exhausted."""
    with lock:
        if fleet_budget["available"] >= amount:
            fleet_budget["available"] -= amount
            fleet_budget["spent"] += amount
            per_agent_spent[agent_id] += amount
            return True
        return False


print("Fleet budget: $1,000.00  (shared, no per-agent caps)")
print()

# Researcher goes rogue — loops on a search API call
print("Researcher goes rogue...")
call = 0
while True:
    call += 1
    approved = spend("researcher", 5.00, f"Search API call {call}")
    if not approved:
        print(
            f"  Researcher stopped at call {call} — fleet budget exhausted\n"
            f"  Researcher spent: ${per_agent_spent['researcher']:.2f}"
        )
        break
    if call % 40 == 0:
        print(
            f"  Researcher call {call:3d}: approved  "
            f"(fleet remaining: ${fleet_budget['available']:.2f})"
        )

print()
print("Other agents try to run...")
print()

analyst_ok = spend("analyst", 50.00, "Analyze findings")
print(
    f"Analyst:  {'APPROVED' if analyst_ok else 'DENIED — no budget left'}  $50.00"
)

writer_ok = spend("writer", 40.00, "Write report")
print(
    f"Writer:   {'APPROVED' if writer_ok else 'DENIED — no budget left'}  $40.00"
)

print()
print("Summary:")
for agent, spent_amt in per_agent_spent.items():
    print(f"  {agent:12s}: ${spent_amt:.2f}")
print(f"  {'fleet total':12s}: ${fleet_budget['spent']:.2f} of $1,000.00")
print()
print("Fleet failed: researcher consumed the entire budget.")
print("Root cause: no per-agent spend caps.")
