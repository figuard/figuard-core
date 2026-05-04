"""
Unit tests for guarded_openai_function.

All tests use a mocked FiGuardClient — no running FiGuard server or
openai installation required.
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

# Skip entire module if openai is not installed
pytest.importorskip("openai")

from figuard.integrations.openai import guarded_openai_function
from figuard.models import AuthorizationResult


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SESSION_TOKEN = "st_test_openai_token"


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

class TestGuardedOpenAIFunction:

    def test_authorized_call_executes_and_confirms(self):
        """Simulates dispatching a parsed OpenAI tool_call with kwargs."""
        client = _mock_client(_authorized("evt_001", 299.0))

        @guarded_openai_function(
            client=client,
            session_token=SESSION_TOKEN,
            category="flight",
            amount_key="price",
        )
        def book_flight(destination: str, price: float) -> str:
            return f"Flight to {destination} booked for ${price}"

        # OpenAI passes arguments as a parsed dict (after json.loads)
        result = book_flight(destination="NYC", price=299.0)

        assert result == "Flight to NYC booked for $299.0"
        client.authorize.assert_called_once()
        call_kwargs = client.authorize.call_args.kwargs
        assert call_kwargs["session_token"] == SESSION_TOKEN
        assert call_kwargs["claimed_category"] == "flight"
        assert call_kwargs["requested_amount"] == 299.0
        assert call_kwargs["agent_id"] == "openai_agent"
        client.confirm_event.assert_called_once_with("evt_001", confirmed_amount=299.0)

    def test_denied_call_returns_denial_string(self):
        client = _mock_client(_denied("INSUFFICIENT_FUNDS", "flight budget exhausted"))

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def book_flight(destination: str, amount: float) -> str:
            raise AssertionError("should not be called when denied")

        result = book_flight(destination="LAX", amount=450.0)

        assert "DENIED" in result
        assert "INSUFFICIENT_FUNDS" in result
        assert "flight budget exhausted" in result
        client.confirm_event.assert_not_called()

    def test_denied_call_without_message(self):
        client = _mock_client(_denied("NO_MATCHING_ALLOCATION"))

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def book_flight(amount: float) -> str:
            raise AssertionError("should not be called when denied")

        result = book_flight(amount=200.0)

        assert "DENIED" in result
        assert "NO_MATCHING_ALLOCATION" in result
        assert "—" not in result

    def test_tool_error_calls_fail_event_and_reraises(self):
        client = _mock_client(_authorized("evt_003", 120.0))

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def reserve_car(amount: float) -> str:
            raise RuntimeError("car reservation service down")

        with pytest.raises(RuntimeError, match="car reservation service down"):
            reserve_car(amount=120.0)

        client.fail_event.assert_called_once()
        call_kwargs = client.fail_event.call_args.kwargs
        assert call_kwargs["event_id"] == "evt_003"
        assert call_kwargs["reason"] == "TOOL_ERROR"
        assert "car reservation service down" in call_kwargs["error_message"]
        client.confirm_event.assert_not_called()

    def test_default_amount_key_is_amount(self):
        client = _mock_client(_authorized())

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def buy_item(name: str, amount: float) -> str:
            return "bought"

        buy_item(name="book", amount=12.99)

        assert client.authorize.call_args.kwargs["requested_amount"] == 12.99

    def test_zero_amount_when_key_missing(self):
        client = _mock_client(_authorized())

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def send_email(to: str, subject: str) -> str:
            return "sent"

        send_email(to="alice@example.com", subject="hello")

        assert client.authorize.call_args.kwargs["requested_amount"] == 0.0

    def test_wraps_preserves_function_name_and_docstring(self):
        client = _mock_client(_authorized())

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def my_tool(amount: float) -> str:
            """Books something important."""
            return "done"

        assert my_tool.__name__ == "my_tool"
        assert my_tool.__doc__ == "Books something important."

    def test_no_category_by_default(self):
        client = _mock_client(_authorized())

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        buy(amount=5.0)

        assert client.authorize.call_args.kwargs["claimed_category"] is None

    def test_custom_agent_id_passed_to_authorize(self):
        client = _mock_client(_authorized())

        @guarded_openai_function(
            client=client, session_token=SESSION_TOKEN, agent_id="gpt4o_travel_agent"
        )
        def buy(amount: float) -> str:
            return "done"

        buy(amount=5.0)

        assert client.authorize.call_args.kwargs["agent_id"] == "gpt4o_travel_agent"

    def test_confirm_not_called_when_denied(self):
        client = _mock_client(_denied())

        @guarded_openai_function(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        buy(amount=50.0)

        client.confirm_event.assert_not_called()
        client.fail_event.assert_not_called()
