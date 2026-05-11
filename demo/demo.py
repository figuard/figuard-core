"""
FiGuard Demo — three scenarios showing the full value proposition.

Scenario 1: Multi-agent travel booking
  An orchestrator agent coordinates a $500 travel budget. It tries
  a premium flight (denied), falls back to economy (authorized), then
  spawns specialist sub-agents: a hotel agent and a transport agent.
  The Spend Tree shows the orchestrator as root with sub-agent children.

Scenario 2: Customer support refund agent
  Agents are capped at $100 per customer refund.
  An $80 refund is approved. A $120 refund is denied — the agent
  must escalate to a human supervisor instead of issuing it directly.

Scenario 3: Multi-agent campaign orchestration
  An orchestrator plans a product launch campaign ($2,000 budget).
  It spawns a paid-ads sub-agent and a content sub-agent. The content
  sub-agent further spawns a vendor agent — demonstrating a 3-level
  causal chain in the Spend Tree.

Run against local Docker stack:
  ./mvnw clean package -DskipTests
  docker-compose up --build -d
  python demo/demo.py

Idempotent: re-running skips scenarios that already have live data.
Delete demo/.demo-state.json to force a full re-seed.
"""

import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone, timedelta

# ---------------------------------------------------------------------------
# Try to import the SDK; fall back to plain requests
# ---------------------------------------------------------------------------
try:
    sys.path.insert(0, "sdk/python")
    from figuard import FiGuardClient, FiGuardDeniedException
    _SDK_AVAILABLE = True
except ImportError:
    _SDK_AVAILABLE = False

import requests

# ---------------------------------------------------------------------------
# ANSI colours
# ---------------------------------------------------------------------------
_USE_COLOR = sys.stdout.isatty() and sys.platform != "win32"

def _c(code: str, text: str) -> str:
    return f"\033[{code}m{text}\033[0m" if _USE_COLOR else text

GREEN  = lambda t: _c("32", t)
RED    = lambda t: _c("31", t)
YELLOW = lambda t: _c("33", t)
CYAN   = lambda t: _c("36", t)
BOLD   = lambda t: _c("1",  t)
DIM    = lambda t: _c("2",  t)


# ---------------------------------------------------------------------------
# Idempotency state file
# ---------------------------------------------------------------------------
_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".demo-state.json")


def _load_state() -> dict:
    try:
        with open(_STATE_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_state(state: dict) -> None:
    with open(_STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)


def _budget_alive(raw: "_RawClient", budget_id: str) -> bool:
    """Return True if the budget still exists on the server."""
    try:
        result = raw.get(f"/api/v1/budgets/{budget_id}")
        return isinstance(result, dict) and "id" in result
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Thin HTTP wrapper
# ---------------------------------------------------------------------------
class _RawClient:
    def __init__(self, base_url: str, api_key: str):
        self.base = base_url.rstrip("/")
        self.headers = {"X-Agent-Budget-Key": api_key, "Content-Type": "application/json"}

    def post(self, path, json=None, extra_headers=None):
        h = {**self.headers, **(extra_headers or {})}
        r = requests.post(f"{self.base}{path}", json=json, headers=h, timeout=10)
        r.raise_for_status()
        return r.json()

    def get(self, path):
        r = requests.get(f"{self.base}{path}", headers=self.headers, timeout=10)
        r.raise_for_status()
        return r.json()

    def create_budget(self, *, user_id, total_limit, expires_at, intent_context=None,
                      allocations=None, currency="USD"):
        body = {"userId": user_id, "totalLimit": total_limit, "expiresAt": expires_at,
                "currency": currency}
        if intent_context:
            body["intentContext"] = intent_context
        if allocations:
            body["allocations"] = allocations
        return self.post("/api/v1/budgets", json=body)

    def authorize(self, *, session_token, agent_id, description, requested_amount,
                  claimed_category=None, parent_event_id=None):
        body = {
            "agentId": agent_id,
            "actionType": "PURCHASE",
            "description": description,
            "requestedQuantity": requested_amount,
            "idempotencyKey": str(uuid.uuid4()),
        }
        if claimed_category:
            body["claimedCategory"] = claimed_category
        if parent_event_id:
            body["parentEventId"] = parent_event_id
        return self.post("/api/v1/authorize", json=body,
                         extra_headers={"X-Session-Token": session_token})

    def confirm(self, event_id, confirmed_amount):
        return self.post(f"/api/v1/events/{event_id}/confirm",
                         json={"confirmedQuantity": confirmed_amount})

    def get_budget(self, budget_id):
        return self.get(f"/api/v1/budgets/{budget_id}")


# ---------------------------------------------------------------------------
# Formatting helpers
# ---------------------------------------------------------------------------
def _expires_at(hours: int = 23) -> str:
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M:%SZ")


def _divider(title: str = "") -> None:
    width = 60
    if title:
        pad = (width - len(title) - 2) // 2
        print(CYAN("─" * pad + f" {title} " + "─" * pad))
    else:
        print(DIM("─" * width))


def _print_decision(description: str, decision: str, amount: float,
                    reason: str = None, indent: int = 0) -> None:
    prefix = "  " * indent
    icon = GREEN("✓ APPROVED") if decision == "AUTHORIZED" else RED("✗ DENIED")
    amount_str = f"${amount:,.2f}"
    print(f"{prefix}{icon}  {BOLD(amount_str)}  {description}")
    if reason:
        print(f"{prefix}           {DIM('reason: ' + reason)}")


def _print_budget_summary(budget: dict) -> None:
    spent     = budget.get("quantitySpent", budget.get("amountSpent", 0))
    reserved  = budget.get("quantityReserved", budget.get("amountReserved", 0))
    available = budget.get("availableQuantity", budget.get("availableAmount", 0))
    total     = budget.get("totalLimit", 0)
    used_pct  = int(((spent + reserved) / total) * 100) if total else 0
    bar_filled = int(used_pct / 5)
    bar = "█" * bar_filled + "░" * (20 - bar_filled)
    print(f"\n  Budget:  {DIM(bar)}  {used_pct}% used")
    print(f"  Spent:   {BOLD(f'${spent:,.2f}')}   Reserved: ${reserved:,.2f}   Available: {GREEN(f'${available:,.2f}')}")


def _auth_result(result: dict):
    """Extract decision, event_id, denial_code from a raw authorize response."""
    decision    = result["decision"]
    event_id    = result.get("id") or result.get("eventId")
    denial_code = result.get("denialReason")
    return decision, event_id, denial_code


# ---------------------------------------------------------------------------
# Scenario 1: Multi-agent travel booking
# ---------------------------------------------------------------------------
def scenario_travel(client: _RawClient, state: dict) -> None:
    _divider("Scenario 1: Multi-Agent Travel Booking")

    state_key = "travel_budget_id"
    if state_key in state and _budget_alive(client, state[state_key]):
        print(f"\n  {DIM('Already seeded — budget ' + state[state_key][:8] + '...')}")
        print(f"  {DIM('Delete demo/.demo-state.json to re-seed.')}\n")
        return

    print(f"\n  {DIM('Orchestrator coordinates a $500 travel budget.')}")
    print(f"  {DIM('Allocations: $150 flight · $300 hotel · $50 ground transport')}")
    print(f"  {DIM('Sub-agents for hotel and transport cite the flight event as parent.')}\n")

    budget = client.create_budget(
        user_id="travel_agent_user",
        total_limit=500.00,
        expires_at=_expires_at(),
        intent_context="Book round-trip travel to NYC",
        allocations=[
            {"category": "flight",           "allowedCategories": ["flight"],             "limit": 150.00},
            {"category": "hotel",            "allowedCategories": ["hotel"],              "limit": 300.00},
            {"category": "ground_transport", "allowedCategories": ["taxi", "car_rental"], "limit": 50.00},
        ],
    )
    bid = budget["id"]
    tok = budget["sessionToken"]
    print(f"  Budget created  {DIM('id=' + bid[:8] + '...')}\n")

    # Orchestrator: try premium flight first — denied (exceeds $150 allocation)
    r = client.authorize(session_token=tok, agent_id="orchestrator_v1",
                         description="Premium flight — JFK business class",
                         requested_amount=280.00, claimed_category="flight")
    d, premium_id, reason = _auth_result(r)
    _print_decision("orchestrator → Premium flight — JFK business class", d, 280.00, reason)

    # Orchestrator: fall back to economy — authorized (this becomes the ROOT event)
    r = client.authorize(session_token=tok, agent_id="orchestrator_v1",
                         description="Economy flight — JFK round-trip",
                         requested_amount=149.00, claimed_category="flight")
    d, flight_id, reason = _auth_result(r)
    _print_decision("orchestrator → Economy flight — JFK round-trip", d, 149.00, reason)
    if d == "AUTHORIZED":
        client.confirm(flight_id, 149.00)

    # hotel_agent: spawned by orchestrator — cites flight event as parent
    r = client.authorize(session_token=tok, agent_id="hotel_agent_v1",
                         description="Hotel — 2 nights Manhattan",
                         requested_amount=260.00, claimed_category="hotel",
                         parent_event_id=flight_id)
    d, hotel_id, reason = _auth_result(r)
    _print_decision("  hotel_agent → Hotel — 2 nights Manhattan", d, 260.00, reason, indent=1)
    if d == "AUTHORIZED":
        client.confirm(hotel_id, 255.00)

    # transport_agent: spawned by orchestrator — cites flight event as parent
    r = client.authorize(session_token=tok, agent_id="transport_agent_v1",
                         description="Rental car — 3 days",
                         requested_amount=95.00, claimed_category="car_rental",
                         parent_event_id=flight_id)
    d, transport_id, reason = _auth_result(r)
    _print_decision("  transport_agent → Rental car — 3 days (over $50 cap)", d, 95.00, reason, indent=1)

    final = client.get_budget(bid)
    _print_budget_summary(final)
    print()

    state[state_key] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 2: Customer support refund agent
# ---------------------------------------------------------------------------
def scenario_refund(client: _RawClient, state: dict) -> None:
    _divider("Scenario 2: Customer Support Refund Agent")

    state_key = "refund_budget_ids"
    if state_key in state:
        alive = [bid for bid in state[state_key] if _budget_alive(client, bid)]
        if len(alive) == 3:
            print(f"\n  {DIM('Already seeded — 3 refund budgets present.')}")
            print(f"  {DIM('Delete demo/.demo-state.json to re-seed.')}\n")
            return

    print(f"\n  {DIM('Support agents are capped at $100 per customer refund.')}")
    print(f"  {DIM('Larger refunds require human supervisor approval.')}\n")

    created_ids = []

    def handle_customer(customer_id: str, refund_amount: float, issue: str) -> None:
        budget = client.create_budget(
            user_id=customer_id,
            total_limit=100.00,
            expires_at=_expires_at(),
            intent_context=f"Customer refund: {issue}",
        )
        bid = budget["id"]
        tok = budget["sessionToken"]
        created_ids.append(bid)

        r = client.authorize(session_token=tok, agent_id="support_agent_v1",
                             description=f"Refund for: {issue}",
                             requested_amount=refund_amount)
        decision, event_id, denial_code = _auth_result(r)

        _print_decision(f"{customer_id}: {issue}", decision, refund_amount, denial_code)

        if decision == "AUTHORIZED" and event_id:
            client.confirm(event_id, refund_amount)
            print(f"  {DIM('  → Refund issued automatically.')}")
        else:
            print(f"  {YELLOW('  → Escalated to human supervisor.')}")

    handle_customer("cust_A1234", 80.00,  "delayed shipment — order #88821")
    handle_customer("cust_B5678", 120.00, "defective product — order #99105")
    handle_customer("cust_C9012", 45.00,  "wrong item received — order #77634")
    print()

    state[state_key] = created_ids
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 3: Multi-agent campaign orchestration (3-level spend tree)
# ---------------------------------------------------------------------------
def scenario_campaign(client: _RawClient, state: dict) -> None:
    _divider("Scenario 3: Multi-Agent Campaign Orchestration")

    state_key = "campaign_budget_id"
    if state_key in state and _budget_alive(client, state[state_key]):
        print(f"\n  {DIM('Already seeded — budget ' + state[state_key][:8] + '...')}")
        print(f"  {DIM('Delete demo/.demo-state.json to re-seed.')}\n")
        return

    print(f"\n  {DIM('Orchestrator plans a product launch campaign ($2,000 budget).')}")
    print(f"  {DIM('Spawns ads and content sub-agents. Content spawns a vendor agent.')}")
    print(f"  {DIM('Spend Tree shows 3-level orchestrator → sub-agent → vendor chain.')}\n")

    budget = client.create_budget(
        user_id="campaign_manager",
        total_limit=2000.00,
        expires_at=_expires_at(hours=23),
        intent_context="Product launch campaign — Q2 2026",
        allocations=[
            {"category": "paid_ads", "allowedCategories": ["paid_ads"], "limit": 1200.00},
            {"category": "content",  "allowedCategories": ["content"],  "limit": 800.00},
        ],
    )
    bid = budget["id"]
    tok = budget["sessionToken"]
    print(f"  Budget created  {DIM('id=' + bid[:8] + '...')}\n")

    def auth(agent_id, description, amount, category, parent=None, indent=0):
        r = client.authorize(session_token=tok, agent_id=agent_id,
                             description=description, requested_amount=amount,
                             claimed_category=category, parent_event_id=parent)
        d, eid, reason = _auth_result(r)
        _print_decision(f"{'  ' * indent}{agent_id} → {description}", d, amount, reason)
        if d == "AUTHORIZED" and eid:
            client.confirm(eid, amount)
        return d, eid

    # Orchestrator: coordination fee (root event)
    _, orch_id = auth("orchestrator_v1", "Campaign planning fee", 50.00, "paid_ads")
    print()

    # Paid-ads sub-agent (parent = orchestrator event)
    print(f"  {DIM('Paid-ads sub-agent:')}")
    _, ads1 = auth("ads_agent_v1", "Google Ads — search campaign",   450.00, "paid_ads", orch_id, 1)
    _, ads2 = auth("ads_agent_v1", "Meta Ads — retargeting campaign", 380.00, "paid_ads", orch_id, 1)
    auth("ads_agent_v1", "LinkedIn Ads — B2B audience",               420.00, "paid_ads", orch_id, 1)
    print()

    # Content sub-agent (parent = orchestrator event)
    print(f"  {DIM('Content sub-agent:')}")
    _, blog  = auth("content_agent_v1", "Blog post series — 5 articles", 200.00, "content", orch_id, 1)
    _, video = auth("content_agent_v1", "Video production — product demo", 350.00, "content", orch_id, 1)
    print()

    # Vendor agent (parent = video production event — 3rd level)
    print(f"  {DIM('Vendor agent (spawned by content sub-agent):')}")
    auth("vendor_agent_v1", "Stock footage license", 120.00, "content", video, 2)
    print()

    print(f"  {GREEN('✓')} Spend tree seeded — open the Spend Tree page to see the")
    print(f"    orchestrator → sub-agent → vendor hierarchy.\n")

    state[state_key] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Connectivity check
# ---------------------------------------------------------------------------
def _wait_for_service(base_url: str, max_wait: int = 30) -> None:
    health = f"{base_url}/actuator/health"
    deadline = time.time() + max_wait
    print(DIM(f"  Waiting for service at {base_url} ..."), end="", flush=True)
    while time.time() < deadline:
        try:
            r = requests.get(health, timeout=3)
            if r.status_code == 200 and r.json().get("status") == "UP":
                print(GREEN(" UP"))
                return
        except Exception:
            pass
        print(".", end="", flush=True)
        time.sleep(1)
    print(RED(" TIMEOUT"))
    print(f"\n  Service did not become healthy within {max_wait}s.")
    print("  Make sure the stack is running:")
    print("    ./mvnw clean package -DskipTests && docker-compose up --build -d")
    sys.exit(1)


def _seed_demo_data(base_url: str, api_key: str) -> None:
    """Ensure tenant + API key exist. Idempotent — safe to call every run."""
    try:
        r = requests.post(
            f"{base_url}/internal/demo/seed",
            json={"apiKey": api_key},
            timeout=5,
        )
        if r.status_code not in (200, 201, 404, 405):
            pass
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(description="FiGuard demo script")
    parser.add_argument("--base-url",  default="http://localhost:8080")
    parser.add_argument("--api-key",   default="ab_live_demo")
    parser.add_argument("--no-wait",   action="store_true",
                        help="Skip health check (service already running)")
    parser.add_argument("--force",     action="store_true",
                        help="Ignore state file and re-seed all scenarios")
    args = parser.parse_args()

    print()
    print(BOLD("  FiGuard — Pre-flight Spend Authorization for AI Agents"))
    print(DIM("  " + "─" * 50))
    print()

    if not args.no_wait:
        _wait_for_service(args.base_url)
        print()

    _seed_demo_data(args.base_url, args.api_key)

    client = _RawClient(args.base_url, args.api_key)
    state = {} if args.force else _load_state()

    t0 = time.time()
    scenario_travel(client, state)
    scenario_refund(client, state)
    scenario_campaign(client, state)
    elapsed = time.time() - t0

    _divider()
    print(f"\n  {GREEN('Done')}  {DIM(f'({elapsed:.1f}s)')}")
    print(f"  {DIM('State saved to demo/.demo-state.json')}\n")


if __name__ == "__main__":
    main()
