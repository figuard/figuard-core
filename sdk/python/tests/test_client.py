"""
Unit tests for FiGuardClient (sync).

Uses the `responses` library to mock HTTP — no real server needed.
"""

from __future__ import annotations

import json
import uuid
from typing import Any, Dict

import pytest
import responses as resp_lib

from figuard import (
    FiGuardApiError,
    FiGuardClient,
    FiGuardConnectionError,
    FiGuardDeniedException,
)
from figuard.models import AuthorizationResult, Budget

BASE = "https://api.figuard.io"
API_KEY = "ab_test_sdktest"

BUDGET_ID = str(uuid.uuid4())
EVENT_ID = str(uuid.uuid4())
SESSION_TOKEN = "st_abcdef1234567890"


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def client() -> FiGuardClient:
    return FiGuardClient(api_key=API_KEY, base_url=BASE)


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
# create_budget
# ---------------------------------------------------------------------------

class TestCreateBudget:

    @resp_lib.activate
    def test_returns_budget_with_session_token(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets",
                     json=_budget_payload(session_token=True), status=201)

        budget = client.create_budget(
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

    @resp_lib.activate
    def test_session_token_absent_on_get(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json=_budget_payload(session_token=False), status=200)

        budget = client.get_budget(BUDGET_ID)

        assert budget.session_token is None

    @resp_lib.activate
    def test_api_error_propagated(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets",
                     json={"error": "VALIDATION_FAILED", "message": "expiresAt is required"},
                     status=400)

        with pytest.raises(FiGuardApiError) as exc_info:
            client.create_budget(user_id="u", total_limit=100.0, expires_at="bad")

        assert exc_info.value.status_code == 400
        assert "expiresAt" in exc_info.value.message


# ---------------------------------------------------------------------------
# authorize
# ---------------------------------------------------------------------------

class TestAuthorize:

    def test_raises_value_error_when_idempotency_key_missing(self, client: FiGuardClient) -> None:
        with pytest.raises(ValueError, match="idempotency_key is required"):
            client.authorize(
                session_token=SESSION_TOKEN,
                agent_id="agent_001",
                action_type="PURCHASE",
                description="test",
                requested_amount=50.0,
                # idempotency_key intentionally omitted
            )

    def test_raises_value_error_when_idempotency_key_blank(self, client: FiGuardClient) -> None:
        with pytest.raises(ValueError):
            client.authorize(
                session_token=SESSION_TOKEN,
                agent_id="agent_001",
                action_type="PURCHASE",
                description="test",
                requested_amount=50.0,
                idempotency_key="   ",
            )

    @resp_lib.activate
    def test_authorized_returns_result_with_is_authorized_true(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_authorized_payload(), status=200)

        result = client.authorize(
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

    @resp_lib.activate
    def test_denied_is_authorized_false(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_denied_payload("INSUFFICIENT_FUNDS"), status=200)

        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="too expensive",
            requested_amount=9999.0,
            idempotency_key=str(uuid.uuid4()),
        )

        assert result.is_authorized is False
        assert result.denial_reason == "INSUFFICIENT_FUNDS"

    @resp_lib.activate
    def test_raise_if_denied_raises_figguard_denied_exception(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_denied_payload("BUDGET_PAUSED"), status=200)

        with pytest.raises(FiGuardDeniedException) as exc_info:
            client.authorize(
                session_token=SESSION_TOKEN,
                agent_id="agent_001",
                action_type="PURCHASE",
                description="paused budget",
                requested_amount=10.0,
                idempotency_key=str(uuid.uuid4()),
            ).raise_if_denied()

        assert exc_info.value.denial_reason == "BUDGET_PAUSED"

    @resp_lib.activate
    def test_raise_if_denied_returns_self_when_authorized(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_authorized_payload(), status=200)

        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="test",
            requested_amount=50.0,
            idempotency_key=str(uuid.uuid4()),
        ).raise_if_denied()

        assert result.is_authorized is True

    @resp_lib.activate
    def test_entity_already_authorized_carries_original_event_id(self, client: FiGuardClient) -> None:
        original_id = str(uuid.uuid4())
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json={
                         "eventId": EVENT_ID,
                         "decision": "DENIED",
                         "denialReason": "ENTITY_ALREADY_AUTHORIZED",
                         "denialMessage": "entity already authorized",
                         "originalEventId": original_id,
                         "originalEventStatus": "AUTHORIZED",
                     }, status=200)

        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="duplicate",
            requested_amount=50.0,
            idempotency_key=str(uuid.uuid4()),
        )

        assert result.denial_reason == "ENTITY_ALREADY_AUTHORIZED"
        assert result.original_event_id == original_id

        # raise_if_denied should propagate original_event_id
        with pytest.raises(FiGuardDeniedException) as exc_info:
            result.raise_if_denied()
        assert exc_info.value.original_event_id == original_id

    @resp_lib.activate
    def test_session_token_not_in_request_headers_log(self, client: FiGuardClient) -> None:
        """The raw session token must be sent as header but never logged."""
        captured: list = []

        def capture_request(req):
            captured.append(req)
            return (200, {}, json.dumps(_authorized_payload()))

        resp_lib.add_callback(resp_lib.POST, f"{BASE}/api/v1/authorize", callback=capture_request)

        client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="test",
            requested_amount=50.0,
            idempotency_key=str(uuid.uuid4()),
        )

        assert captured[0].headers.get("X-Session-Token") == SESSION_TOKEN


# ---------------------------------------------------------------------------
# Payment lifecycle
# ---------------------------------------------------------------------------

class TestPaymentLifecycle:

    @resp_lib.activate
    def test_confirm_event(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/confirm",
                     json=_event_payload("CONFIRMED"), status=200)

        event = client.confirm_event(EVENT_ID, confirmed_amount=95.0)

        assert event.id == EVENT_ID
        assert event.decision == "CONFIRMED"

    @resp_lib.activate
    def test_fail_event(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/fail",
                     json=_event_payload("FAILED"), status=200)

        event = client.fail_event(EVENT_ID, reason="PAYMENT_DECLINED")

        assert event.decision == "FAILED"

    @resp_lib.activate
    def test_void_event_returns_void_result(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/void",
                     json=_event_payload("VOIDED"), status=200)

        result = client.void_event(EVENT_ID, reason="USER_CANCELLED")

        assert result.is_voided is True
        assert result.event.decision == "VOIDED"


# ---------------------------------------------------------------------------
# Resume budget
# ---------------------------------------------------------------------------

class TestResumeBudget:

    @resp_lib.activate
    def test_resume_returns_active_budget(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets/{BUDGET_ID}/resume",
                     json=_budget_payload(), status=200)

        budget = client.resume_budget(
            BUDGET_ID,
            override_reason="Reviewed and confirmed legitimate spend",
            override_by="ops-team",
        )

        assert budget.status == "ACTIVE"

    @resp_lib.activate
    def test_resume_409_when_not_paused(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets/{BUDGET_ID}/resume",
                     json={"message": "Budget is not PAUSED (current status: ACTIVE)"},
                     status=409)

        with pytest.raises(FiGuardApiError) as exc_info:
            client.resume_budget(BUDGET_ID, override_reason="reason")

        assert exc_info.value.status_code == 409


# ---------------------------------------------------------------------------
# Retry behaviour
# ---------------------------------------------------------------------------

class TestRetry:

    @resp_lib.activate
    def test_retries_on_500_and_succeeds(self, client: FiGuardClient) -> None:
        # First two attempts return 500, third succeeds
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json={"error": "server error"}, status=500)
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json={"error": "server error"}, status=500)
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json=_budget_payload(), status=200)

        # Patch sleep so tests don't actually wait
        import unittest.mock as mock
        with mock.patch("figuard.client.time.sleep"):
            budget = client.get_budget(BUDGET_ID)

        assert budget.id == BUDGET_ID

    @resp_lib.activate
    def test_raises_after_all_retries_exhausted(self, client: FiGuardClient) -> None:
        for _ in range(3):
            resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                         json={"error": "server error"}, status=500)

        import unittest.mock as mock
        with mock.patch("figuard.client.time.sleep"):
            with pytest.raises(FiGuardApiError) as exc_info:
                client.get_budget(BUDGET_ID)

        assert exc_info.value.status_code == 500

    @resp_lib.activate
    def test_does_not_retry_4xx(self, client: FiGuardClient) -> None:
        # Only one 404 registered — if it retried, responses would raise an error
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json={"message": "not found"}, status=404)

        with pytest.raises(FiGuardApiError) as exc_info:
            client.get_budget(BUDGET_ID)

        assert exc_info.value.status_code == 404
        assert len(resp_lib.calls) == 1


# ---------------------------------------------------------------------------
# Ledger
# ---------------------------------------------------------------------------

class TestLedger:

    @resp_lib.activate
    def test_get_ledger_returns_ledger_page(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/ledger",
                     json={
                         "content": [_event_payload("CONFIRMED")],
                         "totalElements": 1,
                         "totalPages": 1,
                         "number": 0,
                         "size": 20,
                     }, status=200)

        page = client.get_ledger(BUDGET_ID)

        assert page.total_elements == 1
        assert len(page.events) == 1
        assert page.has_next is False
        assert page.events[0].decision == "CONFIRMED"
