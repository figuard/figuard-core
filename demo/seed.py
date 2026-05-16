"""
FiGuard Seed Script — 11 diverse scenarios showcasing the full product.

Scenarios:
  1.  Travel booking         — multi-agent spend tree, simple lifecycle
  2.  E-commerce procurement — allocation exhaustion, missing claimed category
  3.  Legal AI               — token budget, max-transaction ceiling
  4.  SaaS support           — entity dedup (ENTITY_ALREADY_AUTHORIZED)
  5.  Marketing campaign     — 3-level spend tree (orchestrator → sub-agents → vendor)
  6.  Cloud infra            — anomaly detection, budget paused
  7.  Financial payouts      — STRICT mode, forbidden item types
  8.  Sales outreach         — intent tags, INTENT_SCOPE_VIOLATION
  9.  Research pipeline USD  — CompositeGuard pattern with void on denial
  10. Research pipeline tok  — paired token budget for same composite pattern
  11. Velocity loop          — velocity_max_per_minute=3, 4th call returns VELOCITY_LIMIT_EXCEEDED

Usage:
  python demo/seed.py [--base-url http://localhost:8080] [--api-key ab_live_demo]

Idempotent: re-running skips already-seeded scenarios.
Delete demo/.seed-state.json to force a full re-seed.
"""

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone, timedelta

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
# State file
# ---------------------------------------------------------------------------
_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".seed-state.json")


def _load_state() -> dict:
    try:
        with open(_STATE_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_state(state: dict) -> None:
    with open(_STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)


# ---------------------------------------------------------------------------
# Thin HTTP client
# ---------------------------------------------------------------------------
class _Client:
    def __init__(self, base_url: str, api_key: str):
        self.base = base_url.rstrip("/")
        self._headers = {
            "X-Agent-Budget-Key": api_key,
            "Content-Type": "application/json",
        }

    def _post(self, path: str, body=None, extra_headers=None):
        h = {**self._headers, **(extra_headers or {})}
        r = requests.post(f"{self.base}{path}", json=body, headers=h, timeout=10)
        try:
            r.raise_for_status()
        except requests.HTTPError:
            print(RED(f"  HTTP {r.status_code} on POST {path}: {r.text[:300]}"))
            raise
        return r.json()

    def _get(self, path: str):
        r = requests.get(f"{self.base}{path}", headers=self._headers, timeout=10)
        r.raise_for_status()
        return r.json()

    def _delete(self, path: str):
        r = requests.delete(f"{self.base}{path}", headers=self._headers, timeout=10)
        r.raise_for_status()
        return r.json()

    # -- Budgets --
    def create_budget(self, body: dict) -> dict:
        return self._post("/api/v1/budgets", body)

    def cancel_budget(self, budget_id: str) -> None:
        try:
            self._post(f"/api/v1/budgets/{budget_id}/cancel")
        except Exception:
            pass  # already cancelled / not found

    def get_budget(self, budget_id: str) -> dict:
        return self._get(f"/api/v1/budgets/{budget_id}")

    def list_budgets(self, page: int = 0, size: int = 50, include_cancelled: bool = False) -> dict:
        qs = f"page={page}&size={size}&includeCancelled={str(include_cancelled).lower()}"
        return self._get(f"/api/v1/budgets?{qs}")

    def extend_budget(self, budget_id: str, expires_at: str) -> dict:
        return self._post(f"/api/v1/budgets/{budget_id}/extend", {"expiresAt": expires_at})

    def cancel_batch(self, budget_ids: list) -> dict:
        return self._post("/api/v1/budgets/cancel-batch", {"budgetIds": budget_ids})

    # -- Delegation tokens --
    def create_delegation_token(self, budget_id: str, label: str, caps: list) -> dict:
        return self._post(f"/api/v1/budgets/{budget_id}/delegation-tokens",
                          {"label": label, "caps": caps})

    def revoke_delegation_token(self, token_id: str) -> dict:
        return self._delete(f"/api/v1/delegation-tokens/{token_id}")

    def list_delegation_tokens(self, budget_id: str) -> list:
        return self._get(f"/api/v1/budgets/{budget_id}/delegation-tokens")

    # -- Events --
    def authorize(self, *, session_token: str, body: dict) -> dict:
        return self._post(
            "/api/v1/authorize",
            body,
            extra_headers={"X-Session-Token": session_token},
        )

    def confirm(self, event_id: str, confirmed_quantity: float) -> dict:
        return self._post(
            f"/api/v1/events/{event_id}/confirm",
            {"confirmedQuantity": confirmed_quantity},
        )

    def fail(self, event_id: str, reason: str = "agent_failed") -> dict:
        return self._post(
            f"/api/v1/events/{event_id}/fail",
            {"reason": reason},
        )

    def void(self, event_id: str, reason: str = "partial_denial_rollback") -> dict:
        return self._post(
            f"/api/v1/events/{event_id}/void",
            {"reason": reason},
        )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _expires_at(hours: int = 23) -> str:
    """All budgets must expire within 24h per API limit. Use SQL to force EXPIRED/PAUSED after."""
    dt = datetime.now(timezone.utc) + timedelta(hours=hours)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _divider(title: str = "") -> None:
    width = 64
    if title:
        pad = max(1, (width - len(title) - 2) // 2)
        line = "─" * pad + f" {title} " + "─" * pad
    else:
        line = "─" * width
    print(CYAN(line))


def _print_event(label: str, decision: str, amount, reason: str = None, indent: int = 0) -> None:
    prefix = "  " * indent
    icon = GREEN("✓ AUTH") if decision == "AUTHORIZED" else RED("✗ DENY")
    amt = f"{amount:,.0f}" if isinstance(amount, (int, float)) else str(amount)
    print(f"{prefix}  {icon}  {label}  ({amt})")
    if reason:
        print(f"{prefix}         {DIM(reason)}")


def _auth(client: _Client, *, session_token: str, agent_id: str, description: str,
          quantity: float, category: str = None, item_type: str = None,
          entity_id: str = None, parent_event_id: str = None,
          intent_context: str = None, trace_id: str = None,
          action_type: str = "PURCHASE", indent: int = 0) -> tuple[str, str, str]:
    """
    Authorize a spend event. Returns (decision, event_id, denial_reason).
    Prints result inline.
    """
    body = {
        "agentId": agent_id,
        "actionType": action_type,
        "description": description,
        "requestedQuantity": quantity,
        "idempotencyKey": str(uuid.uuid4()),
    }
    if category:
        body["claimedCategory"] = category
    if item_type:
        body["claimedItemType"] = item_type
    if entity_id:
        body["entityId"] = entity_id
    if parent_event_id:
        body["parentEventId"] = parent_event_id
    if intent_context:
        body["intentContext"] = intent_context
    if trace_id:
        body["traceId"] = trace_id

    r = client.authorize(session_token=session_token, body=body)
    decision = r["decision"]
    event_id = r.get("id") or r.get("eventId")
    denial   = r.get("denialReason")
    _print_event(description, decision, quantity, denial, indent)
    return decision, event_id, denial


# ---------------------------------------------------------------------------
# Backdate registry
# ---------------------------------------------------------------------------
# Each entry: {"event_id": str, "days_ago": int, "hour": int}
_BACKDATE: list[dict] = []

def _schedule_backdate(event_id: str, days_ago: int, hour: int = 10) -> None:
    if event_id:
        _BACKDATE.append({"event_id": event_id, "days_ago": days_ago, "hour": hour})


# ---------------------------------------------------------------------------
# SQL backdate + status overrides
# ---------------------------------------------------------------------------
def _apply_sql(budget_patches: list[dict]) -> None:
    """
    budget_patches: list of {"budget_id": str, "status": str}  — optional
    _BACKDATE: global list of event backdate instructions
    """
    psql = "/opt/homebrew/opt/postgresql@16/bin/psql"
    dsn  = "postgresql://figuard:figuard_local@localhost:5432/figuard"
    env  = {**os.environ, "PGPASSWORD": "figuard_local"}

    statements = []

    for entry in _BACKDATE:
        eid   = entry["event_id"]
        days  = entry["days_ago"]
        hour  = entry["hour"]
        ts    = (datetime.now(timezone.utc) - timedelta(days=days)).replace(
            hour=hour, minute=0, second=0, microsecond=0
        ).strftime("%Y-%m-%d %H:%M:%S+00")
        statements.append(
            f"UPDATE spend_events SET created_at = '{ts}' WHERE id = '{eid}';"
        )

    for patch in budget_patches:
        bid    = patch["budget_id"]
        status = patch["status"]
        if status == "EXPIRED":
            # Set expires_at to past so the scheduler will pick it up, or force status directly
            statements.append(
                f"UPDATE agent_budgets SET status = 'EXPIRED', "
                f"expires_at = NOW() - INTERVAL '1 day' WHERE id = '{bid}';"
            )
        elif status == "PAUSED":
            statements.append(
                f"UPDATE agent_budgets SET status = 'PAUSED' WHERE id = '{bid}';"
            )

    if not statements:
        print(DIM("  (no SQL patches needed)"))
        return

    sql = "\n".join(statements)
    result = subprocess.run(
        [psql, dsn],
        input=sql,
        capture_output=True,
        text=True,
        env=env,
    )
    if result.returncode != 0:
        print(RED(f"  SQL error: {result.stderr[:400]}"))
    else:
        print(GREEN(f"  SQL: {len(statements)} statement(s) applied."))


# ---------------------------------------------------------------------------
# Cancel all existing non-cancelled budgets
# ---------------------------------------------------------------------------
def cancel_all_existing(client: _Client) -> None:
    _divider("Cancelling existing budgets")
    page = 0
    cancelled = 0
    while True:
        resp = client.list_budgets(page=page, size=50)
        budgets = resp.get("content", [])
        for b in budgets:
            if b["status"] not in ("CANCELLED",):
                client.cancel_budget(b["id"])
                cancelled += 1
                print(f"  Cancelled {b['id'][:8]}…  ({b['status']})")
        if resp.get("last", True):
            break
        page += 1
    print(f"\n  {GREEN(str(cancelled))} budget(s) cancelled.\n")


# ---------------------------------------------------------------------------
# Scenario 1: Travel Booking
# ---------------------------------------------------------------------------
def scenario_travel(client: _Client, state: dict) -> None:
    _divider("1 · Travel Booking  ($800 · allocated · will expire)")
    KEY = "travel"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  Orchestrator + hotel/transport sub-agents. Budget forced EXPIRED via SQL."))

    budget = client.create_budget({
        "userId": "agent_traveler_01",
        "externalReference": "seed-travel-booking-v1",  # idempotent create — safe on re-seed
        "totalLimit": 800.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=23),
        "intentContext": "Book round-trip travel NYC → SFO",
        "allocations": [
            {"category": "flight",           "allowedCategories": ["flight"],             "limit": 400.00},
            {"category": "hotel",            "allowedCategories": ["hotel"],              "limit": 320.00},
            {"category": "ground_transport", "allowedCategories": ["taxi", "car_rental"], "limit": 80.00},
        ],
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created\n")

    # Day 6 ago — orchestrator tries premium flight (denied)
    d, eid, _ = _auth(client, session_token=tok, agent_id="orchestrator",
                      description="Business class — JFK→SFO", quantity=620.00,
                      category="flight", indent=0)
    _schedule_backdate(eid, days_ago=6, hour=9)

    # Day 6 ago — fall back economy (authorized) → confirm
    d, flight_id, _ = _auth(client, session_token=tok, agent_id="orchestrator",
                             description="Economy flight — JFK→SFO round-trip", quantity=389.00,
                             category="flight", indent=0)
    _schedule_backdate(flight_id, days_ago=6, hour=9)
    if d == "AUTHORIZED":
        client.confirm(flight_id, 389.00)

    # Day 5 — hotel sub-agent
    d, hotel_id, _ = _auth(client, session_token=tok, agent_id="hotel_agent",
                            description="Hotel — 3 nights Union Square", quantity=310.00,
                            category="hotel", parent_event_id=flight_id, indent=1)
    _schedule_backdate(hotel_id, days_ago=5, hour=14)
    if d == "AUTHORIZED":
        client.confirm(hotel_id, 295.00)

    # Day 4 — transport sub-agent (over cap → denied)
    d, taxi_id, _ = _auth(client, session_token=tok, agent_id="transport_agent",
                           description="Rental car — 4 days (over $80 cap)", quantity=95.00,
                           category="car_rental", parent_event_id=flight_id, indent=1)
    _schedule_backdate(taxi_id, days_ago=4, hour=10)

    # Day 4 — transport sub-agent retries with taxi (within cap)
    d, taxi2_id, _ = _auth(client, session_token=tok, agent_id="transport_agent",
                            description="Airport taxi — SFO pickup", quantity=48.00,
                            category="taxi", parent_event_id=flight_id, indent=1)
    _schedule_backdate(taxi2_id, days_ago=4, hour=11)
    if d == "AUTHORIZED":
        client.confirm(taxi2_id, 48.00)

    state[KEY] = bid
    _save_state(state)
    print(f"\n  {DIM('Will be forced to EXPIRED status via SQL.')}")


# ---------------------------------------------------------------------------
# Scenario 2: E-commerce Procurement
# ---------------------------------------------------------------------------
def scenario_procurement(client: _Client, state: dict) -> None:
    _divider("2 · E-commerce Procurement  ($10k · 3 allocations)")
    KEY = "procurement"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  Shows ALLOCATION_EXHAUSTED and MISSING_CLAIMED_CATEGORY denials."))

    budget = client.create_budget({
        "userId": "procurement_bot_v2",
        "totalLimit": 10_000.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=23),
        "intentContext": "Q2 hardware + software procurement",
        "externalReference": "PO-2026-Q2-001",
        "allocations": [
            {"category": "electronics", "allowedCategories": ["electronics", "hardware"],
             "limit": 6_000.00},
            {"category": "software",    "allowedCategories": ["software", "saas"],
             "limit": 3_000.00},
            {"category": "logistics",   "allowedCategories": ["logistics", "shipping"],
             "limit": 1_000.00},
        ],
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created\n")

    # Day 7 — bulk laptop order
    d, eid, _ = _auth(client, session_token=tok, agent_id="procurement_agent",
                      description="MacBook Pro M4 × 8 units", quantity=5_600.00,
                      category="electronics")
    _schedule_backdate(eid, days_ago=7, hour=10)
    if d == "AUTHORIZED": client.confirm(eid, 5_600.00)

    # Day 6 — monitors
    d, eid2, _ = _auth(client, session_token=tok, agent_id="procurement_agent",
                       description="27″ monitors × 6 units", quantity=1_800.00,
                       category="electronics")
    _schedule_backdate(eid2, days_ago=6, hour=11)
    # Electronics allocation has $400 left → ALLOCATION_EXHAUSTED expected — just confirm if auth'd
    if d == "AUTHORIZED": client.confirm(eid2, 1_800.00)

    # Day 5 — server hardware (ALLOCATION_EXHAUSTED — electronics near/over cap)
    d, eid3, _ = _auth(client, session_token=tok, agent_id="procurement_agent",
                       description="NAS server unit — storage expansion", quantity=950.00,
                       category="electronics")
    _schedule_backdate(eid3, days_ago=5, hour=9)

    # Day 5 — SaaS tooling
    d, eid4, _ = _auth(client, session_token=tok, agent_id="procurement_agent",
                       description="Figma Enterprise license — annual", quantity=2_400.00,
                       category="software")
    _schedule_backdate(eid4, days_ago=5, hour=14)
    if d == "AUTHORIZED": client.confirm(eid4, 2_400.00)

    # Day 3 — freight shipping
    d, eid5, _ = _auth(client, session_token=tok, agent_id="procurement_agent",
                       description="Freight shipping — bulk hardware delivery", quantity=380.00,
                       category="logistics")
    _schedule_backdate(eid5, days_ago=3, hour=13)
    if d == "AUTHORIZED": client.confirm(eid5, 380.00)

    # Day 2 — no category claimed → MISSING_CLAIMED_CATEGORY
    d, eid6, _ = _auth(client, session_token=tok, agent_id="procurement_agent",
                       description="Office chairs × 5 (no category set)", quantity=750.00)
    _schedule_backdate(eid6, days_ago=2, hour=10)

    state[KEY] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 3: Legal AI — token & API-call budgets
# ---------------------------------------------------------------------------
def scenario_legal(client: _Client, state: dict) -> None:
    _divider("3 · Legal AI  (token budget · max-transaction ceiling)")
    KEY = "legal"
    if KEY in state:
        print(DIM(f"  skipped (budgets {state[KEY][0][:8]}… + {state[KEY][1][:8]}…)"))
        return

    print(DIM("  Non-monetary unit budgets. Token budget has 60k-token ceiling per request."))

    # Budget A: LLM tokens (8M total, 60k ceiling per call)
    tok_budget = client.create_budget({
        "userId": "legal_ai_service",
        "externalReference": "LEGAL-TOK-MAY26",
        "totalLimit": 8_000_000,
        "unit": "tokens",
        "maxTransactionQuantity": 60_000,
        "expiresAt": _expires_at(hours=23),
        "intentContext": "Contract analysis — due diligence batch",
    })
    bid_tok = tok_budget["id"]
    tok_tok = tok_budget["tokens"][0]["sessionToken"]

    # Budget B: API calls (300 total, 20 per call ceiling)
    api_budget = client.create_budget({
        "userId": "legal_ai_service",
        "externalReference": "LEGAL-API-MAY26",
        "totalLimit": 300,
        "unit": "api_calls",
        "maxTransactionQuantity": 20,
        "expiresAt": _expires_at(hours=23),
        "intentContext": "Westlaw API queries — due diligence batch",
    })
    bid_api = api_budget["id"]
    tok_api = api_budget["tokens"][0]["sessionToken"]

    print(f"  Token budget {bid_tok[:8]}…  (8M tokens, ceil 60k)")
    print(f"  API budget   {bid_api[:8]}…  (300 calls, ceil 20)\n")

    # Token budget events — days 5-1
    for i, (doc, toks, day) in enumerate([
        ("NDA batch — 12 contracts",             45_000, 5),
        ("M&A agreement — 340-page document",    60_000, 4),
        ("Oversized clause extraction (>60k)",   85_000, 4),  # EXCEEDS_QUANTITY_LIMIT
        ("Employment contracts × 25",            52_000, 3),
        ("IP assignment agreements × 8",         38_000, 2),
        ("Final board resolution review",        41_000, 1),
    ]):
        d, eid, _ = _auth(client, session_token=tok_tok, agent_id="legal_analyzer",
                          description=doc, quantity=toks, category="analysis")
        _schedule_backdate(eid, days_ago=day, hour=10 + i % 4)
        if d == "AUTHORIZED": client.confirm(eid, toks)

    print()

    # API call budget events
    for doc, calls, day in [
        ("Case law search — tort cases",     12, 5),
        ("Statutory cross-reference check",  18, 4),
        ("Batch citation verification × 22", 22, 3),  # EXCEEDS_QUANTITY_LIMIT (ceil 20)
        ("Final precedent lookup",           9,  2),
    ]:
        d, eid, _ = _auth(client, session_token=tok_api, agent_id="legal_researcher",
                          description=doc, quantity=calls, category="research")
        _schedule_backdate(eid, days_ago=day, hour=11)
        if d == "AUTHORIZED": client.confirm(eid, calls)

    state[KEY] = [bid_tok, bid_api]
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 4: SaaS Customer Support — entity dedup
# ---------------------------------------------------------------------------
def scenario_support(client: _Client, state: dict) -> None:
    _divider("4 · SaaS Customer Support  (entity dedup · $250 ceiling)")
    KEY = "support"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  entityDedupEnabled prevents double-refunds for the same customer/ticket."))

    budget = client.create_budget({
        "userId": "support_ops",
        "totalLimit": 2_500.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=23),
        "maxTransactionQuantity": 250.00,
        "entityDedupEnabled": True,
        "intentContext": "Customer refund — automated support tier",
        "externalReference": "SUP-TIER1-MAY26",
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created\n")

    # Normal refunds — varied days
    refunds = [
        ("TICKET-4412", "order_88821", 80.00,  7, "Delayed shipment"),
        ("TICKET-4413", "order_99105", 45.00,  6, "Wrong item received"),
        ("TICKET-4414", "order_77634", 190.00, 5, "Defective product"),
        ("TICKET-4415", "order_55231", 75.00,  4, "Partial order missing"),
    ]
    for ticket, order_id, amount, day, reason in refunds:
        d, eid, _ = _auth(client, session_token=tok, agent_id="support_bot_v3",
                          description=f"{reason} — {ticket}", quantity=amount,
                          category="refund", entity_id=order_id)
        _schedule_backdate(eid, days_ago=day, hour=10)
        if d == "AUTHORIZED": client.confirm(eid, amount)

    print()

    # Over-ceiling refund
    d, eid, _ = _auth(client, session_token=tok, agent_id="support_bot_v3",
                      description="Premium item refund — TICKET-4420 (over $250 ceiling)", quantity=320.00,
                      category="refund", entity_id="order_34567")
    _schedule_backdate(eid, days_ago=3, hour=14)

    # Duplicate entity — ENTITY_ALREADY_AUTHORIZED
    d, eid2, _ = _auth(client, session_token=tok, agent_id="support_bot_v3",
                       description="Duplicate refund attempt — TICKET-4413 (same order)", quantity=45.00,
                       category="refund", entity_id="order_99105")
    _schedule_backdate(eid2, days_ago=2, hour=11)

    # Retried refund with new order ID after duplicate was denied
    d, eid3, _ = _auth(client, session_token=tok, agent_id="support_bot_v3",
                       description="Replacement unit — TICKET-4425", quantity=110.00,
                       category="refund", entity_id="order_10001")
    _schedule_backdate(eid3, days_ago=1, hour=9)
    if d == "AUTHORIZED": client.confirm(eid3, 110.00)

    state[KEY] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 5: Marketing Campaign — 3-level spend tree
# ---------------------------------------------------------------------------
def scenario_marketing(client: _Client, state: dict) -> None:
    _divider("5 · Marketing Campaign  ($8k · 4 allocations · spend tree)")
    KEY = "marketing"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  3-level spend tree: orchestrator → channel agents → vendor agent."))

    budget = client.create_budget({
        "userId": "marketing_team",
        "totalLimit": 8_000.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=23),
        "intentContext": "Product launch — Summer 2026 campaign",
        "externalReference": "MKT-2026-LAUNCH",
        "allocations": [
            {"category": "paid_search",  "allowedCategories": ["paid_search"],          "limit": 3_000.00},
            {"category": "social_media", "allowedCategories": ["social_media", "video"], "limit": 2_500.00},
            {"category": "content",      "allowedCategories": ["content"],               "limit": 2_000.00},
            {"category": "events",       "allowedCategories": ["events", "sponsorship"],  "limit": 500.00},
        ],
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created\n")

    # Level 1: orchestrator coordination (root event)
    d, orch_id, _ = _auth(client, session_token=tok, agent_id="campaign_orchestrator",
                          description="Campaign strategy & planning fee", quantity=200.00,
                          category="content")
    _schedule_backdate(orch_id, days_ago=7, hour=9)
    if d == "AUTHORIZED": client.confirm(orch_id, 200.00)

    print()
    print(DIM("  Search channel agent (parent = orchestrator):"))
    d, s1, _ = _auth(client, session_token=tok, agent_id="search_agent",
                     description="Google Ads — branded keywords", quantity=1_200.00,
                     category="paid_search", parent_event_id=orch_id, indent=1)
    _schedule_backdate(s1, days_ago=6, hour=10)
    if d == "AUTHORIZED": client.confirm(s1, 1_200.00)

    d, s2, _ = _auth(client, session_token=tok, agent_id="search_agent",
                     description="Google Ads — competitor keywords", quantity=900.00,
                     category="paid_search", parent_event_id=orch_id, indent=1)
    _schedule_backdate(s2, days_ago=5, hour=10)
    if d == "AUTHORIZED": client.confirm(s2, 900.00)

    d, s3, _ = _auth(client, session_token=tok, agent_id="search_agent",
                     description="Bing Ads — display retargeting (over allocation)", quantity=1_100.00,
                     category="paid_search", parent_event_id=orch_id, indent=1)
    _schedule_backdate(s3, days_ago=4, hour=11)  # will likely be denied — allocation near cap

    print()
    print(DIM("  Social media agent (parent = orchestrator):"))
    d, sm1, _ = _auth(client, session_token=tok, agent_id="social_agent",
                      description="Meta Ads — video retargeting", quantity=1_000.00,
                      category="video", parent_event_id=orch_id, indent=1)
    _schedule_backdate(sm1, days_ago=5, hour=14)
    if d == "AUTHORIZED": client.confirm(sm1, 1_000.00)

    d, sm2, _ = _auth(client, session_token=tok, agent_id="social_agent",
                      description="TikTok Ads — awareness campaign", quantity=800.00,
                      category="social_media", parent_event_id=orch_id, indent=1)
    _schedule_backdate(sm2, days_ago=4, hour=15)
    if d == "AUTHORIZED": client.confirm(sm2, 800.00)

    print()
    print(DIM("  Content agent (parent = orchestrator):"))
    d, c1, _ = _auth(client, session_token=tok, agent_id="content_agent",
                     description="Blog post series × 6 articles", quantity=600.00,
                     category="content", parent_event_id=orch_id, indent=1)
    _schedule_backdate(c1, days_ago=4, hour=13)
    if d == "AUTHORIZED": client.confirm(c1, 600.00)

    d, c2, _ = _auth(client, session_token=tok, agent_id="content_agent",
                     description="Video production — product demo", quantity=900.00,
                     category="content", parent_event_id=orch_id, indent=1)
    _schedule_backdate(c2, days_ago=3, hour=14)
    if d == "AUTHORIZED": client.confirm(c2, 900.00)

    # Level 3: vendor agent (parent = video production)
    print()
    print(DIM("  Vendor agent (parent = video content event — level 3):"))
    d, v1, _ = _auth(client, session_token=tok, agent_id="vendor_agent",
                     description="Stock footage license — 15 clips", quantity=240.00,
                     category="content", parent_event_id=c2, indent=2)
    _schedule_backdate(v1, days_ago=2, hour=10)
    if d == "AUTHORIZED": client.confirm(v1, 240.00)

    d, v2, _ = _auth(client, session_token=tok, agent_id="vendor_agent",
                     description="Voice-over talent — 3 spots", quantity=180.00,
                     category="content", parent_event_id=c2, indent=2)
    _schedule_backdate(v2, days_ago=1, hour=11)
    if d == "AUTHORIZED": client.confirm(v2, 180.00)

    state[KEY] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 6: Cloud Infrastructure — anomaly detection
# ---------------------------------------------------------------------------
def scenario_cloud(client: _Client, state: dict) -> None:
    _divider("6 · Cloud Infra  (500 gpu_hours · anomaly detection · will pause)")
    KEY = "cloud"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  Non-monetary. anomalyMinSampleSize=3. Budget forced PAUSED via SQL."))

    budget = client.create_budget({
        "userId": "ml_platform_service",
        "totalLimit": 500,
        "unit": "gpu_hours",
        "expiresAt": _expires_at(hours=23),
        "anomalyDetectionEnabled": True,
        "anomalyMinSampleSize": 3,
        "anomalyPauseThresholdMultiplier": 2.5,
        "intentContext": "Model training — nightly batch pipeline",
        "externalReference": "GPU-POOL-A100-01",
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created\n")

    # Establish baseline — 3 normal jobs (days 7, 6, 5)
    baseline_jobs = [
        ("Embedding model fine-tune — checkpoint A",  8.0,  7),
        ("Classifier training — v2.1 baseline",       9.5,  6),
        ("RLHF reward model — iteration 3",            7.0,  5),
    ]
    for desc, hrs, day in baseline_jobs:
        d, eid, _ = _auth(client, session_token=tok, agent_id="ml_scheduler",
                          description=desc, quantity=hrs, category="training")
        _schedule_backdate(eid, days_ago=day, hour=2)
        if d == "AUTHORIZED": client.confirm(eid, hrs)

    # Gradual ramp — days 4, 3
    ramp_jobs = [
        ("LLM pre-train — shard 1/4",  12.0, 4),
        ("LLM pre-train — shard 2/4",  14.0, 3),
    ]
    for desc, hrs, day in ramp_jobs:
        d, eid, _ = _auth(client, session_token=tok, agent_id="ml_scheduler",
                          description=desc, quantity=hrs, category="training")
        _schedule_backdate(eid, days_ago=day, hour=2)
        if d == "AUTHORIZED": client.confirm(eid, hrs)

    # Anomaly spike — 3× normal (day 2) → triggers anomaly detector
    d, spike_id, _ = _auth(client, session_token=tok, agent_id="ml_scheduler",
                            description="Full model retrain — unexpected 28-hour job", quantity=28.0,
                            category="training")
    _schedule_backdate(spike_id, days_ago=2, hour=1)
    if d == "AUTHORIZED": client.confirm(spike_id, 28.0)

    # Day 1 — another large job (budget will be PAUSED via SQL after spike)
    d, eid2, _ = _auth(client, session_token=tok, agent_id="ml_scheduler",
                       description="Distributed training — data parallel run", quantity=18.0,
                       category="training")
    _schedule_backdate(eid2, days_ago=1, hour=3)
    if d == "AUTHORIZED": client.confirm(eid2, 18.0)

    state[KEY] = bid
    _save_state(state)
    print(f"\n  {DIM('Will be forced to PAUSED status via SQL.')}")


# ---------------------------------------------------------------------------
# Scenario 7: Financial Payouts — STRICT + forbidden item types
# ---------------------------------------------------------------------------
def scenario_financial(client: _Client, state: dict) -> None:
    _divider("7 · Financial Payouts  ($50k · STRICT mode · forbidden items)")
    KEY = "financial"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  STRICT mode: category constrained AND item-type allowed list enforced."))

    budget = client.create_budget({
        "userId": "payout_service_prod",
        "totalLimit": 50_000.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=23),
        "softLimit": 40_000.00,
        "intentContext": "Contractor payout — May 2026 cycle",
        "externalReference": "PAY-2026-05",
        "allocations": [
            {
                "category":        "contractor_pay",
                "allowedCategories": ["contractor_pay"],
                "forbiddenItemTypes": ["crypto_transfer", "gambling_platform"],
                "enforcementMode": "STRICT",
                "limit": 35_000.00,
            },
            {
                "category":        "vendor_invoice",
                "allowedCategories": ["vendor_invoice"],
                "forbiddenItemTypes": ["crypto_transfer"],
                "enforcementMode": "STRICT",
                "limit": 15_000.00,
            },
        ],
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created\n")

    # Legitimate contractor payouts — days 7-4
    legit = [
        ("Contractor pay — Alice Chen (backend)", "contractor_pay", "bank_transfer", 8_500.00, 7),
        ("Contractor pay — Bob Kim (ML eng)",     "contractor_pay", "bank_transfer", 9_200.00, 6),
        ("Vendor invoice — Acme Cloud SaaS",       "vendor_invoice", "bank_transfer", 4_800.00, 5),
        ("Contractor pay — Carol Wu (design)",     "contractor_pay", "bank_transfer", 6_000.00, 4),
    ]
    for desc, cat, itype, amt, day in legit:
        d, eid, _ = _auth(client, session_token=tok, agent_id="payout_agent",
                          description=desc, quantity=amt, category=cat, item_type=itype)
        _schedule_backdate(eid, days_ago=day, hour=10)
        if d == "AUTHORIZED": client.confirm(eid, amt)

    print()

    # Forbidden item type — FORBIDDEN_ITEM_TYPE denial
    d, eid, _ = _auth(client, session_token=tok, agent_id="payout_agent",
                      description="Crypto transfer — external wallet (forbidden)", quantity=5_000.00,
                      category="contractor_pay", item_type="crypto_transfer")
    _schedule_backdate(eid, days_ago=3, hour=14)

    # Gambling platform via contractor_pay — FORBIDDEN_ITEM_TYPE
    d, eid2, _ = _auth(client, session_token=tok, agent_id="payout_agent",
                       description="Gambling affiliate payout (forbidden item type)", quantity=1_200.00,
                       category="contractor_pay", item_type="gambling_platform")
    _schedule_backdate(eid2, days_ago=2, hour=11)

    # Legit resume after denials
    d, eid3, _ = _auth(client, session_token=tok, agent_id="payout_agent",
                       description="Vendor invoice — DevTools Inc. license renewal", quantity=3_100.00,
                       category="vendor_invoice", item_type="bank_transfer")
    _schedule_backdate(eid3, days_ago=1, hour=9)
    if d == "AUTHORIZED": client.confirm(eid3, 3_100.00)

    state[KEY] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 8: Sales Outreach — intent tags + trace IDs
# ---------------------------------------------------------------------------
def scenario_sales(client: _Client, state: dict) -> None:
    _divider("8 · Sales Outreach  ($1.2k · flat · intent tags · INTENT_SCOPE_VIOLATION)")
    KEY = "sales"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  Flat budget (no allocations) — intentTags enforcement active."))
    print(DIM("  Out-of-scope requests get INTENT_SCOPE_VIOLATION. Trace IDs throughout."))

    budget = client.create_budget({
        "userId": "sales_ai_v1",
        "externalReference": "SALES-OUTREACH-Q2-26",
        "totalLimit": 1_200.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=23),
        "intentContext": "SDR automation — outbound outreach Q2 2026",
        "intentTags": ["outreach", "crm", "email_sequence", "linkedin"],
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created\n")

    campaign_trace = str(uuid.uuid4())

    in_scope = [
        ("LinkedIn Sales Navigator — monthly seat",   "email_sequence", 89.00,  7, "LI-001"),
        ("Apollo.io credits × 500 contacts",           "outreach",       120.00, 6, "AP-002"),
        ("Outreach.io — sequence automation seat",     "crm",            149.00, 5, "OR-003"),
        ("Instantly.ai — email warmup × 3 inboxes",   "email_sequence",  75.00,  4, "IN-004"),
        ("ZoomInfo — intent data add-on (monthly)",    "outreach",       180.00, 3, "ZI-005"),
        ("HubSpot CRM — Professional tier upgrade",    "crm",            200.00, 2, "HS-006"),
    ]
    for desc, intent, amt, day, short_trace in in_scope:
        trace = f"{campaign_trace[:8]}-{short_trace}"
        d, eid, _ = _auth(client, session_token=tok, agent_id="sdr_agent",
                          description=desc, quantity=amt, intent_context=intent,
                          trace_id=trace)
        _schedule_backdate(eid, days_ago=day, hour=10)
        if d == "AUTHORIZED": client.confirm(eid, amt)

    print()
    print(DIM("  Out-of-scope spend attempts (INTENT_SCOPE_VIOLATION expected):"))

    out_of_scope = [
        ("Google Ads spend — brand campaign",    "paid_advertising", 400.00, 3),
        ("Podcast sponsorship — SaaS Growth FM", "sponsorship",      350.00, 2),
        ("Conference booth — SaaStr Annual",     "events",           500.00, 1),
    ]
    for desc, intent, amt, day in out_of_scope:
        d, eid, _ = _auth(client, session_token=tok, agent_id="sdr_agent",
                          description=desc, quantity=amt, intent_context=intent,
                          trace_id=f"{campaign_trace[:8]}-OOS-{day}")
        _schedule_backdate(eid, days_ago=day, hour=14)

    state[KEY] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 9: Research Pipeline — CompositeGuard + void pattern
# ---------------------------------------------------------------------------
def scenario_research(client: _Client, state: dict) -> None:
    _divider("9 · Research Pipeline  (CompositeGuard: USD + token budgets · void on partial denial)")
    KEY = "research"
    if KEY in state:
        print(DIM(f"  skipped (budgets {state[KEY][0][:8]}… + {state[KEY][1][:8]}…)"))
        return

    print(DIM("  Two paired budgets (USD cost + token count). Both must authorize."))
    print(DIM("  On partial denial, the authorized budget event is voided for atomicity."))

    # USD cost budget
    usd_budget = client.create_budget({
        "userId": "research_pipeline",
        "totalLimit": 800.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=23),
        "intentContext": "AI research batch — inference cost tracking",
        "externalReference": "RESEARCH-USD-MAY26",
    })
    bid_usd = usd_budget["id"]
    tok_usd = usd_budget["tokens"][0]["sessionToken"]

    # Token count budget (500k total, 100k ceiling per request)
    tok_budget = client.create_budget({
        "userId": "research_pipeline",
        "totalLimit": 500_000,
        "unit": "tokens",
        "maxTransactionQuantity": 100_000,
        "expiresAt": _expires_at(hours=23),
        "intentContext": "AI research batch — token quota tracking",
        "externalReference": "RESEARCH-TOK-MAY26",
    })
    bid_tok = tok_budget["id"]
    tok_tok = tok_budget["tokens"][0]["sessionToken"]

    print(f"  USD budget   {bid_usd[:8]}…  ($800)")
    print(f"  Token budget {bid_tok[:8]}…  (500k tok, ceil 100k)\n")

    # Successful paired authorizations — days 6-2
    paired_jobs = [
        ("Paper summary batch — 50 abstracts",   25.00, 40_000, 6),
        ("Full paper analysis — 12 papers",      48.00, 75_000, 5),
        ("Literature review — topic clustering",  32.00, 55_000, 4),
        ("Citation graph extraction",             18.00, 28_000, 3),
    ]
    for desc, cost, tokens, day in paired_jobs:
        d1, eid1, _ = _auth(client, session_token=tok_usd, agent_id="research_agent",
                             description=f"[USD] {desc}", quantity=cost)
        _schedule_backdate(eid1, days_ago=day, hour=10)

        d2, eid2, _ = _auth(client, session_token=tok_tok, agent_id="research_agent",
                             description=f"[TOK] {desc}", quantity=tokens)
        _schedule_backdate(eid2, days_ago=day, hour=10)

        if d1 == "AUTHORIZED" and d2 == "AUTHORIZED":
            client.confirm(eid1, cost)
            client.confirm(eid2, tokens)
        elif d1 == "AUTHORIZED" and d2 != "AUTHORIZED":
            # Partial denial → void the USD event (CompositeGuard atomicity)
            client.void(eid1, "token_budget_denied_rollback")
            print(DIM(f"    ^ voided USD event (token budget denied) — atomic rollback"))
        elif d2 == "AUTHORIZED" and d1 != "AUTHORIZED":
            client.void(eid2, "usd_budget_denied_rollback")
            print(DIM(f"    ^ voided token event (USD budget denied) — atomic rollback"))

    print()
    print(DIM("  Partial denial scenario (token ceiling exceeded → void USD):"))

    # Oversized job: USD authorizes but tokens exceed 100k ceiling → void USD
    d1, eid1, _ = _auth(client, session_token=tok_usd, agent_id="research_agent",
                         description="[USD] Massive NLP corpus analysis — 120k tokens", quantity=95.00)
    _schedule_backdate(eid1, days_ago=2, hour=14)

    d2, eid2, _ = _auth(client, session_token=tok_tok, agent_id="research_agent",
                         description="[TOK] Massive NLP corpus analysis — exceeds 100k ceiling",
                         quantity=120_000)
    _schedule_backdate(eid2, days_ago=2, hour=14)

    if d1 == "AUTHORIZED" and d2 != "AUTHORIZED":
        client.void(eid1, "token_budget_denied_rollback")
        print(GREEN("  ✓ CompositeGuard pattern: USD event voided after token denial."))
    elif d1 == "AUTHORIZED":
        client.confirm(eid1, 95.00)
    if d2 == "AUTHORIZED":
        client.confirm(eid2, 120_000)

    state[KEY] = [bid_usd, bid_tok]
    _save_state(state)


# ---------------------------------------------------------------------------
# Scenario 11: Velocity Loop — velocity_max_per_minute enforcement
# ---------------------------------------------------------------------------
def scenario_velocity(client: _Client, state: dict) -> None:
    _divider("11 · Velocity Loop  ($50 · velocity_max_per_minute=3 · 4 rapid calls)")
    KEY = "velocity"
    if KEY in state:
        print(DIM(f"  skipped (budget {state[KEY][:8]}…)"))
        return

    print(DIM("  Creates a budget with velocity_max_per_minute=3."))
    print(DIM("  First 3 calls succeed; 4th returns VELOCITY_LIMIT_EXCEEDED."))

    budget = client.create_budget({
        "userId": "data_processor_demo",
        "totalLimit": 50.00,
        "currency": "USD",
        "expiresAt": _expires_at(hours=1),
        "intentContext": "Velocity controls demo — runaway retry loop",
        "externalReference": "seed-velocity-demo-v1",
        "velocityMaxPerMinute": 3,
    })
    bid = budget["id"]
    tok = budget["tokens"][0]["sessionToken"]
    print(f"  Budget {bid[:8]}…  created  (velocity_max_per_minute=3)\n")

    call_cost = 1.50

    for i in range(1, 5):
        d, eid, denial = _auth(
            client,
            session_token=tok,
            agent_id="data_processor_demo",
            description=f"Data processing API call — batch chunk {i}",
            quantity=call_cost,
            action_type="EXTERNAL_CALL",
        )
        if d == "AUTHORIZED":
            client.confirm(eid, call_cost)

    print()
    print(DIM("  Calls 1-3: AUTHORIZED (within the 3/min velocity window)"))
    print(DIM("  Call 4:    VELOCITY_LIMIT_EXCEEDED (window exhausted)"))

    state[KEY] = bid
    _save_state(state)


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------
def _wait_for_service(base_url: str, max_wait: int = 30) -> None:
    health = f"{base_url}/actuator/health"
    deadline = time.time() + max_wait
    print(DIM(f"  Waiting for service at {base_url} …"), end="", flush=True)
    while time.time() < deadline:
        try:
            r = requests.get(health, timeout=3)
            if r.status_code == 200 and r.json().get("status") == "UP":
                print(GREEN(" UP\n"))
                return
        except Exception:
            pass
        print(".", end="", flush=True)
        time.sleep(1)
    print(RED(" TIMEOUT"))
    sys.exit(1)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(description="FiGuard comprehensive seed script")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--api-key",  default="ab_live_demo")
    parser.add_argument("--no-wait",  action="store_true")
    parser.add_argument("--force",    action="store_true",
                        help="Ignore state file and re-seed all scenarios")
    parser.add_argument("--no-cancel", action="store_true",
                        help="Skip cancelling existing budgets")
    args = parser.parse_args()

    print()
    print(BOLD("  FiGuard — Comprehensive Seed  (11 scenarios)"))
    print(DIM("  " + "─" * 50))
    print()

    if not args.no_wait:
        _wait_for_service(args.base_url)

    # Ensure tenant + API key exist
    try:
        requests.post(f"{args.base_url}/internal/demo/seed",
                      json={"apiKey": args.api_key}, timeout=5)
    except Exception:
        pass

    client = _Client(args.base_url, args.api_key)
    state  = {} if args.force else _load_state()

    if not args.no_cancel:
        cancel_all_existing(client)

    t0 = time.time()

    scenario_travel(client, state)
    print()
    scenario_procurement(client, state)
    print()
    scenario_legal(client, state)
    print()
    scenario_support(client, state)
    print()
    scenario_marketing(client, state)
    print()
    scenario_cloud(client, state)
    print()
    scenario_financial(client, state)
    print()
    scenario_sales(client, state)
    print()
    scenario_research(client, state)
    print()
    scenario_velocity(client, state)
    print()

    # -----------------------------------------------------------------------
    # Phase 2 feature demos
    # -----------------------------------------------------------------------
    _divider("Phase 2 demos: advisory anomaly mode, extend, cancel-batch")

    # Demo 1: autoPauseOnAnomaly=false — advisory mode
    # Anomaly is detected and denied but budget stays ACTIVE
    advisory_budget = client.create_budget({
        "userId": "demo-advisory-agent",
        "totalLimit": 500,
        "currency": "USD",
        "expiresAt": _expires_at(hours=2),
        "anomalyDetectionEnabled": True,
        "autoPauseOnAnomaly": False,  # advisory mode — stays ACTIVE even on anomaly
        "anomalyMinSampleSize": 3,
        "anomalyPauseThresholdMultiplier": 2.0,
        "externalReference": "seed-advisory-mode-demo",
        "intentContext": "Advisory anomaly demo budget",
    })
    advisory_bid = advisory_budget["id"]
    advisory_tok = advisory_budget["tokens"][0]["sessionToken"]
    print(f"  Advisory anomaly budget {advisory_bid[:8]}…  created")

    # Demo 2: extendBudget — keep an agent alive past original expiry
    ext_budget = client.create_budget({
        "userId": "demo-extend-agent",
        "totalLimit": 100,
        "currency": "USD",
        "expiresAt": _expires_at(hours=1),
        "externalReference": "seed-extend-demo",
        "intentContext": "Budget extend demo",
    })
    ext_bid = ext_budget["id"]
    # Extend by 2 more hours
    extended = client.extend_budget(ext_bid, _expires_at(hours=3))
    print(f"  Extend budget {ext_bid[:8]}…  extended → {extended['expiresAt'][:19]}")

    # Demo 3: cancelBatch — tear down multiple budgets in one call
    batch_ids = []
    for i in range(3):
        b = client.create_budget({
            "userId": f"demo-batch-agent-{i}",
            "totalLimit": 50,
            "currency": "USD",
            "expiresAt": _expires_at(hours=1),
            "externalReference": f"seed-batch-cancel-{i}",
            "intentContext": f"Batch cancel demo {i}",
        })
        batch_ids.append(b["id"])
    cancelled = client.cancel_batch(batch_ids)
    print(f"  Cancel-batch: {len(cancelled)} budgets cancelled in one call")

    # -----------------------------------------------------------------------
    # Phase 3: Fleet budget + delegation tokens demo
    # -----------------------------------------------------------------------
    _divider("Phase 3 demo: fleet budget + scoped delegation tokens")

    if "fleet" not in state:
        # Create fleet budget with 3 resource allocations
        fleet = client.create_budget({
            "userId": "fleet-orchestrator",
            "totalLimit": 50000,
            "currency": "USD",
            "expiresAt": _expires_at(hours=24),
            "intentContext": "Refund agent fleet — customer order processing",
            "externalReference": "fleet-refund-v1",
            "allocations": [
                {"category": "refund",     "allowedCategories": ["refund"],     "limit": 50000, "currency": "USD"},
                {"category": "llm_tokens", "allowedCategories": ["llm_tokens"], "limit": 8000000},
                {"category": "email",      "allowedCategories": ["email"],      "limit": 20000},
            ],
        })
        state["fleet"] = fleet["id"]
        state["fleet_token"] = fleet["tokens"][0]["sessionToken"]
        _save(state)
        print(f"  Fleet budget: {fleet['id']}  (REFUND $50k | LLM_TOKENS 8M | EMAIL 20k)")

        # Create 3 per-customer delegation tokens
        orders = [
            ("order-1001", [
                {"category": "refund",     "limit": 3000},
                {"category": "llm_tokens", "limit": 10000},
            ]),
            ("order-1002", [
                {"category": "refund",     "limit": 3000},
                {"category": "llm_tokens", "limit": 10000},
            ]),
            ("order-1003", [
                {"category": "refund",     "limit": 1500},  # partial-refund order
                {"category": "llm_tokens", "limit": 5000},
            ]),
        ]
        delegation_tokens = {}
        for label, caps in orders:
            tok = client.create_delegation_token(state["fleet"], label=label, caps=caps)
            delegation_tokens[label] = tok["sessionToken"]
            print(f"  Delegation token for {label}: prefix={tok['sessionTokenPrefix']}  caps={[(c['category'], c['totalLimit']) for c in tok['caps']]}")
            state[f"delegation_{label}"] = tok["id"]
        _save(state)

        # Simulate order-1001 agent processing a $500 refund
        result = client.authorize(
            session_token=delegation_tokens["order-1001"],
            body={
                "agentId": "refund-agent-order-1001",
                "actionType": "REFUND",
                "description": "Process refund for order 1001",
                "requestedQuantity": 500,
                "idempotencyKey": str(uuid.uuid4()),
                "claimedCategory": "refund",
            },
        )
        print(f"  order-1001 refund $500: {result['decision']}")
        if result["decision"] == "AUTHORIZED":
            client.confirm(result["eventId"], 500)

        # Simulate LLM token usage (no per-agent cap for email, so it hits fleet directly)
        llm_result = client.authorize(
            session_token=delegation_tokens["order-1001"],
            body={
                "agentId": "refund-agent-order-1001",
                "actionType": "LLM_CALL",
                "description": "Analyze refund reason with LLM",
                "requestedQuantity": 1200,
                "idempotencyKey": str(uuid.uuid4()),
                "claimedCategory": "llm_tokens",
            },
        )
        print(f"  order-1001 LLM tokens 1200: {llm_result['decision']}")

        # Revoke order-1003 token (order cancelled)
        if f"delegation_order-1003" in state:
            revoked = client.revoke_delegation_token(state["delegation_order-1003"])
            print(f"  order-1003 token revoked: {revoked['status']}")

        print(f"  Fleet demo complete — {len(orders)} agents, 1 revoked")
    else:
        print(f"  Fleet already seeded: {state['fleet']} — skipping")

    # Apply SQL patches
    _divider("SQL: backdate events + force EXPIRED/PAUSED")
    budget_patches = []
    if "travel" in state:
        budget_patches.append({"budget_id": state["travel"], "status": "EXPIRED"})
    if "cloud" in state:
        budget_patches.append({"budget_id": state["cloud"], "status": "PAUSED"})
    _apply_sql(budget_patches)

    elapsed = time.time() - t0
    _divider()
    print(f"\n  {GREEN('Done')}  {DIM(f'({elapsed:.1f}s)')}")
    print(f"  {DIM('State saved to demo/.seed-state.json')}\n")


if __name__ == "__main__":
    main()
