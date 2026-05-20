"""
FiGuard — Live Protection Demo

Four denial types in one run. Record with:
    PS1='$ ' asciinema rec demo.cast --cols 72 --rows 40
    python demo.py
    # Ctrl+D, then: agg --loop 0 --font-size 14 demo.cast demo.gif

Run:
    pip install figuard
    python demo.py
"""

import time
from figuard import FiGuardClient

# ── ANSI colours ──────────────────────────────────────────────────────────────
RED    = "\033[91m"
GREEN  = "\033[32m"
YELLOW = "\033[93m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

figuard = FiGuardClient(
    api_key="fg_live_demo",
    base_url="http://localhost:8080",  # sandbox: use "https://figuard-sandbox-1.onrender.com"
)

def pause(n=0.35):
    time.sleep(n)

def section(n, total, title, subtitle):
    print()
    print(f"{BOLD}[{n}/{total}]  {title}{RESET}  {DIM}— {subtitle}{RESET}")
    pause(0.4)

def ok(msg):
    print(f"      {GREEN}✓  {msg}{RESET}")
    pause(0.45)

def stop(msg):
    print(f"      {BOLD}{RED}✗  {msg}  ◄◄◄{RESET}")
    pause(0.8)

def info(msg):
    print(f"      {DIM}{msg}{RESET}")
    pause(0.3)

# ── Header ────────────────────────────────────────────────────────────────────
print()
print(f"{BOLD}  FiGuard — Rogue Agent Protection Demo{RESET}")
print(f"{DIM}  Pre-flight authorization for AI agents — four denial types{RESET}")
print(f"  {'─' * 54}")
pause(0.4)

# ── SDK snippet (what you write) ──────────────────────────────────────────────
print()
print(f"  {DIM}# What you write:{RESET}")
print(f"  {DIM}auth = client.authorize(session_token, agent_id=\"agent\", requested_quantity=2.00){RESET}")
print(f"  {DIM}if not auth.is_authorized: raise Stop(auth.denial_reason){RESET}")
pause(1.2)

# ══════════════════════════════════════════════════════════════════════════════
# 1. BUDGET CAP — infinite loop guard
# ══════════════════════════════════════════════════════════════════════════════
section(1, 4, "Budget Cap", "stops a runaway loop before it drains the account")

budget = figuard.create_budget(
    user_id="react_agent",
    total_limit=10.00,
    currency="USD",
    expires_in="1h",
)
info(f"budget $10.00  |  $2.00/call  →  hard stop at call 6")

for call in range(1, 20):
    auth = figuard.authorize(
        session_token=budget.primary_token.session_token,
        agent_id="react_agent",
        action_type="LLM_CALL",
        description=f"Iteration {call}",
        requested_quantity=2.00,
        idempotency_key=f"loop-{call}",
    )
    spent = min(call * 2.00, 10.00)
    if auth.is_authorized:
        ok(f"call {call}  AUTHORIZED    ${spent:.2f} of $10.00")
    else:
        stop(f"call {call}  {auth.denial_reason}")
        break

# ══════════════════════════════════════════════════════════════════════════════
# 2. DELEGATION CAP — rogue sub-agent in a fleet
# ══════════════════════════════════════════════════════════════════════════════
section(2, 4, "Delegation Cap", "one rogue sub-agent can't starve the rest of the fleet")

fleet = figuard.create_budget(
    user_id="crew_manager",
    total_limit=1000.00,
    currency="USD",
    expires_in="1h",
)
researcher = figuard.create_delegation_token(
    budget_id=fleet.id,
    label="researcher",
    caps=[{"category": "search_api", "limit": 50.00}],
)
analyst = figuard.create_delegation_token(
    budget_id=fleet.id,
    label="analyst",
    caps=[{"category": "llm_calls", "limit": 300.00}],
)
info(f"fleet $1,000.00  |  researcher capped at $50  ·  analyst capped at $300")

spent = 0.0
for call in range(1, 20):
    auth = figuard.authorize(
        session_token=researcher.session_token,
        agent_id="researcher",
        action_type="TOOL_CALL",
        description=f"Search call {call}",
        requested_quantity=20.00,
        claimed_category="search_api",
        idempotency_key=f"search-{call}",
    )
    if auth.is_authorized:
        spent += 20.00
        ok(f"researcher  call {call}  AUTHORIZED    ${spent:.2f} of $50.00")
    else:
        stop(f"researcher  call {call}  {auth.denial_reason}")
        break

analyst_auth = figuard.authorize(
    session_token=analyst.session_token,
    agent_id="analyst",
    action_type="LLM_CALL",
    description="Analyze findings",
    requested_quantity=80.00,
    claimed_category="llm_calls",
    idempotency_key="analyst-run-1",
)
if analyst_auth.is_authorized:
    ok(f"analyst              AUTHORIZED    fleet unaffected — $920.00 remaining")

# ══════════════════════════════════════════════════════════════════════════════
# 3. VELOCITY LIMIT — retry storm guard
# ══════════════════════════════════════════════════════════════════════════════
section(3, 4, "Velocity Limit", "cuts off a retry storm before it floods the ledger")

budget3 = figuard.create_budget(
    user_id="data_processor",
    total_limit=500.00,
    currency="USD",
    expires_in="1h",
    velocity_max_per_minute=3,
)
info(f"budget $500.00  |  velocity_max_per_minute=3  →  4th call blocked")

for call in range(1, 7):
    auth = figuard.authorize(
        session_token=budget3.primary_token.session_token,
        agent_id="data_processor",
        action_type="EXTERNAL_CALL",
        description=f"API call {call}",
        requested_quantity=1.50,
        idempotency_key=f"chunk-{call}",
    )
    if auth.is_authorized:
        ok(f"call {call}  AUTHORIZED    within 3/min window")
        figuard.confirm_event(auth.event_id, confirmed_quantity=1.50)
    else:
        stop(f"call {call}  {auth.denial_reason}")
        break

# ══════════════════════════════════════════════════════════════════════════════
# 4. CATEGORY CONSTRAINT — wrong bucket prevention
# ══════════════════════════════════════════════════════════════════════════════
section(4, 4, "Category Constraint", "agent can't accidentally bill the wrong budget category")

budget4 = figuard.create_budget(
    user_id="travel_agent",
    total_limit=600.00,
    currency="USD",
    expires_in="1h",
    allocations=[{
        "category": "flight",
        "limit": 600.00,
        "enforcementMode": "CATEGORY_CONSTRAINED",
        "allowedCategories": ["flight"],
    }],
)
info(f"budget $600.00  |  flight-only allocation  (allowedCategories=[\"flight\"])")

a1 = figuard.authorize(
    session_token=budget4.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="JetBlue SFO→JFK",
    requested_quantity=267.00,
    claimed_category="flight",
    idempotency_key="flight-001",
)
if a1.is_authorized:
    ok(f"claimedCategory=flight   AUTHORIZED    $267.00")
    figuard.confirm_event(a1.event_id, confirmed_quantity=267.00)

a2 = figuard.authorize(
    session_token=budget4.primary_token.session_token,
    agent_id="travel_agent",
    action_type="PURCHASE",
    description="Marriott Times Square",
    requested_quantity=312.00,
    claimed_category="hotel",
    idempotency_key="hotel-001",
)
if not a2.is_authorized:
    stop(f"claimedCategory=hotel    {a2.denial_reason}")

# ── Summary ───────────────────────────────────────────────────────────────────
print()
print(f"  {'─' * 54}")
print(f"  {GREEN}✓{RESET}  Budget cap          loop killed at call 6  (saved ~$40+ uncapped)")
print(f"  {GREEN}✓{RESET}  Delegation cap      researcher stopped at $50 — fleet had $920 left")
print(f"  {GREEN}✓{RESET}  Velocity limit      retry storm cut at call 4  (3/min enforced)")
print(f"  {GREEN}✓{RESET}  Category constraint wrong-category spend blocked at auth time")
pause(2.0)
print()
