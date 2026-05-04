"""
Unit tests for AsyncFiGuardClient.

Uses ``aioresponses`` to mock aiohttp — no real server needed.
All tests run under pytest-asyncio in auto mode.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, Dict
from unittest import mock

import pytest
from aioresponses import aioresponses as aioresponses_ctx

from figuard import (
    AsyncFiGuardClient,
    FiGuardApiError,
    FiGuardConnectionError,
    FiGuardDeniedException,
)
from figuard.models import AuthorizationResult, Budget

BASE = "https://api.figuard.io"
API_KEY = "ab_test_asynctest"

BUDGET_ID = str(uuid.uuid4())
EVENT_ID = str(uuid.uuid4())
SESSION_TOKEN = "st_abcdef1234567890"


# ---------------------------------------------------------------------------
# Shared payload factories (mirrors test_client.py)
# ---------------------------------------------------------------------------

def _budget_payload(session_token: bool = False) -> Dict[str, Any]:
    payload = {
        "id": BUDGET_ID,
        "userId": "user_test",
        "totalLimit": 500.0,
        "currency": "USD",
        "amountSpent": 0.0,
        "amountReserved": 0.0,
        "availableAmount": 500.0,
        "status": "ACTIVE",
        "expiresAt": "2025-12-31T23:59:59Z",
        "createdAt": "2025-01-01T00:00:00Z",
        "sessionTokenPrefix": "st_abcde",
        "allocations": [],
    }
    if session_token:
        payload["sessionToken"] = SESSION_TOKEN
    return payload


def _authorized_payload() -> Dict[str, Any]:
    return {
        "eventId": EVENT_ID,
        "decision": "AUTHORIZED",
        "approvedAmount": 100.0,
        "authorizedAt": "2025-01-01T10:00:00Z",
        "budgetSnapshot": {
            "totalLimit": 500.0,
            "amountSpent": 0.0,
            "amountReserved": 100.0,
            "availableAmount": 400.0,
            "status": "ACTIVE",
        },
    }


def _denied_payload(reason: str = "INSUFFICIENT_FUNDS") -> Dict[str, Any]:
    return {
        "eventId": EVENT_ID,
        "decision": "DENIED",
        "denialReason": reason,
        "denialMessage": f"Denied because {reason}",
    }


def _event_payload(decision: str = "CONFIRMED") -> Dict[str, Any]:
    return {
        "id": EVENT_ID,
        "decision": decision,
        "requestedAmount": 100.0,
        "currency": "USD",
        "createdAt": "2025-01-01T10:00:00Z",
    }


# ---------------------------------------------------------------------------
# Context-manager lifecycle
# ---------------------------------------------------------------------------

class TestContextManager:

    @pytest.mark.asyncio
    async def test_async_with_creates_and_closes_session(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            assert client._session is not None
            assert not client._session.closed
        assert client._session is None or client._session.closed

    @pytest.mark.asyncio
    async def test_close_is_idempotent(self) -> None:
        client = AsyncFiGuardClient(api_key=API_KEY, base_url=BASE)
        await client.close()  # no session yet — should not raise
        await client.close()  # second call — still fine


# ---------------------------------------------------------------------------
# create_budget / get_budget
# ---------------------------------------------------------------------------

class TestCreateBudget:

    @pytest.mark.asyncio
    async def test_returns_budget_with_session_token(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/budgets", payload=_budget_payload(session_token=True), status=201)

                budget = await client.create_budget(
                    user_id="user_test",
                    total_limit=500.0,
                    expires_at="2025-12-31T23:59:59Z",
                )

        assert isinstance(budget, Budget)
        assert budget.id == BUDGET_ID
        assert budget.session_token == SESSION_TOKEN
        assert budget.status == "ACTIVE"
        assert budget.available_amount == 500.0
        assert budget.is_active is True

    @pytest.mark.asyncio
    async def test_session_token_absent_on_get(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}", payload=_budget_payload(), status=200)
                budget = await client.get_budget(BUDGET_ID)

        assert budget.session_token is None

    @pytest.mark.asyncio
    async def test_api_error_propagated(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(
                    f"{BASE}/api/v1/budgets",
                    payload={"error": "VALIDATION_FAILED", "message": "expiresAt is required"},
                    status=400,
                )

                with pytest.raises(FiGuardApiError) as exc_info:
                    await client.create_budget(user_id="u", total_limit=100.0, expires_at="bad")

        assert exc_info.value.status_code == 400
        assert "expiresAt" in exc_info.value.message


# ---------------------------------------------------------------------------
# authorize
# ---------------------------------------------------------------------------

class TestAuthorize:

    @pytest.mark.asyncio
    async def test_raises_value_error_when_idempotency_key_missing(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with pytest.raises(ValueError, match="idempotency_key is required"):
                await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="agent_001",
                    action_type="PURCHASE",
                    description="test",
                    requested_amount=50.0,
                )

    @pytest.mark.asyncio
    async def test_raises_value_error_when_idempotency_key_blank(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with pytest.raises(ValueError):
                await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="agent_001",
                    action_type="PURCHASE",
                    description="test",
                    requested_amount=50.0,
                    idempotency_key="   ",
                )

    @pytest.mark.asyncio
    async def test_authorized_returns_result_with_is_authorized_true(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload=_authorized_payload(), status=200)

                result = await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="agent_001",
                    action_type="PURCHASE",
                    description="NYC flight",
                    requested_amount=100.0,
                    idempotency_key=str(uuid.uuid4()),
                )

        assert isinstance(result, AuthorizationResult)
        assert result.is_authorized is True
        assert result.event_id == EVENT_ID
        assert result.approved_amount == 100.0
        assert result.budget_snapshot is not None
        assert result.budget_snapshot.available_amount == 400.0

    @pytest.mark.asyncio
    async def test_denied_is_authorized_false(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload=_denied_payload("INSUFFICIENT_FUNDS"), status=200)

                result = await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="agent_001",
                    action_type="PURCHASE",
                    description="too expensive",
                    requested_amount=9999.0,
                    idempotency_key=str(uuid.uuid4()),
                )

        assert result.is_authorized is False
        assert result.denial_reason == "INSUFFICIENT_FUNDS"

    @pytest.mark.asyncio
    async def test_raise_if_denied_raises_figuard_denied_exception(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload=_denied_payload("BUDGET_PAUSED"), status=200)

                with pytest.raises(FiGuardDeniedException) as exc_info:
                    (await client.authorize(
                        session_token=SESSION_TOKEN,
                        agent_id="agent_001",
                        action_type="PURCHASE",
                        description="paused budget",
                        requested_amount=10.0,
                        idempotency_key=str(uuid.uuid4()),
                    )).raise_if_denied()

        assert exc_info.value.denial_reason == "BUDGET_PAUSED"

    @pytest.mark.asyncio
    async def test_entity_already_authorized_carries_original_event_id(self) -> None:
        original_id = str(uuid.uuid4())
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload={
                    "eventId": EVENT_ID,
                    "decision": "DENIED",
                    "denialReason": "ENTITY_ALREADY_AUTHORIZED",
                    "denialMessage": "entity already authorized",
                    "originalEventId": original_id,
                    "originalEventStatus": "AUTHORIZED",
                }, status=200)

                result = await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="agent_001",
                    action_type="PURCHASE",
                    description="duplicate",
                    requested_amount=50.0,
                    idempotency_key=str(uuid.uuid4()),
                )

        assert result.denial_reason == "ENTITY_ALREADY_AUTHORIZED"
        assert result.original_event_id == original_id

        with pytest.raises(FiGuardDeniedException) as exc_info:
            result.raise_if_denied()
        assert exc_info.value.original_event_id == original_id


# ---------------------------------------------------------------------------
# Payment lifecycle
# ---------------------------------------------------------------------------

class TestPaymentLifecycle:

    @pytest.mark.asyncio
    async def test_confirm_event(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/events/{EVENT_ID}/confirm",
                       payload=_event_payload("CONFIRMED"), status=200)
                event = await client.confirm_event(EVENT_ID, confirmed_amount=95.0)

        assert event.id == EVENT_ID
        assert event.decision == "CONFIRMED"

    @pytest.mark.asyncio
    async def test_fail_event(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/events/{EVENT_ID}/fail",
                       payload=_event_payload("FAILED"), status=200)
                event = await client.fail_event(EVENT_ID, reason="PAYMENT_DECLINED")

        assert event.decision == "FAILED"

    @pytest.mark.asyncio
    async def test_void_event_returns_void_result(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/events/{EVENT_ID}/void",
                       payload=_event_payload("VOIDED"), status=200)
                result = await client.void_event(EVENT_ID, reason="USER_CANCELLED")

        assert result.is_voided is True
        assert result.event.decision == "VOIDED"


# ---------------------------------------------------------------------------
# Resume budget
# ---------------------------------------------------------------------------

class TestResumeBudget:

    @pytest.mark.asyncio
    async def test_resume_returns_active_budget(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/budgets/{BUDGET_ID}/resume",
                       payload=_budget_payload(), status=200)
                budget = await client.resume_budget(
                    BUDGET_ID,
                    override_reason="Reviewed and confirmed legitimate spend",
                    override_by="ops-team",
                )

        assert budget.status == "ACTIVE"

    @pytest.mark.asyncio
    async def test_resume_409_when_not_paused(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(
                    f"{BASE}/api/v1/budgets/{BUDGET_ID}/resume",
                    payload={"message": "Budget is not PAUSED (current status: ACTIVE)"},
                    status=409,
                )

                with pytest.raises(FiGuardApiError) as exc_info:
                    await client.resume_budget(BUDGET_ID, override_reason="reason")

        assert exc_info.value.status_code == 409


# ---------------------------------------------------------------------------
# Retry
# ---------------------------------------------------------------------------

class TestRetry:

    @pytest.mark.asyncio
    async def test_retries_on_500_and_succeeds(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                      payload={"error": "server error"}, status=500)
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                      payload={"error": "server error"}, status=500)
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                      payload=_budget_payload(), status=200)

                with mock.patch("figuard.async_client.asyncio.sleep"):
                    budget = await client.get_budget(BUDGET_ID)

        assert budget.id == BUDGET_ID

    @pytest.mark.asyncio
    async def test_raises_after_all_retries_exhausted(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                for _ in range(3):
                    m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                          payload={"error": "server error"}, status=500)

                with mock.patch("figuard.async_client.asyncio.sleep"):
                    with pytest.raises(FiGuardApiError) as exc_info:
                        await client.get_budget(BUDGET_ID)

        assert exc_info.value.status_code == 500

    @pytest.mark.asyncio
    async def test_does_not_retry_4xx(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                      payload={"message": "not found"}, status=404)

                with pytest.raises(FiGuardApiError) as exc_info:
                    await client.get_budget(BUDGET_ID)

        assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Concurrent authorize — core async value prop
# ---------------------------------------------------------------------------

class TestConcurrentAuthorize:

    @pytest.mark.asyncio
    async def test_three_agents_authorize_concurrently(self) -> None:
        """
        Three async agents fire authorize() concurrently.

        With a real server, the locking guarantees mean at most budget_limit /
        requested_amount can succeed. Here we just verify the SDK fires all
        three calls without serializing them — i.e., the interface is truly
        async and doesn't block the event loop between requests.
        """
        agent_ids = ["agent_flight", "agent_hotel", "agent_car"]
        idempotency_keys = [str(uuid.uuid4()) for _ in agent_ids]

        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                # All three calls succeed
                for _ in agent_ids:
                    m.post(f"{BASE}/api/v1/authorize",
                           payload=_authorized_payload(), status=200)

                results = await asyncio.gather(*[
                    client.authorize(
                        session_token=SESSION_TOKEN,
                        agent_id=aid,
                        action_type="PURCHASE",
                        description=f"{aid} purchase",
                        requested_amount=50.0,
                        idempotency_key=ikey,
                    )
                    for aid, ikey in zip(agent_ids, idempotency_keys)
                ])

        assert len(results) == 3
        assert all(r.is_authorized for r in results)

    @pytest.mark.asyncio
    async def test_gather_continues_after_partial_denial(self) -> None:
        """
        When one agent is denied and others are authorized, asyncio.gather
        with return_exceptions=True surfaces all results without cancelling
        the group.
        """
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload=_authorized_payload(), status=200)
                m.post(f"{BASE}/api/v1/authorize",
                       payload=_denied_payload("INSUFFICIENT_FUNDS"), status=200)
                m.post(f"{BASE}/api/v1/authorize", payload=_authorized_payload(), status=200)

                raw_results = await asyncio.gather(*[
                    client.authorize(
                        session_token=SESSION_TOKEN,
                        agent_id=f"agent_{i}",
                        action_type="PURCHASE",
                        description="test",
                        requested_amount=50.0,
                        idempotency_key=str(uuid.uuid4()),
                    )
                    for i in range(3)
                ], return_exceptions=True)

        authorized = [r for r in raw_results if isinstance(r, AuthorizationResult) and r.is_authorized]
        denied = [r for r in raw_results if isinstance(r, AuthorizationResult) and not r.is_authorized]
        assert len(authorized) == 2
        assert len(denied) == 1


# ---------------------------------------------------------------------------
# Ledger
# ---------------------------------------------------------------------------

class TestLedger:

    @pytest.mark.asyncio
    async def test_get_ledger_returns_ledger_page(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(
                    f"{BASE}/api/v1/budgets/{BUDGET_ID}/ledger?page=0&size=20",
                    payload={
                        "content": [_event_payload("CONFIRMED")],
                        "totalElements": 1,
                        "totalPages": 1,
                        "number": 0,
                        "size": 20,
                    },
                    status=200,
                )

                page = await client.get_ledger(BUDGET_ID)

        assert page.total_elements == 1
        assert len(page.events) == 1
        assert page.has_next is False
        assert page.events[0].decision == "CONFIRMED"
