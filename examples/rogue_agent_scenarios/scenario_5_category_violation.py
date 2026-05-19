"""
Scenario 5 — The Category Violation

THE INCIDENT
A travel booking agent had a flight-only budget ($600, category=flight).
The agent then tried to pay for a hotel using the same session token.
The hotel booking used the wrong category — it slipped through and depleted
the flight budget for non-flight spend. Finance caught it at month-end.

THE FIX
CATEGORY_CONSTRAINED allocations. FiGuard denies any spend whose
claimedCategory is not in the allocation's allowedCategories list.
The flight allocation only accepts claimedCategory="flight".
Any other category — including "hotel" — returns NO_MATCHING_ALLOCATION.

Run:
    pip install figuard
    python scenario_5_category_violation.py
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

FLIGHT_LIMIT = 600.00

# ── WITHOUT FIGUARD ───────────────────────────────────────────────────────────
print(f"\n{BOLD}{RED}━━━  WITHOUT FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
print(f"  Flight budget: ${FLIGHT_LIMIT:.2f}  |  no category enforcement — one shared pool")
print()
print(f"  {GREEN}Flight (JetBlue SFO→JFK):    AUTHORIZED  $267.00{RESET}  ← correct")
print(f"  {RED}Hotel  (Marriott Times Sq):   AUTHORIZED  $312.00{RESET}  ← slips through!")
print()
print(f"  {RED}✗  Flight allocation contaminated — $312.00 in hotel spend charged to flights{RESET}")
print(f"  {DIM}  Finance caught the mischarge at month-end reconciliation{RESET}")

# ── WITH FIGUARD ──────────────────────────────────────────────────────────────
print(f"\n{BOLD}{GREEN}━━━  WITH FIGUARD  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")

budget = figuard.create_budget(
    user_id="travel_agent",
    total_limit=FLIGHT_LIMIT,
    currency="USD",
    expires_in="1h",
    allocations=[
        {
            "category": "flight",
            "limit": FLIGHT_LIMIT,
            "enforcementMode": "CATEGORY_CONSTRAINED",
            "allowedCategories": ["flight"],
        },
    ],
)

print(f"  Budget: {BOLD}${budget.total_limit:.2f}{RESET}  |  flight-only allocation  "
      f"{DIM}(allowedCategories=[\"flight\"]){RESET}")
print()

# Correct: flight booking
auth1 = figuard.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK",
    requested_quantity=267.00,
    claimed_category="flight",
    idempotency_key="booking-flight-001",
)
if auth1.is_authorized:
    print(f"  {GREEN}Flight  claimedCategory=flight:  ✓ AUTHORIZED   $267.00{RESET}")
    figuard.confirm_event(auth1.event_id, confirmed_quantity=267.00)
else:
    print(f"  {RED}Flight  claimedCategory=flight:  ✗ {auth1.denial_reason}{RESET}")

# Wrong: hotel spend against flight-only allocation
auth2 = figuard.authorize(
    session_token=budget.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square",
    requested_quantity=312.00,
    claimed_category="hotel",   # not in flight allocation's allowedCategories
    idempotency_key="booking-hotel-wrong",
)
if not auth2.is_authorized:
    print(f"  {BOLD}{RED}Hotel   claimedCategory=hotel:   ✗ {auth2.denial_reason} — blocked here  ◄◄◄{RESET}")
else:
    print(f"  {RED}Hotel   claimedCategory=hotel:   AUTHORIZED — $312.00  (unexpected){RESET}")

print()
print(f"  {GREEN}✓  Flight allocation: $267.00  (flights only — untouched by hotel spend){RESET}")
print(f"  {GREEN}✓  Hotel spend blocked at authorization time — no month-end surprise{RESET}")
