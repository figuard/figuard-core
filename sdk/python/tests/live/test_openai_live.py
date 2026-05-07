"""
Live integration tests for FiGuard OpenAI function calling integration.

These tests exercise guarded_openai_function against a real running figuard-core
container. No actual OpenAI API calls are made — tool functions are called
directly to verify the FiGuard authorize → execute → confirm lifecycle.

Run:
    make run                   # start figuard-core container
    pytest tests/live/test_openai_live.py -v
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

pytest.importorskip("openai", reason="openai not installed — pip install figuard[openai]")

from figuard import FiGuardClient
from figuard.integrations.openai import guarded_openai_function


def _expires_at() -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# guarded_openai_function — live tests
# ---------------------------------------------------------------------------

class TestGuardedOpenAIFunctionLive:

    def test_authorized_call_executes_and_confirms(
        self, client: FiGuardClient, flat_budget
    ):
        """
        Full authorize → execute → confirm lifecycle.
        Event should appear as CONFIRMED in the ledger.
        """
        @guarded_openai_function(
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_openai_agent",
        )
        def book_hotel(city: str, amount: float) -> str:
            return f"Hotel in {city} booked"

        result = book_hotel(city="NYC", amount=189.0)

        assert result == "Hotel in NYC booked"

        ledger = client.get_ledger(flat_budget.id, size=10)
        assert any(e.decision == "CONFIRMED" for e in ledger.events)

    def test_denied_call_returns_denial_string_function_never_runs(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        When FiGuard denies, the function body never executes and a denial
        string is returned.
        """
        ran = []

        @guarded_openai_function(
            client=client,
            session_token=allocated_budget.session_token,
            category="car_rental",  # not in allocated_budget
            agent_id="live_openai_agent",
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
        A call with a matching allocation category is authorized and confirmed.
        """
        @guarded_openai_function(
            client=client,
            session_token=allocated_budget.session_token,
            category="flight",
            amount_key="price",
            agent_id="live_openai_agent",
        )
        def book_flight(destination: str, price: float) -> str:
            return f"Flight to {destination}"

        result = book_flight(destination="LAX", price=250.0)

        assert "LAX" in result

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
        @guarded_openai_function(
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_openai_agent",
        )
        def broken_tool(amount: float) -> str:
            raise RuntimeError("openai tool failure")

        with pytest.raises(RuntimeError, match="openai tool failure"):
            broken_tool(amount=50.0)

        ledger = client.get_ledger(flat_budget.id, size=10)
        assert any(e.decision == "FAILED" for e in ledger.events)

    def test_insufficient_funds_denied(
        self, client: FiGuardClient, tiny_budget
    ):
        """
        A request exceeding the budget limit is denied with INSUFFICIENT_FUNDS.
        """
        @guarded_openai_function(
            client=client,
            session_token=tiny_budget.session_token,
            agent_id="live_openai_agent",
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
        When amount_key="price", the guard reads price kwarg, not amount.
        """
        @guarded_openai_function(
            client=client,
            session_token=flat_budget.session_token,
            amount_key="price",
            agent_id="live_openai_agent",
        )
        def book_flight(destination: str, price: float) -> str:
            return f"Flight to {destination}"

        result = book_flight(destination="MIA", price=299.0)

        assert "MIA" in result

        ledger = client.get_ledger(flat_budget.id, size=5)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) >= 1

    def test_multiple_sequential_tool_calls(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        Multiple sequential calls each confirm independently.
        """
        @guarded_openai_function(
            client=client,
            session_token=allocated_budget.session_token,
            category="flight",
            agent_id="live_openai_agent",
        )
        def book_flight(amount: float) -> str:
            return "Flight booked"

        @guarded_openai_function(
            client=client,
            session_token=allocated_budget.session_token,
            category="hotel",
            agent_id="live_openai_agent",
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

    def test_allocation_exhausted_after_limit_reached(self, client: FiGuardClient):
        """
        After an allocation is exhausted, further calls to that category are
        denied with ALLOCATION_EXHAUSTED or INSUFFICIENT_FUNDS.
        """
        budget = client.create_budget(
            user_id="live_test_user",
            total_limit=50.00,
            expires_at=_expires_at(),
            allocations=[
                {
                    "category": "flight",
                    "allowedCategories": ["flight"],
                    "limit": 50.00,
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                }
            ],
        )

        @guarded_openai_function(
            client=client,
            session_token=budget.session_token,
            category="flight",
            agent_id="live_openai_agent",
        )
        def book_flight(amount: float) -> str:
            return "Flight booked"

        result_1 = book_flight(amount=40.0)
        assert "DENIED" not in result_1

        result_2 = book_flight(amount=40.0)  # only $10 remaining
        assert "DENIED" in result_2
        assert "ALLOCATION_EXHAUSTED" in result_2 or "INSUFFICIENT_FUNDS" in result_2
