"""
Live integration tests for AsyncFiGuardClient.

These tests exercise AsyncFiGuardClient against a real running figuard-core
container, validating that the aiohttp-based HTTP layer works end-to-end
and that concurrency is handled correctly.

Run:
    make run                   # start figuard-core container
    pytest tests/live/test_async_live.py -v
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

pytest.importorskip("aiohttp", reason="aiohttp not installed — pip install figuard[async]")

from figuard import AsyncFiGuardClient, FiGuardClient
from figuard.models import AuthorizationResult, Budget, LedgerPage

FIGUARD_URL = "http://localhost:8080"


def _expires_at() -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def api_key_val(api_key) -> str:
    return api_key


@pytest.fixture
def figuard_url_val(figuard_url) -> str:
    return figuard_url


# ---------------------------------------------------------------------------
# Basic CRUD lifecycle
# ---------------------------------------------------------------------------

class TestAsyncClientLive:

    @pytest.mark.asyncio
    async def test_create_budget_returns_session_token(
        self, figuard_url, api_key
    ):
        """
        create_budget returns a Budget with a non-None session_token.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=200.00,
                expires_at=_expires_at(),
            )

        assert isinstance(budget, Budget)
        assert budget.id
        assert budget.session_token is not None
        assert budget.session_token.startswith("st_")
        assert budget.status == "ACTIVE"
        assert budget.available_quantity == 200.00

    @pytest.mark.asyncio
    async def test_get_budget_has_no_session_token(
        self, figuard_url, api_key
    ):
        """
        get_budget must not return session_token (security requirement).
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=100.00,
                expires_at=_expires_at(),
            )
            fetched = await client.get_budget(budget.id)

        assert fetched.id == budget.id
        assert fetched.session_token is None

    @pytest.mark.asyncio
    async def test_authorize_and_confirm(
        self, figuard_url, api_key
    ):
        """
        Full async authorize → confirm lifecycle. The event appears as CONFIRMED
        in the ledger.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=500.00,
                expires_at=_expires_at(),
            )

            result = await client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="async confirm test",
                requested_quantity=75.00,
                idempotency_key=str(uuid4()),
            )

            assert result.is_authorized is True
            assert result.event_id

            event = await client.confirm_event(result.event_id, confirmed_quantity=75.00)

        assert event.decision == "CONFIRMED"
        assert event.confirmed_quantity == 75.00

    @pytest.mark.asyncio
    async def test_authorize_denied_insufficient_funds(
        self, figuard_url, api_key
    ):
        """
        A request exceeding the budget is denied with INSUFFICIENT_FUNDS.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=10.00,
                expires_at=_expires_at(),
            )

            result = await client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="over-limit test",
                requested_quantity=999.00,
                idempotency_key=str(uuid4()),
            )

        assert result.is_authorized is False
        assert result.denial_reason == "INSUFFICIENT_FUNDS"

    @pytest.mark.asyncio
    async def test_fail_event_releases_reservation(
        self, figuard_url, api_key
    ):
        """
        After fail_event, the reserved amount is released back to the budget.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=300.00,
                expires_at=_expires_at(),
            )

            result = await client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="fail release test",
                requested_quantity=100.00,
                idempotency_key=str(uuid4()),
            )
            assert result.is_authorized is True

            budget_after_auth = await client.get_budget(budget.id)
            assert budget_after_auth.quantity_reserved >= 100.00

            event = await client.fail_event(result.event_id, reason="PAYMENT_DECLINED")
            assert event.decision == "FAILED"

            budget_after_fail = await client.get_budget(budget.id)
            assert budget_after_fail.quantity_reserved < budget_after_auth.quantity_reserved

    @pytest.mark.asyncio
    async def test_void_event_releases_reservation(
        self, figuard_url, api_key
    ):
        """
        After void_event, the reserved amount is released back to the budget.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=300.00,
                expires_at=_expires_at(),
            )

            result = await client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="void test",
                requested_quantity=80.00,
                idempotency_key=str(uuid4()),
            )
            assert result.is_authorized is True

            void_result = await client.void_event(result.event_id, reason="USER_CANCELLED")
            assert void_result.is_voided is True

            budget_after_void = await client.get_budget(budget.id)
            assert budget_after_void.quantity_reserved == 0.0

    @pytest.mark.asyncio
    async def test_get_ledger(
        self, figuard_url, api_key
    ):
        """
        get_ledger returns a paginated list of events.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=200.00,
                expires_at=_expires_at(),
            )

            auth = await client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="ledger test",
                requested_quantity=25.00,
                idempotency_key=str(uuid4()),
            )
            await client.confirm_event(auth.event_id, confirmed_quantity=25.00)

            page = await client.get_ledger(budget.id, size=20)

        assert isinstance(page, LedgerPage)
        assert page.total_elements >= 1
        assert any(e.id == auth.event_id for e in page.events)

    @pytest.mark.asyncio
    async def test_get_spend_tree(
        self, figuard_url, api_key
    ):
        """
        get_spend_tree returns a SpendTree with the confirmed event as a root node.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=200.00,
                expires_at=_expires_at(),
            )

            auth = await client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="tree test",
                requested_quantity=30.00,
                idempotency_key=str(uuid4()),
            )
            await client.confirm_event(auth.event_id, confirmed_quantity=30.00)

            tree = await client.get_spend_tree(budget.id)

        assert tree.budget_id == budget.id
        assert tree.total_events >= 1
        event_ids = [node.event.id for node in tree.roots]
        assert auth.event_id in event_ids

    @pytest.mark.asyncio
    async def test_rotate_session_token(
        self, figuard_url, api_key
    ):
        """
        rotate_session_token returns a new token that can authorize.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=200.00,
                expires_at=_expires_at(),
            )

            new_token = await client.rotate_session_token(budget.id)
            assert new_token.startswith("st_")
            assert new_token != budget.session_token

            result = await client.authorize(
                session_token=new_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="post-rotation auth",
                requested_quantity=20.00,
                idempotency_key=str(uuid4()),
            )

        assert result.is_authorized is True


# ---------------------------------------------------------------------------
# Concurrency tests — the core async value proposition
# ---------------------------------------------------------------------------

class TestAsyncConcurrencyLive:

    @pytest.mark.asyncio
    async def test_concurrent_authorizations_within_budget(
        self, figuard_url, api_key
    ):
        """
        Multiple async agents authorize concurrently against the same budget.
        Total requested must be within the budget limit.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=600.00,
                expires_at=_expires_at(),
            )

            # Three 100-dollar requests — all should be authorized concurrently
            results = await asyncio.gather(*[
                client.authorize(
                    session_token=budget.session_token,
                    agent_id=f"async_agent_{i}",
                    action_type="TOOL_CALL",
                    description=f"concurrent request {i}",
                    requested_quantity=100.00,
                    idempotency_key=str(uuid4()),
                )
                for i in range(3)
            ])

        authorized = [r for r in results if r.is_authorized]
        assert len(authorized) == 3

    @pytest.mark.asyncio
    async def test_concurrent_authorizations_budget_overrun(
        self, figuard_url, api_key
    ):
        """
        When concurrent requests exceed the budget, some are denied.
        The server's optimistic locking ensures the total never exceeds the limit.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=150.00,  # only 1.5 slots of 100
                expires_at=_expires_at(),
            )

            # Five 100-dollar requests against a 150-dollar budget
            # At most 1 can succeed, but server may authorize 1-2 depending on locking
            results = await asyncio.gather(*[
                client.authorize(
                    session_token=budget.session_token,
                    agent_id=f"async_agent_{i}",
                    action_type="TOOL_CALL",
                    description=f"overrun request {i}",
                    requested_quantity=100.00,
                    idempotency_key=str(uuid4()),
                )
                for i in range(5)
            ])

        authorized = [r for r in results if r.is_authorized]
        denied = [r for r in results if not r.is_authorized]

        # At least one must be denied — budget is $150 and we asked for $500 total
        assert len(denied) >= 1
        # Total reserved must not exceed the limit
        total_reserved = len(authorized) * 100.00
        assert total_reserved <= 150.00 + 0.01  # small float tolerance

    @pytest.mark.asyncio
    async def test_concurrent_confirm_and_authorize(
        self, figuard_url, api_key
    ):
        """
        Confirming one event and authorizing another concurrently must both
        complete correctly without race conditions.
        """
        async with AsyncFiGuardClient(api_key=api_key, base_url=figuard_url) as client:
            budget = await client.create_budget(
                user_id="async_live_user",
                total_limit=400.00,
                expires_at=_expires_at(),
            )

            # Pre-authorize one event
            pre_auth = await client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="pre-authorized",
                requested_quantity=50.00,
                idempotency_key=str(uuid4()),
            )
            assert pre_auth.is_authorized

            # Confirm the first and authorize a second concurrently
            confirm_coro = client.confirm_event(pre_auth.event_id, confirmed_quantity=50.00)
            auth_coro = client.authorize(
                session_token=budget.session_token,
                agent_id="async_live_agent",
                action_type="TOOL_CALL",
                description="concurrent with confirm",
                requested_quantity=75.00,
                idempotency_key=str(uuid4()),
            )

            confirm_event, new_auth = await asyncio.gather(confirm_coro, auth_coro)

        assert confirm_event.decision == "CONFIRMED"
        assert new_auth.is_authorized is True
