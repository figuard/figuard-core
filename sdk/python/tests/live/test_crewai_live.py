"""
Live integration tests for FiGuard CrewAI integration.

These tests exercise FiGuardCrewGuard against a real running figuard-core
container. No LLM or actual CrewAI crew is required — the guard is exercised
by calling the wrapped tool's _run method directly.

Run:
    make run                   # start figuard-core container
    pytest tests/live/test_crewai_live.py -v
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock

import pytest


def _expires_at() -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")

pytest.importorskip("crewai", reason="crewai not installed — pip install figuard[crewai]")

from figuard import FiGuardClient
from figuard.integrations.crewai import FiGuardCrewGuard


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_tool(name: str = "process_action", run_result: str = "done") -> MagicMock:
    tool = MagicMock(spec=["name", "_run", "description"])
    tool.name = name
    tool.description = f"Live test tool: {name}"
    tool._run = MagicMock(return_value=run_result)
    return tool


# ---------------------------------------------------------------------------
# FiGuardCrewGuard — live tests
# ---------------------------------------------------------------------------

class TestCrewGuardLive:

    def test_authorized_call_executes_and_confirms(
        self, client: FiGuardClient, flat_budget
    ):
        """
        Full authorize → execute → confirm lifecycle.
        Event should appear as CONFIRMED in the ledger.
        """
        tool = _make_tool("book_hotel", run_result="Hotel booked")

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_crewai_agent",
        )

        result = tool._run(amount=200.0, destination="NYC")

        assert result == "Hotel booked"

        ledger = client.get_ledger(flat_budget.id, size=5)
        assert any(e.decision == "CONFIRMED" for e in ledger.events)

    def test_denied_call_returns_denial_string_tool_never_runs(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        When FiGuard denies, the original _run is never called and a denial
        string is returned — the LLM can reason about the outcome.
        """
        tool = _make_tool("book_car")
        original_run = tool._run

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=allocated_budget.session_token,
            category="car_rental",  # not in allocated_budget
            agent_id="live_crewai_agent",
        )

        result = tool._run(amount=80.0)

        assert "DENIED" in result
        assert "NO_MATCHING_ALLOCATION" in result
        original_run.assert_not_called()

    def test_allocation_category_match_authorizes(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        A call with a category that matches an allocation must be authorized
        and confirmed.
        """
        tool = _make_tool("book_flight", run_result="Flight booked")

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=allocated_budget.session_token,
            category="flight",
            agent_id="live_crewai_agent",
        )

        result = tool._run(amount=250.0, destination="LAX")

        assert result == "Flight booked"

        ledger = client.get_ledger(allocated_budget.id, size=5)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) >= 1

    def test_tool_error_fails_event_and_reraises(
        self, client: FiGuardClient, flat_budget
    ):
        """
        When the wrapped tool raises, fail_event is called and the exception
        propagates — the authorization reservation is released.
        """
        tool = _make_tool()

        def failing_run(**kwargs):
            raise RuntimeError("crewai tool failure")

        tool._run = failing_run

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_crewai_agent",
        )

        with pytest.raises(RuntimeError, match="crewai tool failure"):
            tool._run(amount=50.0)

        ledger = client.get_ledger(flat_budget.id, size=10)
        assert any(e.decision == "FAILED" for e in ledger.events)

    def test_insufficient_funds_denied(
        self, client: FiGuardClient, tiny_budget
    ):
        """
        A request exceeding the budget limit is denied with INSUFFICIENT_FUNDS.
        The LLM receives the denial string rather than an exception.
        """
        tool = _make_tool("big_purchase")

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=tiny_budget.session_token,
            agent_id="live_crewai_agent",
        )

        result = tool._run(amount=500.0)

        assert "DENIED" in result
        assert "INSUFFICIENT_FUNDS" in result

    def test_custom_amount_key_extracted_correctly(
        self, client: FiGuardClient, flat_budget
    ):
        """
        When amount_key="cost", the guard reads the cost kwarg, not amount.
        """
        tool = _make_tool("supply_purchase", run_result="Supplies ordered")

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=flat_budget.session_token,
            amount_key="cost",
            agent_id="live_crewai_agent",
        )

        result = tool._run(item="printer paper", cost=35.0)

        assert result == "Supplies ordered"

        ledger = client.get_ledger(flat_budget.id, size=5)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) >= 1

    def test_multiple_sequential_authorizations(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        Multiple sequential tool calls against the same budget stay within
        allocation limits and are each confirmed independently.
        """
        flight_tool = _make_tool("book_flight_1", run_result="First flight booked")
        hotel_tool = _make_tool("book_hotel_1", run_result="Hotel booked")

        FiGuardCrewGuard(
            tool=flight_tool,
            client=client,
            session_token=allocated_budget.session_token,
            category="flight",
            agent_id="live_crewai_agent",
        )

        FiGuardCrewGuard(
            tool=hotel_tool,
            client=client,
            session_token=allocated_budget.session_token,
            category="hotel",
            agent_id="live_crewai_agent",
        )

        r1 = flight_tool._run(amount=100.0)
        r2 = hotel_tool._run(amount=80.0)

        assert r1 == "First flight booked"
        assert r2 == "Hotel booked"

        ledger = client.get_ledger(allocated_budget.id, size=10)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) == 2

    def test_allocation_exhausted_after_limit_reached(
        self, client: FiGuardClient
    ):
        """
        After an allocation is exhausted, further calls to that category
        are denied with ALLOCATION_EXHAUSTED.
        """
        # Create a budget with a tight $50 flight allocation
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

        tool = _make_tool("book_flight")

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=budget.session_token,
            category="flight",
            agent_id="live_crewai_agent",
        )

        # First call — $40 — authorized
        result_1 = tool._run(amount=40.0)
        assert "DENIED" not in result_1

        # Re-wrap the tool (FiGuardCrewGuard patches _run in place, so the
        # already-patched _run is what we call again — no re-wrap needed)
        result_2 = tool._run(amount=40.0)  # only $10 remaining
        assert "DENIED" in result_2
        assert "ALLOCATION_EXHAUSTED" in result_2 or "INSUFFICIENT_FUNDS" in result_2
