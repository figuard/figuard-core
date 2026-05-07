"""
Unit tests for FiGuardCrewGuard.

All tests use a mocked FiGuardClient and a mocked BaseTool — no running
FiGuard server or CrewAI installation required.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

# Skip entire module if crewai is not installed
pytest.importorskip("crewai")

from figuard.integrations.crewai import FiGuardCrewGuard
from figuard.models import AuthorizationResult


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SESSION_TOKEN = "st_test_crewai_token"
AGENT_ID = "test_crewai_agent"


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


def _make_tool(run_fn=None):
    """Create a minimal BaseTool-like mock."""
    tool = MagicMock(spec=["name", "_run", "description"])
    tool.name = "buy_item"
    tool.description = "Purchase an item"
    if run_fn:
        tool._run = run_fn
    else:
        tool._run = MagicMock(return_value="Item purchased successfully")
    return tool


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestFiGuardCrewGuard:

    def test_authorized_call_executes_tool_and_confirms(self):
        client = _mock_client(_authorized("evt_001", 75.0))
        tool = _make_tool()
        original_run = tool._run

        FiGuardCrewGuard(
            tool=tool,
            client=client,
            session_token=SESSION_TOKEN,
            category="supplies",
            amount_key="cost",
        )

        result = tool._run(item="notebook", cost=75.0)

        assert result == "Item purchased successfully"
        client.authorize.assert_called_once()
        call_kwargs = client.authorize.call_args.kwargs
        assert call_kwargs["session_token"] == SESSION_TOKEN
        assert call_kwargs["claimed_category"] == "supplies"
        assert call_kwargs["requested_amount"] == 75.0
        client.confirm_event.assert_called_once_with("evt_001", confirmed_amount=75.0)

    def test_denied_call_returns_denial_string(self):
        client = _mock_client(_denied("INSUFFICIENT_FUNDS", "no remaining budget"))
        tool = _make_tool()
        original_run = tool._run

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)

        result = tool._run(amount=500.0)

        assert "DENIED" in result
        assert "INSUFFICIENT_FUNDS" in result
        assert "no remaining budget" in result
        original_run.assert_not_called()
        client.confirm_event.assert_not_called()

    def test_denied_call_without_message(self):
        client = _mock_client(_denied("NO_MATCHING_ALLOCATION"))
        tool = _make_tool()

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)

        result = tool._run(amount=100.0)

        assert "DENIED" in result
        assert "NO_MATCHING_ALLOCATION" in result
        assert "—" not in result  # no separator when there's no message

    def test_tool_error_calls_fail_event_and_reraises(self):
        client = _mock_client(_authorized("evt_002", 200.0))

        def failing_run(**kwargs):
            raise RuntimeError("payment gateway timeout")

        tool = _make_tool(run_fn=failing_run)

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)

        with pytest.raises(RuntimeError, match="payment gateway timeout"):
            tool._run(amount=200.0)

        client.fail_event.assert_called_once()
        call_kwargs = client.fail_event.call_args.kwargs
        assert call_kwargs["event_id"] == "evt_002"
        assert call_kwargs["reason"] == "TOOL_ERROR"
        assert "payment gateway timeout" in call_kwargs["error_message"]
        client.confirm_event.assert_not_called()

    def test_default_amount_key_is_amount(self):
        client = _mock_client(_authorized())
        tool = _make_tool()

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        tool._run(amount=42.0)

        assert client.authorize.call_args.kwargs["requested_amount"] == 42.0

    def test_zero_amount_when_key_missing(self):
        client = _mock_client(_authorized())
        tool = _make_tool()

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        tool._run(vendor="acme")  # no amount kwarg

        assert client.authorize.call_args.kwargs["requested_amount"] == 0.0

    def test_no_category_by_default(self):
        client = _mock_client(_authorized())
        tool = _make_tool()

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        tool._run(amount=10.0)

        assert client.authorize.call_args.kwargs["claimed_category"] is None

    def test_custom_agent_id_passed_to_authorize(self):
        client = _mock_client(_authorized())
        tool = _make_tool()

        FiGuardCrewGuard(
            tool=tool, client=client,
            session_token=SESSION_TOKEN, agent_id="custom_crewai_agent"
        )
        tool._run(amount=10.0)

        assert client.authorize.call_args.kwargs["agent_id"] == "custom_crewai_agent"

    def test_confirm_not_called_when_denied(self):
        client = _mock_client(_denied())
        tool = _make_tool()

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        tool._run(amount=50.0)

        client.confirm_event.assert_not_called()
        client.fail_event.assert_not_called()

    def test_confirm_failure_does_not_propagate(self):
        """confirm_event exception (network error) must not crash the tool call."""
        client = _mock_client(_authorized("evt_001", 100.0))
        client.confirm_event.side_effect = Exception("network timeout")
        tool = _make_tool()

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)

        result = tool._run(amount=100.0)
        assert result == "Item purchased successfully"

    def test_denied_string_format_with_message(self):
        client = _mock_client(_denied("NO_MATCHING_ALLOCATION", "category not in budget"))
        tool = _make_tool()

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        result = tool._run(amount=100.0)

        assert result == "FiGuard DENIED: NO_MATCHING_ALLOCATION — category not in budget"

    def test_idempotency_key_sent_as_uuid(self):
        """Each guarded call must generate a unique idempotency_key."""
        import re
        client = _mock_client(_authorized())
        tool = _make_tool()
        call_count = [0]

        original_authorize = client.authorize

        def tracking_authorize(**kwargs):
            call_count[0] += 1
            key = kwargs.get("idempotency_key", "")
            # Must be a UUID4 string
            assert re.match(
                r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                key,
            ), f"idempotency_key is not a UUID4: {key!r}"
            return _authorized()

        client.authorize = tracking_authorize

        FiGuardCrewGuard(tool=tool, client=client, session_token=SESSION_TOKEN)
        tool._run(amount=10.0)

        assert call_count[0] == 1
