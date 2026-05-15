"""
Scenario 5 — The Category Violation (WITH FiGuard)

Budget allocations with CATEGORY_CONSTRAINED enforcement.
The travel agent tries to charge a hotel booking against the flight allocation.
FiGuard denies it with NO_MATCHING_ALLOCATION. The correct category is accepted.
Flight budget is never silently depleted for non-flight spend.

Run against the sandbox — no local setup required:
    pip install figuard
    python scenario_5_category_violation.py
"""

from figuard import FiGuardClient

figuard = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
)

budget = figuard.create_budget(
    user_id="travel_agent",
    total_limit=1000.00,
    currency="USD",
    expires_in="1h",
    allocations=[
        {
            "category": "flight",
            "limit": 600.00,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["flight"],
        },
        {
            "category": "hotel",
            "limit": 400.00,
            "enforcement_mode": "CATEGORY_CONSTRAINED",
            "allowed_categories": ["hotel"],
        },
    ],
)

print(f"Budget: ${budget.total_limit:.2f}  (flight: $600  hotel: $400)")
print()

# Correct: flight agent books a flight against the flight allocation
auth1 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK",
    requested_quantity=267.00,
    claimed_category="flight",
    idempotency_key="booking-flight-001",
)
print(f"Flight → flight alloc:  {auth1.decision} — $267.00")
if auth1.is_authorized:
    figuard.confirm_event(auth1.event_id, confirmed_quantity=267.00)

# Wrong: agent tries to charge a hotel against the flight allocation
auth2 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square",
    requested_quantity=312.00,
    claimed_category="flight",  # wrong — hotel spend against flight allocation
    idempotency_key="booking-hotel-wrong-001",
)
print(
    f"Hotel → flight alloc:   {auth2.decision}"
    + (f" — {auth2.denial_reason}" if not auth2.is_authorized else " — $312.00")
)

# Correct: hotel charged to the hotel allocation
auth3 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square",
    requested_quantity=312.00,
    claimed_category="hotel",  # correct
    idempotency_key="booking-hotel-001",
)
print(f"Hotel → hotel alloc:    {auth3.decision} — $312.00")
if auth3.is_authorized:
    figuard.confirm_event(auth3.event_id, confirmed_quantity=312.00)

print()
print("Flight allocation spent: $267.00  (flights only — never contaminated)")
print("Hotel allocation spent:  $312.00")
print()
print("Category violation blocked at authorization time.")
print("Finance reconciliation matches bookings exactly.")
