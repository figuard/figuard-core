"""
Live integration tests for FiGuard Anthropic integration.

These tests exercise guarded_anthropic_tool against a real running figuard-core
container. No actual Anthropic API calls are made — tool functions are called
directly to verify the FiGuard authorize → execute → confirm lifecycle.

Run:
    make run                   # start figuard-core container
    pytest tests/live/test_anthropic_live.py -v
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

pytest.importorskip("anthropic", reason="anthropic not installed — pip install figuard[anthropic]")

from figuard import FiGuardClient
from figuard.integrations.anthropic import guarded_anthropic_tool


def _expires_at() -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# guarded_anthropic_tool — live tests
# ---------------------------------------------------------------------------

class TestGuardedAnthropicToolLive:

    def test_authorized_call_executes_and_confirms(
        self, client: FiGuardClient, flat_budget
    ):
        """
        Full authorize → execute → confirm lifecycle.
        Event should appear as CONFIRMED in the ledger.
        """
        @guarded_anthropic_tool(
            client=client,
            session_token=flat_budget.session_token,
            category=None,
            agent_id="live_anthropic_agent",
        )
        def book_hotel(city: str, amount: float) -> str:
            return f"Hotel in {city} booked"

        result = book_hotel(city="Paris", amount=150.0)

        assert result == "Hotel in Paris booked"

        ledger = client.get_ledger(flat_budget.id, size=10)
        assert any(e.decision == "CONFIRMED" for e in ledger.events)

    def test_denied_call_returns_denial_string_function_never_runs(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        When FiGuard denies, the function body never executes and a denial
        string is returned — Claude can reason about the outcome.
        """
        ran = []

        @guarded_anthropic_tool(
            client=client,
            session_token=allocated_budget.session_token,
            category="car_rental",  # not in allocated_budget
            agent_id="live_anthropic_agent",
        )
        def rent_car(amount: float) -> str:
            ran.append(True)
            return "Car rented"

        result = rent_car(amount=80.0)

        assert "DENIED" in result
        assert "NO_MATCHING_ALLOCATION" in result
        assert ran == []

    def test_allocation_category_match_authorizes(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        A call with a category matching an allocation is authorized and confirmed.
        """
        @guarded_anthropic_tool(
            client=client,
            session_token=allocated_budget.session_token,
            category="flight",
            amount_key="price",
            agent_id="live_anthropic_agent",
        )
        def book_flight(destination: str, price: float) -> str:
            return f"Flight to {destination}"

        result = book_flight(destination="NYC", price=200.0)

        assert "NYC" in result

        ledger = client.get_ledger(allocated_budget.id, size=10)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) >= 1

    def test_tool_error_fails_event_and_reraises(
        self, client: FiGuardClient, flat_budget
    ):
        """
        When the wrapped function raises, fail_event is called and the exception
        propagates — the authorization reservation is released.
        """
        @guarded_anthropic_tool(
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_anthropic_agent",
        )
        def broken_tool(amount: float) -> str:
            raise RuntimeError("anthropic tool failure")

        with pytest.raises(RuntimeError, match="anthropic tool failure"):
            broken_tool(amount=50.0)

        ledger = client.get_ledger(flat_budget.id, size=10)
        assert any(e.decision == "FAILED" for e in ledger.events)

    def test_insufficient_funds_denied(
        self, client: FiGuardClient, tiny_budget
    ):
        """
        A request exceeding the budget limit is denied with INSUFFICIENT_FUNDS.
        """
        @guarded_anthropic_tool(
            client=client,
            session_token=tiny_budget.session_token,
            agent_id="live_anthropic_agent",
        )
        def big_purchase(amount: float) -> str:
            return "purchased"

        result = big_purchase(amount=500.0)

        assert "DENIED" in result
        assert "INSUFFICIENT_FUNDS" in result

    def test_custom_amount_key(
        self, client: FiGuardClient, flat_budget
    ):
        """
        When amount_key="cost", the guard reads cost, not amount.
        """
        @guarded_anthropic_tool(
            client=client,
            session_token=flat_budget.session_token,
            amount_key="cost",
            agent_id="live_anthropic_agent",
        )
        def buy_supplies(item: str, cost: float) -> str:
            return f"Bought {item}"

        result = buy_supplies(item="printer", cost=45.0)

        assert "printer" in result

        ledger = client.get_ledger(flat_budget.id, size=5)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) >= 1

    def test_multiple_sequential_tool_calls(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        Multiple sequential calls to differently-categorized tools against the
        same budget are each confirmed independently.
        """
        @guarded_anthropic_tool(
            client=client,
            session_token=allocated_budget.session_token,
            category="flight",
            agent_id="live_anthropic_agent",
        )
        def book_flight(amount: float) -> str:
            return "Flight booked"

        @guarded_anthropic_tool(
            client=client,
            session_token=allocated_budget.session_token,
            category="hotel",
            agent_id="live_anthropic_agent",
        )
        def book_hotel(amount: float) -> str:
            return "Hotel booked"

        r1 = book_flight(amount=100.0)
        r2 = book_hotel(amount=80.0)

        assert r1 == "Flight booked"
        assert r2 == "Hotel booked"

        ledger = client.get_ledger(allocated_budget.id, size=10)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) == 2
