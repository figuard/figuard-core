"""
Live integration tests for FiGuard LangChain integration.

These tests exercise FiGuardCallbackHandler and FiGuardToolGuard against a
real running figuard-core container. No LLM is required — the integration
callbacks and tool wrappers are exercised directly.

Run:
    make run                   # start figuard-core container
    pytest tests/live/test_langchain_live.py -v
"""

from __future__ import annotations

from unittest.mock import MagicMock
from uuid import uuid4

import pytest

pytest.importorskip("langchain_core", reason="langchain-core not installed — pip install figuard[langchain]")

from langchain_core.tools import BaseTool, ToolException

from figuard import FiGuardClient
from figuard.integrations.langchain import FiGuardCallbackHandler, FiGuardToolGuard


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_tool(name: str = "buy_item", run_result: str = "success") -> MagicMock:
    """Minimal BaseTool-shaped mock with a real _run callable."""
    tool = MagicMock(spec=["name", "_run", "description"])
    tool.name = name
    tool.description = f"Live test tool: {name}"
    tool._run = MagicMock(return_value=run_result)
    return tool


# ---------------------------------------------------------------------------
# FiGuardCallbackHandler — live tests
# ---------------------------------------------------------------------------

class TestCallbackHandlerLive:
    """Tests that drive FiGuardCallbackHandler against a real figuard container."""

    def test_authorized_tool_call_confirms_event(
        self, client: FiGuardClient, flat_budget
    ):
        """
        Full authorize → confirm lifecycle via the callback handler.
        Verifies the event appears as CONFIRMED in the ledger.
        """
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_langchain_agent",
        )
        run_id = uuid4()

        # Simulate LangChain calling on_tool_start before the tool runs
        handler.on_tool_start(
            {"name": "book_item"},
            '{"amount": 50.0, "item": "flight ticket"}',
            run_id=run_id,
        )

        assert str(run_id) in handler._pending
        event_id, _ = handler._pending[str(run_id)]

        # Simulate tool completing successfully
        handler.on_tool_end("Booking confirmed", run_id=run_id)

        # Pending entry should be cleared after confirm
        assert str(run_id) not in handler._pending

        # Verify the event is CONFIRMED in the ledger
        ledger = client.get_ledger(flat_budget.id, size=5)
        event = next((e for e in ledger.events if e.id == event_id), None)
        assert event is not None, f"event {event_id} not found in ledger"
        assert event.decision == "CONFIRMED"

    def test_denied_tool_call_raises_tool_exception(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        A tool call for a category not in the budget should raise ToolException
        before the tool executes, and leave no pending entry.
        """
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=allocated_budget.session_token,
            agent_id="live_langchain_agent",
            tool_category_map={"buy_insurance": "insurance"},  # not in budget
        )
        run_id = uuid4()

        with pytest.raises(ToolException) as exc_info:
            handler.on_tool_start(
                {"name": "buy_insurance"},
                '{"amount": 85.0}',
                run_id=run_id,
            )

        assert "DENIED" in str(exc_info.value)
        assert "NO_MATCHING_ALLOCATION" in str(exc_info.value)
        # No pending entry — tool was never started
        assert str(run_id) not in handler._pending

    def test_tool_error_marks_event_as_failed(
        self, client: FiGuardClient, flat_budget
    ):
        """
        When a tool raises after being authorized, on_tool_error must call
        fail_event and the event should be FAILED in the ledger.
        """
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_langchain_agent",
        )
        run_id = uuid4()

        handler.on_tool_start(
            {"name": "process_payment"},
            '{"amount": 99.0}',
            run_id=run_id,
        )
        event_id, _ = handler._pending[str(run_id)]

        handler.on_tool_error(RuntimeError("payment gateway timeout"), run_id=run_id)

        assert str(run_id) not in handler._pending

        ledger = client.get_ledger(flat_budget.id, size=10)
        event = next((e for e in ledger.events if e.id == event_id), None)
        assert event is not None
        assert event.decision == "FAILED"

    def test_ignored_tool_skips_authorization(
        self, client: FiGuardClient, flat_budget
    ):
        """
        Tools in ignore_tools must bypass FiGuard entirely — no authorize call,
        no pending entry, no ledger event.
        """
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_langchain_agent",
            ignore_tools={"search_web"},
        )
        run_id = uuid4()

        ledger_before = client.get_ledger(flat_budget.id)
        count_before = ledger_before.total_elements

        handler.on_tool_start(
            {"name": "search_web"},
            '{"query": "cheap flights"}',
            run_id=run_id,
        )

        assert str(run_id) not in handler._pending

        ledger_after = client.get_ledger(flat_budget.id)
        assert ledger_after.total_elements == count_before, (
            "Ignored tool should not create any ledger events"
        )

    def test_budget_exhausted_denies_when_funds_run_out(
        self, client: FiGuardClient, tiny_budget
    ):
        """
        After a $10 budget is fully reserved, a second call should be DENIED
        with INSUFFICIENT_FUNDS.
        """
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=tiny_budget.session_token,
            agent_id="live_langchain_agent",
        )

        # First call — $8 — should be authorized
        run_id_1 = uuid4()
        handler.on_tool_start(
            {"name": "tool_a"},
            '{"amount": 8.0}',
            run_id=run_id_1,
        )
        assert str(run_id_1) in handler._pending

        # Second call — $5 — should be denied (only $2 left)
        run_id_2 = uuid4()
        with pytest.raises(ToolException) as exc_info:
            handler.on_tool_start(
                {"name": "tool_b"},
                '{"amount": 5.0}',
                run_id=run_id_2,
            )

        assert "INSUFFICIENT_FUNDS" in str(exc_info.value)
        assert str(run_id_2) not in handler._pending

    def test_category_map_routes_to_correct_allocation(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        A call using the correct category must hit the right allocation
        and not be denied for a missing category.
        """
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=allocated_budget.session_token,
            agent_id="live_langchain_agent",
            tool_category_map={"book_flight": "flight"},
        )
        run_id = uuid4()

        # Should not raise — "flight" is in the budget
        handler.on_tool_start(
            {"name": "book_flight"},
            '{"amount": 150.0, "destination": "NYC"}',
            run_id=run_id,
        )

        assert str(run_id) in handler._pending
        # Clean up
        handler.on_tool_end("Flight booked", run_id=run_id)

    def test_on_tool_end_noop_after_denial(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        on_tool_end after a denied call (run_id never made it into pending)
        must not crash or make any API calls.
        """
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=allocated_budget.session_token,
            agent_id="live_langchain_agent",
            tool_category_map={"bad_tool": "nonexistent_category"},
        )
        run_id = uuid4()

        try:
            handler.on_tool_start({"name": "bad_tool"}, '{"amount": 10.0}', run_id=run_id)
        except ToolException:
            pass

        # Should not raise and should not call confirm
        handler.on_tool_end("output", run_id=run_id)


# ---------------------------------------------------------------------------
# FiGuardToolGuard — live tests
# ---------------------------------------------------------------------------

class TestToolGuardLive:
    """Tests that drive FiGuardToolGuard against a real figuard container."""

    def test_authorized_call_executes_and_confirms(
        self, client: FiGuardClient, flat_budget
    ):
        """
        Authorized tool call: the original _run executes and the event is CONFIRMED.
        """
        tool = _make_tool("book_item", run_result="Booked successfully")

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_tool_guard",
        )

        result = tool._run(amount=75.0, item="conference ticket")

        assert result == "Booked successfully"

        # Verify event CONFIRMED in ledger
        ledger = client.get_ledger(flat_budget.id, size=5)
        assert any(e.decision == "CONFIRMED" for e in ledger.events)

    def test_denied_call_returns_denial_string_tool_never_runs(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        When FiGuard denies, the original tool _run is never called and a
        denial string is returned to the LLM.
        """
        tool = _make_tool("buy_car_rental")
        original_run = tool._run

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=allocated_budget.session_token,
            category="car_rental",  # not in allocated_budget
            agent_id="live_tool_guard",
        )

        result = tool._run(amount=95.0)

        assert "DENIED" in result
        assert "NO_MATCHING_ALLOCATION" in result
        original_run.assert_not_called()

    def test_tool_error_fails_event_and_reraises(
        self, client: FiGuardClient, flat_budget
    ):
        """
        When the wrapped tool raises, fail_event is called and the exception
        propagates to the caller.
        """
        tool = _make_tool()

        def failing_run(**kwargs):
            raise RuntimeError("downstream payment error")

        tool._run = failing_run

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=flat_budget.session_token,
            agent_id="live_tool_guard",
        )

        with pytest.raises(RuntimeError, match="downstream payment error"):
            tool._run(amount=30.0)

        # The event should be in FAILED state in the ledger
        ledger = client.get_ledger(flat_budget.id, size=10)
        assert any(e.decision == "FAILED" for e in ledger.events)

    def test_custom_amount_key_read_correctly(
        self, client: FiGuardClient, flat_budget
    ):
        """
        A custom amount_key (e.g. "price") is read from kwargs, not "amount".
        """
        tool = _make_tool("book_flight")

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=flat_budget.session_token,
            amount_key="price",
            agent_id="live_tool_guard",
        )

        result = tool._run(price=120.0, destination="LAX")

        assert result == "success"

        ledger = client.get_ledger(flat_budget.id, size=5)
        confirmed = [e for e in ledger.events if e.decision == "CONFIRMED"]
        assert len(confirmed) >= 1

    def test_insufficient_funds_returns_denial_string(
        self, client: FiGuardClient, tiny_budget
    ):
        """
        A request that exceeds the flat budget must be denied with INSUFFICIENT_FUNDS.
        """
        tool = _make_tool("expensive_action")

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=tiny_budget.session_token,
            agent_id="live_tool_guard",
        )

        result = tool._run(amount=999.0)

        assert "DENIED" in result
        assert "INSUFFICIENT_FUNDS" in result
