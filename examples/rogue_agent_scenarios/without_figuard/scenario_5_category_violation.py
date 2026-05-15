"""
Scenario 5 — The Category Violation (WITHOUT FiGuard)

The problem: a travel booking agent has separate limits for flights and hotels.
There is no enforcement — the agent charges a hotel booking against the flight
allocation. The flight budget is silently depleted for non-flight spend.
The discrepancy only appeared in the next month's finance review.

Without category enforcement any spend is accepted against any allocation.
"""


class SimpleBudget:
    """A naive budget with named allocations but no enforcement."""

    def __init__(self, allocations: dict[str, float]) -> None:
        self.allocations = dict(allocations)
        self.spent: dict[str, float] = {k: 0.0 for k in allocations}

    def authorize(self, amount: float, claimed_category: str) -> tuple[bool, str]:
        # No enforcement — accepts any category regardless of allocation name
        total_available = sum(
            self.allocations[k] - self.spent[k]
            for k in self.allocations
        )
        if total_available >= amount:
            # Charges to claimed_category even if it's wrong
            if claimed_category in self.allocations:
                self.spent[claimed_category] += amount
            else:
                # Falls back to first allocation — no error raised
                first = next(iter(self.allocations))
                self.spent[first] += amount
            return True, "AUTHORIZED"
        return False, "INSUFFICIENT_FUNDS"


budget = SimpleBudget({"flight": 600.00, "hotel": 400.00})

# Correct: flight agent books a flight
ok, decision = budget.authorize(267.00, claimed_category="flight")
print(f"Flight booking:       {decision} — $267.00  (correct)")

# Wrong: agent charges hotel to flight allocation — silently accepted
ok, decision = budget.authorize(312.00, claimed_category="flight")
print(f"Hotel → flight alloc: {decision} — $312.00  (WRONG — no enforcement)")
print()

print("Allocation state after both charges:")
for cat, limit in budget.allocations.items():
    used = budget.spent[cat]
    print(f"  {cat:8s}: ${used:.2f} used of ${limit:.2f}  "
          + ("← hotel charged here" if cat == "flight" and used > 267 else ""))

print()
print("Finance review (next month):")
print("  Flight allocation shows $579.00 spent.")
print("  Only one flight was booked ($267.00).")
print("  $312.00 of hotel spend silently charged to flight budget.")
print()
print("Root cause: allocations track spend by name but don't enforce category.")
