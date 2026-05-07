"""
Unit tests for guarded_anthropic_tool.

All tests use a mocked FiGuardClient — no running FiGuard server or
anthropic installation required.

Key difference from OpenAI: Anthropic passes tool inputs as a parsed dict
(block.input), not a JSON string, so no json.loads step is needed before
dispatching.
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

# Skip entire module if anthropic is not installed
pytest.importorskip("anthropic")

from figuard.integrations.anthropic import guarded_anthropic_tool
from figuard.models import AuthorizationResult


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SESSION_TOKEN = "st_test_anthropic_token"


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

class TestGuardedAnthropicTool:

    def test_authorized_call_executes_and_confirms(self):
        """Simulates dispatching an Anthropic tool_use block (block.input is a dict)."""
        client = _mock_client(_authorized("evt_001", 350.0))

        @guarded_anthropic_tool(
            client=client,
            session_token=SESSION_TOKEN,
            category="hotel",
            amount_key="nightly_rate",
        )
        def book_hotel(city: str, nightly_rate: float) -> str:
            return f"Hotel in {city} booked at ${nightly_rate}/night"

        # Anthropic passes block.input directly as a dict — dispatch with **block.input
        result = book_hotel(city="Paris", nightly_rate=350.0)

        assert result == "Hotel in Paris booked at $350.0/night"
        client.authorize.assert_called_once()
        call_kwargs = client.authorize.call_args.kwargs
        assert call_kwargs["session_token"] == SESSION_TOKEN
        assert call_kwargs["claimed_category"] == "hotel"
        assert call_kwargs["requested_amount"] == 350.0
        assert call_kwargs["agent_id"] == "anthropic_agent"
        client.confirm_event.assert_called_once_with("evt_001", confirmed_amount=350.0)

    def test_denied_call_returns_denial_string(self):
        client = _mock_client(_denied("BUDGET_EXHAUSTED", "hotel allocation is empty"))

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def book_hotel(city: str, amount: float) -> str:
            raise AssertionError("should not be called when denied")

        result = book_hotel(city="London", amount=500.0)

        assert "DENIED" in result
        assert "BUDGET_EXHAUSTED" in result
        assert "hotel allocation is empty" in result
        client.confirm_event.assert_not_called()

    def test_denied_call_without_message(self):
        client = _mock_client(_denied("NO_MATCHING_ALLOCATION"))

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def book_hotel(amount: float) -> str:
            raise AssertionError("should not be called when denied")

        result = book_hotel(amount=200.0)

        assert "DENIED" in result
        assert "NO_MATCHING_ALLOCATION" in result
        assert "—" not in result

    def test_tool_error_calls_fail_event_and_reraises(self):
        client = _mock_client(_authorized("evt_004", 89.0))

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def send_wire(amount: float) -> str:
            raise RuntimeError("bank API unreachable")

        with pytest.raises(RuntimeError, match="bank API unreachable"):
            send_wire(amount=89.0)

        client.fail_event.assert_called_once()
        call_kwargs = client.fail_event.call_args.kwargs
        assert call_kwargs["event_id"] == "evt_004"
        assert call_kwargs["reason"] == "TOOL_ERROR"
        assert "bank API unreachable" in call_kwargs["error_message"]
        client.confirm_event.assert_not_called()

    def test_default_amount_key_is_amount(self):
        client = _mock_client(_authorized())

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def place_order(item: str, amount: float) -> str:
            return "ordered"

        place_order(item="laptop", amount=999.0)

        assert client.authorize.call_args.kwargs["requested_amount"] == 999.0

    def test_zero_amount_when_key_missing(self):
        client = _mock_client(_authorized())

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def search_web(query: str) -> str:
            return "results"

        search_web(query="cheap flights NYC")

        assert client.authorize.call_args.kwargs["requested_amount"] == 0.0

    def test_wraps_preserves_function_name_and_docstring(self):
        client = _mock_client(_authorized())

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def my_tool(amount: float) -> str:
            """Executes an important action."""
            return "done"

        assert my_tool.__name__ == "my_tool"
        assert my_tool.__doc__ == "Executes an important action."

    def test_no_category_by_default(self):
        client = _mock_client(_authorized())

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        buy(amount=5.0)

        assert client.authorize.call_args.kwargs["claimed_category"] is None

    def test_custom_agent_id_passed_to_authorize(self):
        client = _mock_client(_authorized())

        @guarded_anthropic_tool(
            client=client, session_token=SESSION_TOKEN, agent_id="claude_travel_agent"
        )
        def buy(amount: float) -> str:
            return "done"

        buy(amount=5.0)

        assert client.authorize.call_args.kwargs["agent_id"] == "claude_travel_agent"

    def test_confirm_not_called_when_denied(self):
        client = _mock_client(_denied())

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        buy(amount=50.0)

        client.confirm_event.assert_not_called()
        client.fail_event.assert_not_called()

    def test_confirm_failure_does_not_propagate(self):
        """confirm_event exception must not crash the tool call that already succeeded."""
        client = _mock_client(_authorized("evt_001", 100.0))
        client.confirm_event.side_effect = Exception("server error")

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        result = buy(amount=100.0)
        assert result == "done"

    def test_denied_string_format_with_message(self):
        client = _mock_client(_denied("BUDGET_EXHAUSTED", "all funds used"))

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
        def buy(amount: float) -> str:
            return "done"

        result = buy(amount=100.0)
        assert result == "FiGuard DENIED: BUDGET_EXHAUSTED — all funds used"

    def test_idempotency_key_is_uuid(self):
        import re
        client = _mock_client(_authorized())

        keys_seen = []

        def tracking_authorize(**kwargs):
            keys_seen.append(kwargs.get("idempotency_key", ""))
            return _authorized()

        client.authorize = tracking_authorize

        @guarded_anthropic_tool(client=client, session_token=SESSION_TOKEN)
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
