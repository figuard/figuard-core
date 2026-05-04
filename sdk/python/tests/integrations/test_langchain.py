"""
Unit tests for FiGuardCallbackHandler and FiGuardToolGuard.

All tests use a mocked FiGuardClient — no running FiGuard server required.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch
from uuid import uuid4

import pytest

# Skip entire module if langchain-core is not installed
langchain_core = pytest.importorskip("langchain_core")

from langchain_core.tools import ToolException

from figuard.integrations.langchain import (
    FiGuardCallbackHandler,
    FiGuardToolGuard,
    _extract_amount,
)
from figuard.models import AuthorizationResult


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SESSION_TOKEN = "st_test_session_token"
AGENT_ID = "test_agent"


def _authorized(event_id: str = "evt_001", amount: float = 100.0) -> AuthorizationResult:
    return AuthorizationResult(
        event_id=event_id,
        decision="AUTHORIZED",
        approved_amount=amount,
    )


def _denied(reason: str = "NO_MATCHING_ALLOCATION", msg: str = "") -> AuthorizationResult:
    return AuthorizationResult(
        event_id="evt_denied",
        decision="DENIED",
        denial_reason=reason,
        denial_message=msg or None,
    )


def _mock_client(authorize_result: AuthorizationResult) -> MagicMock:
    client = MagicMock()
    client.authorize.return_value = authorize_result
    return client


# ---------------------------------------------------------------------------
# _extract_amount helper
# ---------------------------------------------------------------------------

class TestExtractAmount:
    def test_extracts_from_json(self):
        assert _extract_amount('{"amount": 99.50}', "amount") == 99.50

    def test_extracts_custom_key(self):
        assert _extract_amount('{"price": 267.00, "destination": "NYC"}', "price") == 267.0

    def test_returns_zero_when_key_missing(self):
        assert _extract_amount('{"destination": "NYC"}', "amount") == 0.0

    def test_returns_zero_for_non_json(self):
        assert _extract_amount("plain string input", "amount") == 0.0

    def test_returns_zero_for_empty_string(self):
        assert _extract_amount("", "amount") == 0.0


# ---------------------------------------------------------------------------
# FiGuardCallbackHandler
# ---------------------------------------------------------------------------

class TestFiGuardCallbackHandler:

    def _make_handler(self, authorize_result: AuthorizationResult, **kwargs):
        client = _mock_client(authorize_result)
        handler = FiGuardCallbackHandler(
            client=client,
            session_token=SESSION_TOKEN,
            agent_id=AGENT_ID,
            **kwargs,
        )
        return handler, client

    def test_on_tool_start_authorized_stores_pending(self):
        handler, client = self._make_handler(_authorized("evt_001", 100.0))
        run_id = uuid4()

        handler.on_tool_start(
            {"name": "book_flight"},
            '{"amount": 100.0, "destination": "NYC"}',
            run_id=run_id,
        )

        client.authorize.assert_called_once()
        call_kwargs = client.authorize.call_args.kwargs
        assert call_kwargs["session_token"] == SESSION_TOKEN
        assert call_kwargs["agent_id"] == AGENT_ID
        assert call_kwargs["requested_amount"] == 100.0
        assert call_kwargs["idempotency_key"] == str(run_id)
        assert str(run_id) in handler._pending

    def test_on_tool_start_denied_raises_tool_exception(self):
        handler, client = self._make_handler(
            _denied("NO_MATCHING_ALLOCATION", "flight is not in this budget")
        )
        run_id = uuid4()

        with pytest.raises(ToolException) as exc_info:
            handler.on_tool_start(
                {"name": "book_insurance"},
                '{"amount": 85.0}',
                run_id=run_id,
            )

        assert "NO_MATCHING_ALLOCATION" in str(exc_info.value)
        assert "flight is not in this budget" in str(exc_info.value)
        # Denied — nothing should be pending
        assert str(run_id) not in handler._pending

    def test_on_tool_end_confirms_and_clears_pending(self):
        handler, client = self._make_handler(_authorized("evt_001", 267.0))
        run_id = uuid4()

        handler.on_tool_start(
            {"name": "book_flight"},
            '{"amount": 267.0}',
            run_id=run_id,
        )
        handler.on_tool_end("Flight booked successfully", run_id=run_id)

        client.confirm_event.assert_called_once_with("evt_001", confirmed_amount=267.0)
        assert str(run_id) not in handler._pending

    def test_on_tool_error_fails_and_clears_pending(self):
        handler, client = self._make_handler(_authorized("evt_001", 100.0))
        run_id = uuid4()

        handler.on_tool_start(
            {"name": "book_flight"},
            '{"amount": 100.0}',
            run_id=run_id,
        )
        handler.on_tool_error(RuntimeError("payment gateway timeout"), run_id=run_id)

        client.fail_event.assert_called_once()
        call_kwargs = client.fail_event.call_args.kwargs
        assert call_kwargs["event_id"] == "evt_001"
        assert call_kwargs["reason"] == "TOOL_ERROR"
        assert "payment gateway timeout" in call_kwargs["error_message"]
        assert str(run_id) not in handler._pending

    def test_ignored_tool_skips_authorization(self):
        handler, client = self._make_handler(
            _authorized(), ignore_tools={"search_web"}
        )
        run_id = uuid4()

        handler.on_tool_start(
            {"name": "search_web"},
            '{"query": "flights to NYC"}',
            run_id=run_id,
        )

        client.authorize.assert_not_called()
        assert str(run_id) not in handler._pending

    def test_tool_category_map_passed_to_authorize(self):
        handler, client = self._make_handler(
            _authorized(),
            tool_category_map={"book_flight": "flight"},
        )
        run_id = uuid4()

        handler.on_tool_start(
            {"name": "book_flight"},
            '{"amount": 267.0}',
            run_id=run_id,
        )

        call_kwargs = client.authorize.call_args.kwargs
        assert call_kwargs["claimed_category"] == "flight"

    def test_on_tool_end_noop_when_tool_was_denied(self):
        """on_tool_end after a denied call (run_id not in pending) should not crash."""
        handler, client = self._make_handler(_denied())
        run_id = uuid4()

        # Swallow the ToolException from denial
        try:
            handler.on_tool_start({"name": "buy"}, '{"amount": 50.0}', run_id=run_id)
        except ToolException:
            pass

        handler.on_tool_end("some output", run_id=run_id)  # should not raise
        client.confirm_event.assert_not_called()

    def test_on_tool_error_noop_when_tool_was_denied(self):
        """on_tool_error after a denied call should not crash or double-fail."""
        handler, client = self._make_handler(_denied())
        run_id = uuid4()

        try:
            handler.on_tool_start({"name": "buy"}, '{"amount": 50.0}', run_id=run_id)
        except ToolException:
            pass

        handler.on_tool_error(RuntimeError("noop"), run_id=run_id)
        client.fail_event.assert_not_called()

    def test_raise_error_is_true(self):
        """Ensure raise_error=True so ToolException propagates through LangChain."""
        assert FiGuardCallbackHandler.raise_error is True


# ---------------------------------------------------------------------------
# FiGuardToolGuard
# ---------------------------------------------------------------------------

class TestFiGuardToolGuard:

    def _make_tool(self, run_fn=None):
        """Create a minimal BaseTool-like mock."""
        tool = MagicMock(spec=["name", "_run", "description"])
        tool.name = "buy_item"
        tool.description = "Buy an item"
        if run_fn:
            tool._run = run_fn
        else:
            tool._run = MagicMock(return_value="Item purchased successfully")
        return tool

    def test_authorized_call_executes_tool_and_confirms(self):
        client = _mock_client(_authorized("evt_001", 50.0))
        tool = self._make_tool()

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=SESSION_TOKEN,
            category="purchase",
            amount_key="price",
        )

        result = tool._run(item="book", price=50.0)

        assert result == "Item purchased successfully"
        client.authorize.assert_called_once()
        assert client.authorize.call_args.kwargs["claimed_category"] == "purchase"
        assert client.authorize.call_args.kwargs["requested_amount"] == 50.0
        client.confirm_event.assert_called_once_with("evt_001", confirmed_amount=50.0)

    def test_denied_call_returns_denial_string(self):
        client = _mock_client(_denied("INSUFFICIENT_FUNDS"))
        tool = self._make_tool()
        original_run = tool._run

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=SESSION_TOKEN,
        )

        result = tool._run(amount=999.0)

        assert "DENIED" in result
        assert "INSUFFICIENT_FUNDS" in result
        original_run.assert_not_called()
        client.confirm_event.assert_not_called()

    def test_tool_error_calls_fail_event_and_reraises(self):
        client = _mock_client(_authorized("evt_001", 100.0))

        def failing_run(**kwargs):
            raise RuntimeError("gateway timeout")

        tool = self._make_tool(run_fn=failing_run)

        FiGuardToolGuard(
            tool=tool,
            client=client,
            session_token=SESSION_TOKEN,
        )

        with pytest.raises(RuntimeError, match="gateway timeout"):
            tool._run(amount=100.0)

        client.fail_event.assert_called_once()
        assert client.fail_event.call_args.kwargs["event_id"] == "evt_001"
        assert "gateway timeout" in client.fail_event.call_args.kwargs["error_message"]

    def test_default_amount_key_is_amount(self):
        client = _mock_client(_authorized())
        tool = self._make_tool()

        FiGuardToolGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        tool._run(amount=75.0)

        assert client.authorize.call_args.kwargs["requested_amount"] == 75.0

    def test_zero_amount_when_key_missing(self):
        client = _mock_client(_authorized())
        tool = self._make_tool()

        FiGuardToolGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        tool._run(vendor="acme")  # no amount kwarg

        assert client.authorize.call_args.kwargs["requested_amount"] == 0.0
