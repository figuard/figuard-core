"""
FiGuard Demo — two scenarios showing the full value proposition.

Scenario 1: Travel booking agent
  An agent is given $500 to book travel: $150 flights + $300 hotel + $50 ground transport.
  It tries to upgrade the flight (denied — exceeds flight allocation),
  books the base flight (approved), books the hotel (approved),
  then attempts to add a rental car (denied — exceeds ground transport allocation).

Scenario 2: Customer support refund agent
  Agents are capped at $100 per customer refund.
  An $80 refund is approved. A $120 refund is denied — the agent
  must escalate to a human supervisor instead of issuing it directly.

Run against local Docker stack:
  ./mvnw clean package -DskipTests
  docker-compose up --build -d
  python demo/demo.py

Or against a running local service (no Docker):
  python demo/demo.py --base-url http://localhost:8080 --api-key <your-key>
"""

import argparse
import sys
import time
import uuid
from datetime import datetime, timezone, timedelta

# ---------------------------------------------------------------------------
# Try to import the SDK; fall back to plain requests so the demo works
# even without the SDK installed.
# ---------------------------------------------------------------------------
try:
    sys.path.insert(0, "sdk/python")
    from figuard import FiGuardClient, FiGuardDeniedException
    _SDK_AVAILABLE = True
except ImportError:
    _SDK_AVAILABLE = False

import requests  # always available

# ---------------------------------------------------------------------------
# ANSI colours (disabled on Windows / non-TTY)
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
# Thin HTTP wrapper — used when SDK is unavailable
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

    def create_budget(self, *, user_id, total_limit, expires_at, intent_context=None, allocations=None):
        body = {"userId": user_id, "totalLimit": total_limit, "expiresAt": expires_at}
        if intent_context:
            body["intentContext"] = intent_context
        if allocations:
            body["allocations"] = allocations
        return self.post("/api/v1/budgets", json=body)

    def authorize(self, *, session_token, agent_id, description, requested_amount, claimed_category=None):
        body = {
            "agentId": agent_id,
            "actionType": "PURCHASE",
            "description": description,
            "requestedAmount": requested_amount,
            "idempotencyKey": str(uuid.uuid4()),
        }
        if claimed_category:
            body["claimedCategory"] = claimed_category
        return self.post("/api/v1/authorize", json=body, extra_headers={"X-Session-Token": session_token})

    def confirm(self, event_id, confirmed_amount):
        return self.post(f"/api/v1/events/{event_id}/confirm", json={"confirmedAmount": confirmed_amount})

    def get_budget(self, budget_id):
        return self.get(f"/api/v1/budgets/{budget_id}")


# ---------------------------------------------------------------------------
# Formatting helpers
# ---------------------------------------------------------------------------
def _expires_at(hours: int = 2) -> str:
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M:%SZ")

def _divider(title: str = "") -> None:
    width = 60
    if title:
        pad = (width - len(title) - 2) // 2
        print(CYAN("─" * pad + f" {title} " + "─" * pad))
    else:
        print(DIM("─" * width))

def _print_decision(description: str, decision: str, amount: float, reason: str = None) -> None:
    icon   = GREEN("✓ APPROVED") if decision == "AUTHORIZED" else RED("✗ DENIED")
    amount_str = f"${amount:,.2f}"
    if reason:
        print(f"  {icon}  {BOLD(amount_str)}  {description}")
        print(f"           {DIM('reason: ' + reason)}")
    else:
        print(f"  {icon}  {BOLD(amount_str)}  {description}")

def _print_budget_summary(budget: dict) -> None:
    spent     = budget.get("amountSpent", 0)
    reserved  = budget.get("amountReserved", 0)
    available = budget.get("availableAmount", 0)
    total     = budget.get("totalLimit", 0)
    used_pct  = int(((spent + reserved) / total) * 100) if total else 0
    bar_filled = int(used_pct / 5)
    bar = "█" * bar_filled + "░" * (20 - bar_filled)
    print(f"\n  Budget:  {DIM(bar)}  {used_pct}% used")
    print(f"  Spent:   {BOLD(f'${spent:,.2f}')}   Reserved: ${reserved:,.2f}   Available: {GREEN(f'${available:,.2f}')}")


# ---------------------------------------------------------------------------
# Scenario 1: Travel booking agent
# ---------------------------------------------------------------------------
def scenario_travel(client) -> None:
    _divider("Scenario 1: Travel Booking Agent")
    print(f"\n  {DIM('An agent is booking travel. Budget: $500 total')}")
    print(f"  {DIM('Allocations: $150 flights · $300 hotel · $50 ground transport')}\n")

    # Create budget with category allocations (allocations must sum to totalLimit)
    allocations = [
        {"category": "flight",           "allowedCategories": ["flight"],  "limit": 150.00},
        {"category": "hotel",            "allowedCategories": ["hotel"],   "limit": 300.00},
        {"category": "ground_transport", "allowedCategories": ["taxi", "car_rental"], "limit": 50.00},
    ]
    budget = client.create_budget(
        user_id="travel_agent_user",
        total_limit=500.00,
        expires_at=_expires_at(),
        intent_context="Book round-trip travel to NYC",
        allocations=allocations,
    )

    bid = budget["id"] if isinstance(budget, dict) else budget.id
    tok = budget["sessionToken"] if isinstance(budget, dict) else budget.session_token
    print(f"  Budget created  {DIM('id=' + bid[:8] + '...')}\n")

    # Helper — authorize and print result
    confirmed_events = []

    def auth(description, amount, category=None):
        result = client.authorize(
            session_token=tok,
            agent_id="travel_agent_v1",
            description=description,
            requested_amount=amount,
            claimed_category=category,
        )
        if isinstance(result, dict):
            decision    = result["decision"]
            event_id    = result.get("eventId")
            denial_code = result.get("denialReason")
        else:
            decision    = result.decision.name if hasattr(result.decision, "name") else str(result.decision)
            event_id    = result.event_id
            denial_code = result.denial_reason.name if result.denial_reason else None

        _print_decision(description, decision, amount, denial_code)

        if decision == "AUTHORIZED" and event_id:
            confirmed_events.append((event_id, amount))
        return decision, event_id

    # 1a. Try to book premium flight first (over the $150 allocation)
    auth("Premium flight upgrade — JFK business class",  280.00, "flight")

    # 1b. Book the base economy flight (within $150 allocation)
    auth("Economy flight — JFK round-trip",              149.00, "flight")

    # 1c. Book hotel (within $300 allocation)
    auth("Hotel — 2 nights Manhattan",                   260.00, "hotel")

    # 1d. Try to add rental car (exceeds $50 ground_transport allocation)
    auth("Rental car — 3 days",                           95.00, "car_rental")

    # Confirm all approved events
    for event_id, amount in confirmed_events:
        client.confirm(event_id, amount)

    # Final state
    final = client.get_budget(bid)
    _print_budget_summary(final if isinstance(final, dict) else {
        "amountSpent":    final.amount_spent,
        "amountReserved": final.amount_reserved,
        "availableAmount": final.available_amount,
        "totalLimit":     final.total_limit,
    })
    print()


# ---------------------------------------------------------------------------
# Scenario 2: Customer support refund agent
# ---------------------------------------------------------------------------
def scenario_refund(client) -> None:
    _divider("Scenario 2: Customer Support Refund Agent")
    print(f"\n  {DIM('Support agents are capped at $100 per customer refund.')}")
    print(f"  {DIM('Larger refunds require human supervisor approval.')}\n")

    def handle_customer(customer_id: str, refund_amount: float, issue: str) -> None:
        budget = client.create_budget(
            user_id=customer_id,
            total_limit=100.00,
            expires_at=_expires_at(),
            intent_context=f"Customer refund: {issue}",
        )
        bid = budget["id"] if isinstance(budget, dict) else budget.id
        tok = budget["sessionToken"] if isinstance(budget, dict) else budget.session_token

        result = client.authorize(
            session_token=tok,
            agent_id="support_agent_v1",
            description=f"Refund for: {issue}",
            requested_amount=refund_amount,
        )

        if isinstance(result, dict):
            decision    = result["decision"]
            event_id    = result.get("eventId")
            denial_code = result.get("denialReason")
        else:
            decision    = result.decision.name if hasattr(result.decision, "name") else str(result.decision)
            event_id    = result.event_id
            denial_code = result.denial_reason.name if result.denial_reason else None

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


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(description="FiGuard demo script")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--api-key",  default="ab_live_demo")
    parser.add_argument("--no-wait",  action="store_true",
                        help="Skip health check (service already running)")
    args = parser.parse_args()

    print()
    print(BOLD("  FiGuard — Pre-flight Spend Authorization for AI Agents"))
    print(DIM("  " + "─" * 50))
    print()

    if not args.no_wait:
        _wait_for_service(args.base_url)
        print()

    # Seed tenant + API key if needed (idempotent — safe to call every run)
    _seed_demo_data(args.base_url, args.api_key)

    client = _RawClient(args.base_url, args.api_key)

    t0 = time.time()
    scenario_travel(client)
    scenario_refund(client)
    elapsed = time.time() - t0

    _divider()
    print(f"\n  {GREEN('Demo complete')}  {DIM(f'({elapsed:.1f}s)')}\n")


def _seed_demo_data(base_url: str, api_key: str) -> None:
    """
    Ensure a tenant + API key exist for the demo key.
    Calls the internal seed endpoint if available; silently skips on 404/405.
    This allows the demo to run against a fresh Docker stack without manual DB setup.
    """
    try:
        r = requests.post(
            f"{base_url}/internal/demo/seed",
            json={"apiKey": api_key},
            timeout=5,
        )
        if r.status_code not in (200, 201, 404, 405):
            pass  # best-effort
    except Exception:
        pass  # service may not have a seed endpoint; that's fine


if __name__ == "__main__":
    main()
