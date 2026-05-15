"""
Scenario 5 — The Category Violation

THE INCIDENT
A travel booking agent was authorized for flights ($600) and hotels ($400).
The agent tried to book a hotel but mistakenly charged it to the flight allocation.
Without enforcement it went through silently. The flight budget was depleted
for non-flight spend. Finance caught it in the month-end review.

THE FIX
CATEGORY_CONSTRAINED allocations. FiGuard denies any spend whose claimed_category
doesn't match the allocation's allowed_categories. The flight budget can only
be used for flight spend, hotel budget for hotel spend — enforced at auth time.

Run:
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

print(f"Budget: ${budget.total_limit:.2f}  |  flight $600  hotel $400")
print()

# Correct: flight booked against flight allocation
auth1 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK",
    requested_quantity=267.00,
    claimed_category="flight",
    idempotency_key="booking-flight-001",
)
print(f"Flight → flight:  {auth1.decision} — $267.00")
if auth1.is_authorized:
    figuard.confirm_event(auth1.event_id, confirmed_quantity=267.00)

# Wrong: hotel charged to flight allocation
auth2 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square",
    requested_quantity=312.00,
    claimed_category="flight",   # wrong category
    idempotency_key="booking-hotel-wrong",
)
print(f"Hotel  → flight:  {auth2.decision}"
      + (f" — {auth2.denial_reason}" if not auth2.is_authorized else " — $312.00"))

# Correct: hotel charged to hotel allocation
auth3 = figuard.authorize(
    session_token=budget.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square",
    requested_quantity=312.00,
    claimed_category="hotel",    # correct
    idempotency_key="booking-hotel-001",
)
print(f"Hotel  → hotel:   {auth3.decision} — $312.00")
if auth3.is_authorized:
    figuard.confirm_event(auth3.event_id, confirmed_quantity=312.00)

print()
print("✓ Flight allocation: $267.00 (flights only)")
print("  Hotel allocation:  $312.00 (hotels only)")
print("  Category contamination blocked at authorization time.")
