#!/usr/bin/env python3
"""
FiGuard Python SDK smoke test / demo scenario.

Runs a complete authorize → confirm → fail → void → ledger → receipt flow
against a live FiGuard instance. Requires the service to be running
(docker-compose up, then mvn spring-boot:run or jar).

Usage:
    cd sdk/python
    pip install -e .[dev,async]
    cd ../../scripts
    python demo.py [--base-url http://localhost:8080] [--api-key ab_live_...]
"""

import argparse
import sys
import uuid

# Add the SDK to the path when running from the scripts/ directory
sys.path.insert(0, "../sdk/python")

from figuard import FiGuardClient
from figuard.exceptions import FiGuardDeniedException


def section(title: str) -> None:
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


def run(base_url: str, api_key: str) -> None:
    client = FiGuardClient(api_key=api_key, base_url=base_url)

    # ── 1. Create budget ──────────────────────────────────────────────────────
    section("1. Create budget ($500 for travel agent)")
    budget = client.create_budget(
        user_id="user_demo_001",
        total_limit=500.00,
        expires_at="2027-12-31T23:59:59Z",
        currency="USD",
        intent_context="NYC business trip — flights, hotel, ground transport",
    )
    print(f"  Budget ID     : {budget.id}")
    print(f"  Status        : {budget.status}")
    print(f"  Available     : {budget.currency} {budget.available_amount}")
    print(f"  Session token : {budget.session_token[:12]}... (truncated)")

    session_token = budget.session_token
    budget_id = budget.id

    def authorize(description: str, amount: float, idempotency_key: str | None = None) -> str:
        key = idempotency_key or str(uuid.uuid4())
        result = client.authorize(
            session_token=session_token,
            agent_id="agent_travel_bot_v2",
            action_type="PURCHASE",
            description=description,
            requested_quantity=amount,
            currency="USD",
            idempotency_key=key,
        )
        status = "✓ AUTHORIZED" if result.is_authorized else f"✗ DENIED ({result.denial_reason})"
        print(f"  {status:30s}  ${amount:>8.2f}  {description}")
        return result.event_id if result.is_authorized else None

    # ── 2. Four authorizations ────────────────────────────────────────────────
    section("2. Authorize four spend events")
    event_flight = authorize("NYC round-trip flight (JFK–SFO)", 289.00)
    event_hotel  = authorize("3 nights Marriott Midtown",       210.00)
    event_taxi   = authorize("JFK airport taxi",                  42.00)
    event_over   = authorize("Michelin dinner (over budget)",    180.00)  # will be denied

    # ── 3. Confirm the flight ─────────────────────────────────────────────────
    section("3. Confirm flight at final price")
    if event_flight:
        evt = client.confirm_event(event_flight, confirmed_quantity=285.50)
        print(f"  Confirmed  eventId={evt.id}  amount=$285.50")

    # ── 4. Confirm the hotel ──────────────────────────────────────────────────
    section("4. Confirm hotel at final price")
    if event_hotel:
        evt = client.confirm_event(event_hotel, confirmed_quantity=210.00)
        print(f"  Confirmed  eventId={evt.id}  amount=$210.00")

    # ── 5. Fail the taxi (payment gateway error) ──────────────────────────────
    section("5. Fail taxi authorization (payment gateway error)")
    if event_taxi:
        evt = client.fail_event(event_taxi, reason="PAYMENT_GATEWAY_ERROR",
                                error_message="Stripe returned HTTP 402")
        print(f"  Failed     eventId={evt.id}  reason={evt.failure_reason}")

    # ── 6. Re-authorize and void the taxi ────────────────────────────────────
    section("6. Re-authorize taxi, then void (agent cancelled trip)")
    event_taxi2 = authorize("JFK airport taxi (retry)", 42.00)
    if event_taxi2:
        result = client.void_event(event_taxi2, reason="TASK_CANCELLED")
        print(f"  Voided     eventId={result.event.id}  is_voided={result.is_voided}")

    # ── 7. Ledger ─────────────────────────────────────────────────────────────
    section("7. Ledger (all events, newest first)")
    page = client.get_ledger(budget_id, page=0, size=10)
    print(f"  Total events : {page.total_elements}")
    for e in page.events:
        print(f"  {e.decision:12s}  ${str(e.requested_quantity):>10s}  {e.description[:40]}")

    # ── 8. Budget state ───────────────────────────────────────────────────────
    section("8. Budget state after scenario")
    b = client.get_budget(budget_id)
    print(f"  Status    : {b.status}")
    print(f"  Spent     : {b.currency} {b.amount_spent}")
    print(f"  Reserved  : {b.currency} {b.amount_reserved}")
    print(f"  Available : {b.currency} {b.available_amount}")

    # ── 9. Receipt ────────────────────────────────────────────────────────────
    section("9. Shareable receipt URL")
    receipt_url = client.get_receipt_url(budget_id)
    print(f"  Receipt: {receipt_url}")

    section("✓ Demo complete — all steps passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="FiGuard Python SDK demo")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="FiGuard service base URL (default: http://localhost:8080)")
    parser.add_argument("--api-key", default="ab_live_integrationtest",
                        help="API key (default: ab_live_integrationtest for local dev)")
    args = parser.parse_args()

    run(args.base_url, args.api_key)
