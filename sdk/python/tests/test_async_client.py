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
API_KEY = "fg_test_asynctest"

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
        "quantitySpent": 0.0,
        "quantityReserved": 0.0,
        "availableQuantity": 500.0,
        "status": "ACTIVE",
        "expiresAt": "2025-12-31T23:59:59Z",
        "createdAt": "2025-01-01T00:00:00Z",
        "sessionTokenPrefix": "st_abcde",
        "allocations": [],
    }
    if session_token:
        payload["tokens"] = [
            {
                "category": "default",
                "sessionToken": SESSION_TOKEN,
                "sessionTokenPrefix": "st_abcde",
                "unit": None,
                "currency": "USD",
            }
        ]
    return payload


def _authorized_payload() -> Dict[str, Any]:
    return {
        "eventId": EVENT_ID,
        "decision": "AUTHORIZED",
        "approvedQuantity": 100.0,
        "authorizedAt": "2025-01-01T10:00:00Z",
        "budgetSnapshot": {
            "totalLimit": 500.0,
            "quantitySpent": 0.0,
            "quantityReserved": 100.0,
            "availableQuantity": 400.0,
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
        "requestedQuantity": 100.0,
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
        assert budget.primary_token.session_token == SESSION_TOKEN
        assert budget.status == "ACTIVE"
        assert budget.available_quantity == 500.0
        assert budget.is_active is True

    @pytest.mark.asyncio
    async def test_session_token_absent_on_get(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}", payload=_budget_payload(), status=200)
                budget = await client.get_budget(BUDGET_ID)

        assert budget.primary_token is None

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

    @pytest.mark.asyncio
    async def test_velocity_params_sent_in_request_body(self) -> None:
        captured: dict = {}

        async def fake_request(method: str, path: str, **kwargs: Any) -> Any:
            if method == "POST" and path == "/api/v1/budgets":
                captured.update(kwargs.get("json", {}))
            return _budget_payload(session_token=True)

        client = AsyncFiGuardClient(api_key=API_KEY, base_url=BASE)
        with mock.patch.object(client, "_request", side_effect=fake_request):
            await client.create_budget(
                user_id="user_test",
                total_limit=500.0,
                expires_at="2025-12-31T23:59:59Z",
                velocity_max_per_minute=10,
                velocity_max_amount_per_hour=250.0,
                velocity_max_per_day=50,
            )

        assert captured.get("velocityMaxPerMinute") == 10
        assert captured.get("velocityMaxAmountPerHour") == 250.0
        assert captured.get("velocityMaxPerDay") == 50

    @pytest.mark.asyncio
    async def test_parse_budget_extracts_velocity_fields(self) -> None:
        payload = _budget_payload(session_token=True)
        payload["velocityMaxPerMinute"] = 5
        payload["velocityMaxAmountPerHour"] = 100.0
        payload["velocityMaxPerDay"] = 20

        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/budgets", payload=payload, status=201)

                budget = await client.create_budget(
                    user_id="user_test",
                    total_limit=500.0,
                    expires_at="2025-12-31T23:59:59Z",
                )

        assert budget.velocity_max_per_minute == 5
        assert budget.velocity_max_amount_per_hour == 100.0
        assert budget.velocity_max_per_day == 20

    @pytest.mark.asyncio
    async def test_velocity_fields_absent_when_not_set(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/budgets", payload=_budget_payload(session_token=True), status=201)

                budget = await client.create_budget(
                    user_id="user_test",
                    total_limit=500.0,
                    expires_at="2025-12-31T23:59:59Z",
                )

        assert budget.velocity_max_per_minute is None
        assert budget.velocity_max_amount_per_hour is None
        assert budget.velocity_max_per_day is None


# ---------------------------------------------------------------------------
# authorize
# ---------------------------------------------------------------------------

class TestAuthorize:

    @pytest.mark.asyncio
    async def test_auto_generates_idempotency_key_when_omitted(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload=_authorized_payload(), status=200)
                # No idempotency_key — SDK must auto-generate a UUID and not raise
                result = await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="agent_001",
                    action_type="PURCHASE",
                    description="test",
                    requested_quantity=50.0,
                )
                assert result.is_authorized is True

    @pytest.mark.asyncio
    async def test_auto_generates_idempotency_key_when_blank(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload=_authorized_payload(), status=200)
                result = await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="agent_001",
                    action_type="PURCHASE",
                    description="test",
                    requested_quantity=50.0,
                    idempotency_key="   ",  # blank — SDK should auto-generate
                )
                assert result.is_authorized is True

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
                    requested_quantity=100.0,
                    idempotency_key=str(uuid.uuid4()),
                )

        assert isinstance(result, AuthorizationResult)
        assert result.is_authorized is True
        assert result.event_id == EVENT_ID
        assert result.approved_quantity == 100.0
        assert result.budget_snapshot is not None
        assert result.budget_snapshot.available_quantity == 400.0

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
                    requested_quantity=9999.0,
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
                        requested_quantity=10.0,
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
                    requested_quantity=50.0,
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
                event = await client.confirm_event(EVENT_ID, confirmed_quantity=95.0)

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
                        requested_quantity=50.0,
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
                        requested_quantity=50.0,
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

    @pytest.mark.asyncio
    async def test_get_ledger_with_decision_filter(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(
                    f"{BASE}/api/v1/budgets/{BUDGET_ID}/ledger?page=0&size=50&decision=CONFIRMED",
                    payload={
                        "content": [_event_payload("CONFIRMED")],
                        "totalElements": 1,
                        "totalPages": 1,
                        "number": 0,
                        "size": 50,
                    },
                    status=200,
                )

                page = await client.get_ledger(BUDGET_ID, size=50, decision="CONFIRMED")

        assert page.total_elements == 1
        assert page.events[0].decision == "CONFIRMED"

    @pytest.mark.asyncio
    async def test_get_ledger_has_next_when_more_pages(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(
                    f"{BASE}/api/v1/budgets/{BUDGET_ID}/ledger?page=0&size=20",
                    payload={
                        "content": [_event_payload("AUTHORIZED")],
                        "totalElements": 45,
                        "totalPages": 3,
                        "number": 0,
                        "size": 20,
                    },
                    status=200,
                )

                page = await client.get_ledger(BUDGET_ID)

        assert page.has_next is True
        assert page.total_pages == 3


# ---------------------------------------------------------------------------
# Spend tree
# ---------------------------------------------------------------------------

class TestSpendTree:

    @pytest.mark.asyncio
    async def test_empty_tree(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}/tree",
                      payload={"roots": [], "totalEvents": 0}, status=200)
                tree = await client.get_spend_tree(BUDGET_ID)

        assert tree.budget_id == BUDGET_ID
        assert tree.roots == []
        assert tree.total_events == 0

    @pytest.mark.asyncio
    async def test_nested_tree(self) -> None:
        parent_event = {
            "id": str(uuid.uuid4()),
            "decision": "CONFIRMED",
            "requestedQuantity": 100.0,
            "currency": "USD",
            "createdAt": "2025-01-01T10:00:00Z",
            "agentId": "parent_agent",
        }
        child_event = {**parent_event, "id": str(uuid.uuid4()), "agentId": "child_agent"}

        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}/tree",
                      payload={
                          "roots": [
                              {
                                  "event": parent_event,
                                  "children": [{"event": child_event, "children": []}],
                              }
                          ],
                          "totalEvents": 2,
                      }, status=200)
                tree = await client.get_spend_tree(BUDGET_ID)

        assert tree.total_events == 2
        assert len(tree.roots) == 1
        assert tree.roots[0].event.agent_id == "parent_agent"
        assert len(tree.roots[0].children) == 1
        assert tree.roots[0].children[0].event.agent_id == "child_agent"


# ---------------------------------------------------------------------------
# Rotate session token
# ---------------------------------------------------------------------------

class TestRotateSessionToken:

    @pytest.mark.asyncio
    async def test_returns_new_token(self) -> None:
        new_token = "st_newtoken1234567890"
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/budgets/{BUDGET_ID}/rotate-token",
                       payload={"sessionToken": new_token}, status=200)
                token = await client.rotate_session_token(BUDGET_ID)

        assert token == new_token

    @pytest.mark.asyncio
    async def test_api_error_propagated(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/budgets/{BUDGET_ID}/rotate-token",
                       payload={"message": "Budget not found"}, status=404)

                with pytest.raises(FiGuardApiError) as exc_info:
                    await client.rotate_session_token(BUDGET_ID)

        assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Receipt URL
# ---------------------------------------------------------------------------

class TestReceiptUrl:

    @pytest.mark.asyncio
    async def test_returns_url_string(self) -> None:
        receipt_url = "https://receipts.figuard.io/r/abc123xyz"
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}/receipt",
                      payload={"receiptUrl": receipt_url}, status=200)
                url = await client.get_receipt_url(BUDGET_ID)

        assert url == receipt_url


# ---------------------------------------------------------------------------
# Connection error
# ---------------------------------------------------------------------------

class TestConnectionError:

    @pytest.mark.asyncio
    async def test_raises_figuard_connection_error_after_all_retries(self) -> None:
        import aiohttp
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                for _ in range(3):
                    m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                          exception=aiohttp.ClientConnectionError("Connection refused"))

                with mock.patch("figuard.async_client.asyncio.sleep"):
                    with pytest.raises(FiGuardConnectionError) as exc_info:
                        await client.get_budget(BUDGET_ID)

        assert "Connection refused" in str(exc_info.value)


# ---------------------------------------------------------------------------
# Budget: optional fields
# ---------------------------------------------------------------------------

class TestBudgetOptionalFields:

    @pytest.mark.asyncio
    async def test_create_budget_with_allocations_parsed(self) -> None:
        alloc_id = str(uuid.uuid4())
        payload = {
            **_budget_payload(session_token=True),
            "allocations": [
                {
                    "id": alloc_id,
                    "category": "flight",
                    "allowedCategories": ["flight"],
                    "limit": 300.0,
                    "quantitySpent": 0.0,
                    "quantityReserved": 0.0,
                    "availableQuantity": 300.0,
                    "status": "ACTIVE",
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                }
            ],
        }
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/budgets", payload=payload, status=201)
                budget = await client.create_budget(
                    user_id="user_test",
                    total_limit=500.0,
                    expires_at="2025-12-31T23:59:59Z",
                )

        assert len(budget.allocations) == 1
        assert budget.allocations[0].category == "flight"
        assert budget.allocations[0].limit == 300.0

    @pytest.mark.asyncio
    async def test_budget_is_paused_property(self) -> None:
        paused_payload = {**_budget_payload(), "status": "PAUSED"}
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.get(f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                      payload=paused_payload, status=200)
                budget = await client.get_budget(BUDGET_ID)

        assert budget.is_paused is True
        assert budget.is_active is False


# ---------------------------------------------------------------------------
# Payment lifecycle extended
# ---------------------------------------------------------------------------

class TestPaymentLifecycleExtended:

    @pytest.mark.asyncio
    async def test_void_event_with_child_events_flag(self) -> None:
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/events/{EVENT_ID}/void",
                       payload=_event_payload("VOIDED"), status=200)
                result = await client.void_event(
                    EVENT_ID, reason="USER_CANCELLED", void_child_events=True
                )

        assert result.is_voided is True

    @pytest.mark.asyncio
    async def test_allocation_snapshot_parsed(self) -> None:
        payload = {
            **_authorized_payload(),
            "allocationSnapshot": {
                "category": "hotel",
                "limit": 200.0,
                "quantitySpent": 0.0,
                "quantityReserved": 100.0,
                "availableQuantity": 100.0,
                "status": "ACTIVE",
            },
        }
        async with AsyncFiGuardClient(api_key=API_KEY, base_url=BASE) as client:
            with aioresponses_ctx() as m:
                m.post(f"{BASE}/api/v1/authorize", payload=payload, status=200)
                result = await client.authorize(
                    session_token=SESSION_TOKEN,
                    agent_id="a",
                    action_type="PURCHASE",
                    description="hotel",
                    requested_quantity=100.0,
                    idempotency_key=str(uuid.uuid4()),
                    claimed_category="hotel",
                )

        assert result.allocation_snapshot is not None
        assert result.allocation_snapshot.category == "hotel"
        assert result.allocation_snapshot.available_quantity == 100.0
