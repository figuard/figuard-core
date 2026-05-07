"""
Unit tests for guarded_function_tool (OpenAI Agents SDK integration).

All tests use a mocked FiGuardClient — no running FiGuard server or
openai-agents installation required.
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

# Skip entire module if openai-agents is not installed
pytest.importorskip("agents")

from figuard.integrations.openai_agents import guarded_function_tool
from figuard.models import AuthorizationResult


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SESSION_TOKEN = "st_test_openai_agents_token"


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
# Tests
# ---------------------------------------------------------------------------

class TestGuardedFunctionTool:

    def test_authorized_call_executes_and_confirms(self):
        client = _mock_client(_authorized("evt_001", 99.0))

        @guarded_function_tool(
            client=client,
            session_token=SESSION_TOKEN,
            category="travel",
            amount_key="price",
        )
        def book_hotel(city: str, price: float) -> str:
            return f"Hotel in {city} booked for ${price}"

        result = book_hotel(city="NYC", price=99.0)

        assert result == "Hotel in NYC booked for $99.0"
        client.authorize.assert_called_once()
        call_kwargs = client.authorize.call_args.kwargs
        assert call_kwargs["session_token"] == SESSION_TOKEN
        assert call_kwargs["claimed_category"] == "travel"
        assert call_kwargs["requested_amount"] == 99.0
        client.confirm_event.assert_called_once_with("evt_001", confirmed_amount=99.0)

    def test_denied_call_returns_denial_string(self):
        client = _mock_client(_denied("INSUFFICIENT_FUNDS", "over hotel budget"))

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def book_hotel(city: str, amount: float) -> str:
            raise AssertionError("should not be called when denied")

        result = book_hotel(city="Paris", amount=500.0)

        assert "DENIED" in result
        assert "INSUFFICIENT_FUNDS" in result
        assert "over hotel budget" in result
        client.confirm_event.assert_not_called()

    def test_denied_call_without_message(self):
        client = _mock_client(_denied("NO_MATCHING_ALLOCATION"))

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def book_flight(amount: float) -> str:
            raise AssertionError("should not be called when denied")

        result = book_flight(amount=200.0)

        assert "DENIED" in result
        assert "NO_MATCHING_ALLOCATION" in result
        assert "—" not in result

    def test_tool_error_calls_fail_event_and_reraises(self):
        client = _mock_client(_authorized("evt_002", 150.0))

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def book_rental(amount: float) -> str:
            raise RuntimeError("rental service unavailable")

        with pytest.raises(RuntimeError, match="rental service unavailable"):
            book_rental(amount=150.0)

        client.fail_event.assert_called_once()
        call_kwargs = client.fail_event.call_args.kwargs
        assert call_kwargs["event_id"] == "evt_002"
        assert call_kwargs["reason"] == "TOOL_ERROR"
        assert "rental service unavailable" in call_kwargs["error_message"]
        client.confirm_event.assert_not_called()

    def test_default_amount_key_is_amount(self):
        client = _mock_client(_authorized())

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def buy_item(name: str, amount: float) -> str:
            return "bought"

        buy_item(name="pen", amount=3.50)

        assert client.authorize.call_args.kwargs["requested_amount"] == 3.50

    def test_zero_amount_when_key_missing(self):
        client = _mock_client(_authorized())

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def send_message(recipient: str, body: str) -> str:
            return "sent"

        send_message(recipient="alice", body="hello")

        assert client.authorize.call_args.kwargs["requested_amount"] == 0.0

    def test_wraps_preserves_function_name_and_docstring(self):
        client = _mock_client(_authorized())

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def my_custom_tool(amount: float) -> str:
            """Performs a custom operation."""
            return "done"

        assert my_custom_tool.__name__ == "my_custom_tool"
        assert my_custom_tool.__doc__ == "Performs a custom operation."

    def test_no_category_by_default(self):
        client = _mock_client(_authorized())

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        buy(amount=10.0)

        assert client.authorize.call_args.kwargs["claimed_category"] is None

    def test_custom_agent_id_passed_to_authorize(self):
        client = _mock_client(_authorized())

        @guarded_function_tool(
            client=client, session_token=SESSION_TOKEN, agent_id="my_travel_agent"
        )
        def buy(amount: float) -> str:
            return "done"

        buy(amount=10.0)

        assert client.authorize.call_args.kwargs["agent_id"] == "my_travel_agent"

    def test_confirm_not_called_when_denied(self):
        client = _mock_client(_denied())

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        buy(amount=50.0)

        client.confirm_event.assert_not_called()
        client.fail_event.assert_not_called()

    def test_confirm_failure_does_not_propagate(self):
        """confirm_event exception must not crash the tool call that already succeeded."""
        client = _mock_client(_authorized("evt_001", 100.0))
        client.confirm_event.side_effect = Exception("network error")

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        result = buy(amount=100.0)
        assert result == "done"

    def test_denied_string_format_with_message(self):
        client = _mock_client(_denied("BUDGET_PAUSED", "resume budget first"))

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        result = buy(amount=100.0)
        assert result == "FiGuard DENIED: BUDGET_PAUSED — resume budget first"

    def test_idempotency_key_is_uuid(self):
        import re
        keys_seen = []

        def tracking_authorize(**kwargs):
            keys_seen.append(kwargs.get("idempotency_key", ""))
            return _authorized()

        client = _mock_client(_authorized())
        client.authorize = tracking_authorize

        @guarded_function_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        buy(amount=10.0)
        buy(amount=20.0)

        assert len(keys_seen) == 2
        assert keys_seen[0] != keys_seen[1]
        for key in keys_seen:
            assert re.match(
                r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                key,
            )
