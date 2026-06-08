"""
Unit tests for auto_guard_langchain and auto_guard_crewai one-liner wrappers.

All tests use a mocked FiGuardClient — no running FiGuard server required.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch
from uuid import uuid4

import pytest

from figuard.models import Budget, BudgetToken


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _mock_budget(session_token: str = "st_test_token") -> Budget:
    token = MagicMock(spec=BudgetToken)
    token.session_token = session_token
    budget = MagicMock(spec=Budget)
    budget.id = str(uuid4())
    budget.primary_token = token
    return budget


def _mock_client(session_token: str = "st_test_token") -> MagicMock:
    client = MagicMock()
    client.create_budget.return_value = _mock_budget(session_token)
    return client


class MockExecutor:
    """Minimal stand-in for a LangChain AgentExecutor."""
    def __init__(self, callbacks=None):
        self.callbacks = callbacks


# ---------------------------------------------------------------------------
# auto_guard_langchain
# ---------------------------------------------------------------------------

langchain_core = pytest.importorskip("langchain_core")

from figuard.integrations.langchain import (  # noqa: E402
    FiGuardCallbackHandler,
    auto_guard_langchain,
)


class TestAutoGuardLangchain:

    def test_returns_same_executor(self):
        client = _mock_client()
        executor = MockExecutor()
        result = auto_guard_langchain(executor, client=client)
        assert result is executor

    def test_wires_callback_handler(self):
        client = _mock_client()
        executor = MockExecutor()
        auto_guard_langchain(executor, client=client)
        assert len(executor.callbacks) == 1
        assert isinstance(executor.callbacks[0], FiGuardCallbackHandler)

    def test_appends_to_existing_callbacks(self):
        client = _mock_client()
        existing = MagicMock()
        executor = MockExecutor(callbacks=[existing])
        auto_guard_langchain(executor, client=client)
        assert len(executor.callbacks) == 2
        assert executor.callbacks[0] is existing
        assert isinstance(executor.callbacks[1], FiGuardCallbackHandler)

    def test_creates_budget_with_correct_params(self):
        client = _mock_client()
        executor = MockExecutor()
        auto_guard_langchain(executor, budget=250.0, currency="EUR", client=client)
        client.create_budget.assert_called_once()
        call_kwargs = client.create_budget.call_args.kwargs
        assert call_kwargs["total_limit"] == 250.0
        assert call_kwargs["currency"] == "EUR"
        assert call_kwargs["expires_in"] == "24h"

    def test_velocity_max_per_minute_passed_to_create_budget(self):
        client = _mock_client()
        executor = MockExecutor()
        auto_guard_langchain(executor, velocity_max_per_minute=5, client=client)
        call_kwargs = client.create_budget.call_args.kwargs
        assert call_kwargs["velocity_max_per_minute"] == 5

    def test_no_velocity_by_default(self):
        client = _mock_client()
        executor = MockExecutor()
        auto_guard_langchain(executor, client=client)
        call_kwargs = client.create_budget.call_args.kwargs
        assert call_kwargs.get("velocity_max_per_minute") is None

    def test_custom_agent_id_wired_to_handler(self):
        client = _mock_client()
        executor = MockExecutor()
        auto_guard_langchain(executor, agent_id="my_agent", client=client)
        handler = executor.callbacks[0]
        assert handler._agent_id == "my_agent"

    def test_custom_amount_param_wired_to_handler(self):
        client = _mock_client()
        executor = MockExecutor()
        auto_guard_langchain(executor, amount_param="price", client=client)
        handler = executor.callbacks[0]
        assert handler._amount_param == "price"

    def test_session_token_from_budget_wired_to_handler(self):
        client = _mock_client(session_token="st_specific_token")
        executor = MockExecutor()
        auto_guard_langchain(executor, client=client)
        handler = executor.callbacks[0]
        assert handler._session_token == "st_specific_token"

    def test_zero_config_uses_figuard_client(self):
        """auto_guard_langchain with no client uses FiGuardClient() zero-config."""
        mock_budget = _mock_budget()
        with patch("figuard.integrations.langchain.FiGuardClient") as MockClient:
            MockClient.return_value.create_budget.return_value = mock_budget
            executor = MockExecutor()
            auto_guard_langchain(executor)
            MockClient.assert_called_once_with()

    def test_top_level_import_works(self):
        """from figuard import auto_guard_langchain must not raise."""
        from figuard import auto_guard_langchain as agl  # noqa: F401
        assert callable(agl)


# ---------------------------------------------------------------------------
# auto_guard_crewai
# ---------------------------------------------------------------------------

crewai = pytest.importorskip("crewai")

from figuard.integrations.crewai import (  # noqa: E402
    FiGuardCrewGuard,
    auto_guard_crewai,
)


class MockTool:
    """Minimal stand-in for a CrewAI BaseTool."""
    name = "mock_tool"
    description = "A mock tool for testing"

    def _run(self, amount: float = 0.0, **kwargs):
        return f"ran with amount={amount}"


class TestAutoGuardCrewai:

    def test_returns_crew_guard(self):
        client = _mock_client()
        tool = MockTool()
        result = auto_guard_crewai(tool, client=client)
        assert isinstance(result, FiGuardCrewGuard)

    def test_creates_budget_with_correct_params(self):
        client = _mock_client()
        tool = MockTool()
        auto_guard_crewai(tool, budget=300.0, currency="GBP", client=client)
        call_kwargs = client.create_budget.call_args.kwargs
        assert call_kwargs["total_limit"] == 300.0
        assert call_kwargs["currency"] == "GBP"
        assert call_kwargs["expires_in"] == "24h"

    def test_velocity_max_per_minute_passed_to_create_budget(self):
        client = _mock_client()
        tool = MockTool()
        auto_guard_crewai(tool, velocity_max_per_minute=3, client=client)
        call_kwargs = client.create_budget.call_args.kwargs
        assert call_kwargs["velocity_max_per_minute"] == 3

    def test_no_velocity_by_default(self):
        client = _mock_client()
        tool = MockTool()
        auto_guard_crewai(tool, client=client)
        call_kwargs = client.create_budget.call_args.kwargs
        assert call_kwargs.get("velocity_max_per_minute") is None

    def test_custom_agent_id_wired(self):
        client = _mock_client()
        tool = MockTool()
        guard = auto_guard_crewai(tool, agent_id="crewai_researcher", client=client)
        assert guard._agent_id == "crewai_researcher"

    def test_session_token_from_budget(self):
        client = _mock_client(session_token="st_crew_token")
        tool = MockTool()
        guard = auto_guard_crewai(tool, client=client)
        assert guard._session_token == "st_crew_token"

    def test_zero_config_uses_figuard_client(self):
        mock_budget = _mock_budget()
        with patch("figuard.integrations.crewai.FiGuardClient") as MockClient:
            MockClient.return_value.create_budget.return_value = mock_budget
            tool = MockTool()
            auto_guard_crewai(tool)
            MockClient.assert_called_once_with()

    def test_top_level_import_works(self):
        """from figuard import auto_guard_crewai must not raise."""
        from figuard import auto_guard_crewai as agc  # noqa: F401
        assert callable(agc)
