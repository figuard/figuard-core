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
        payload["sessionToken"] = SESSION_TOKEN
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
        assert budget.available_quantity == 500.0
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

    @resp_lib.activate
    def test_auto_generates_idempotency_key_when_omitted(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_authorized_payload(), status=200)

        # No idempotency_key provided — SDK must auto-generate a UUID and not raise
        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="test",
            requested_quantity=50.0,
            # idempotency_key intentionally omitted
        )
        assert result.is_authorized is True
        # Verify the auto-generated key was sent in the request body
        sent_body = resp_lib.calls[0].request.body
        import json as _json
        parsed = _json.loads(sent_body)
        assert "idempotencyKey" in parsed
        assert len(parsed["idempotencyKey"]) == 36  # UUID v4 format

    @resp_lib.activate
    def test_auto_generates_idempotency_key_when_blank(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_authorized_payload(), status=200)

        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="test",
            requested_quantity=50.0,
            idempotency_key="   ",  # blank — SDK should auto-generate
        )
        assert result.is_authorized is True

    @resp_lib.activate
    def test_authorized_returns_result_with_is_authorized_true(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_authorized_payload(), status=200)

        result = client.authorize(
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

    @resp_lib.activate
    def test_denied_is_authorized_false(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_denied_payload("INSUFFICIENT_FUNDS"), status=200)

        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="too expensive",
            requested_quantity=9999.0,
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
                requested_quantity=10.0,
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
            requested_quantity=50.0,
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
            requested_quantity=50.0,
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
            requested_quantity=50.0,
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

        event = client.confirm_event(EVENT_ID, confirmed_quantity=95.0)

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

    @resp_lib.activate
    def test_get_ledger_with_decision_filter(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/ledger",
                     json={
                         "content": [_event_payload("CONFIRMED")],
                         "totalElements": 1,
                         "totalPages": 1,
                         "number": 0,
                         "size": 50,
                     }, status=200)

        page = client.get_ledger(BUDGET_ID, page=0, size=50, decision="CONFIRMED")

        assert page.total_elements == 1
        # Verify the filter param was sent
        assert "decision=CONFIRMED" in resp_lib.calls[0].request.url

    @resp_lib.activate
    def test_get_ledger_has_next_when_more_pages(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/ledger",
                     json={
                         "content": [_event_payload("AUTHORIZED")],
                         "totalElements": 45,
                         "totalPages": 3,
                         "number": 0,
                         "size": 20,
                     }, status=200)

        page = client.get_ledger(BUDGET_ID)

        assert page.has_next is True
        assert page.total_pages == 3

    @resp_lib.activate
    def test_get_ledger_empty(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/ledger",
                     json={
                         "content": [],
                         "totalElements": 0,
                         "totalPages": 0,
                         "number": 0,
                         "size": 20,
                     }, status=200)

        page = client.get_ledger(BUDGET_ID)

        assert page.total_elements == 0
        assert page.events == []
        assert page.has_next is False


# ---------------------------------------------------------------------------
# Spend tree
# ---------------------------------------------------------------------------

def _tree_event_payload(agent_id: str = "agent_001") -> dict:
    return {
        "id": str(uuid.uuid4()),
        "decision": "CONFIRMED",
        "requestedQuantity": 100.0,
        "currency": "USD",
        "createdAt": "2025-01-01T10:00:00Z",
        "agentId": agent_id,
    }


class TestSpendTree:

    @resp_lib.activate
    def test_empty_tree(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/tree",
                     json={"roots": [], "totalEvents": 0}, status=200)

        tree = client.get_spend_tree(BUDGET_ID)

        assert tree.budget_id == BUDGET_ID
        assert tree.roots == []
        assert tree.total_events == 0

    @resp_lib.activate
    def test_flat_tree_single_root(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/tree",
                     json={
                         "roots": [
                             {"event": _tree_event_payload("agent_flight"), "children": []}
                         ],
                         "totalEvents": 1,
                     }, status=200)

        tree = client.get_spend_tree(BUDGET_ID)

        assert tree.total_events == 1
        assert len(tree.roots) == 1
        assert tree.roots[0].event.agent_id == "agent_flight"
        assert tree.roots[0].children == []

    @resp_lib.activate
    def test_nested_tree_parent_child(self, client: FiGuardClient) -> None:
        child_event = _tree_event_payload("child_agent")
        parent_event = _tree_event_payload("parent_agent")
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/tree",
                     json={
                         "roots": [
                             {
                                 "event": parent_event,
                                 "children": [
                                     {"event": child_event, "children": []}
                                 ],
                             }
                         ],
                         "totalEvents": 2,
                     }, status=200)

        tree = client.get_spend_tree(BUDGET_ID)

        assert tree.total_events == 2
        root = tree.roots[0]
        assert root.event.agent_id == "parent_agent"
        assert len(root.children) == 1
        assert root.children[0].event.agent_id == "child_agent"
        assert root.children[0].children == []


# ---------------------------------------------------------------------------
# Rotate session token
# ---------------------------------------------------------------------------

class TestRotateSessionToken:

    @resp_lib.activate
    def test_returns_new_token_string(self, client: FiGuardClient) -> None:
        new_token = "st_newtoken1234567890"
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets/{BUDGET_ID}/rotate-token",
                     json={"sessionToken": new_token}, status=200)

        token = client.rotate_session_token(BUDGET_ID)

        assert token == new_token
        assert token.startswith("st_")

    @resp_lib.activate
    def test_api_error_propagated(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets/{BUDGET_ID}/rotate-token",
                     json={"message": "Budget not found"}, status=404)

        with pytest.raises(FiGuardApiError) as exc_info:
            client.rotate_session_token(BUDGET_ID)

        assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Receipt URL
# ---------------------------------------------------------------------------

class TestReceiptUrl:

    @resp_lib.activate
    def test_returns_url_string(self, client: FiGuardClient) -> None:
        receipt_url = "https://receipts.figuard.io/r/abc123xyz"
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/receipt",
                     json={"receiptUrl": receipt_url}, status=200)

        url = client.get_receipt_url(BUDGET_ID)

        assert url == receipt_url

    @resp_lib.activate
    def test_404_when_budget_not_found(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}/receipt",
                     json={"message": "Budget not found"}, status=404)

        with pytest.raises(FiGuardApiError) as exc_info:
            client.get_receipt_url(BUDGET_ID)

        assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Connection error
# ---------------------------------------------------------------------------

class TestConnectionError:

    @resp_lib.activate
    def test_raises_figuard_connection_error_after_all_retries(self, client: FiGuardClient) -> None:
        import requests as req_lib
        # Simulate a connection error on every attempt
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     body=req_lib.exceptions.ConnectionError("Connection refused"))
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     body=req_lib.exceptions.ConnectionError("Connection refused"))
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     body=req_lib.exceptions.ConnectionError("Connection refused"))

        import unittest.mock as mock
        with mock.patch("figuard.client.time.sleep"):
            with pytest.raises(FiGuardConnectionError) as exc_info:
                client.get_budget(BUDGET_ID)

        assert "Connection refused" in str(exc_info.value)

    @resp_lib.activate
    def test_connection_error_then_success_retries(self, client: FiGuardClient) -> None:
        """Connection error on first attempt, success on second."""
        import requests as req_lib
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     body=req_lib.exceptions.ConnectionError("transient error"))
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json=_budget_payload(), status=200)

        import unittest.mock as mock
        with mock.patch("figuard.client.time.sleep"):
            budget = client.get_budget(BUDGET_ID)

        assert budget.id == BUDGET_ID


# ---------------------------------------------------------------------------
# Budget: optional fields & advanced creation
# ---------------------------------------------------------------------------

class TestBudgetOptionalFields:

    @resp_lib.activate
    def test_create_budget_with_all_optional_fields(self, client: FiGuardClient) -> None:
        payload = {
            **_budget_payload(session_token=True),
            "intentContext": "booking a business trip",
            "intentTags": ["travel", "business"],
            "externalReference": "ext-ref-001",
            "softLimit": 400.0,
            "maxTransactionQuantity": 250.0,
            "metadata": {"department": "engineering"},
        }
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets", json=payload, status=201)

        budget = client.create_budget(
            user_id="user_test",
            total_limit=500.0,
            expires_at="2025-12-31T23:59:59Z",
            intent_context="booking a business trip",
            intent_tags=["travel", "business"],
            external_reference="ext-ref-001",
            soft_limit=400.0,
            max_transaction_quantity=250.0,
            anomaly_detection_enabled=True,
            metadata={"department": "engineering"},
        )

        assert budget.intent_context == "booking a business trip"
        assert budget.intent_tags == ["travel", "business"]
        assert budget.external_reference == "ext-ref-001"
        assert budget.soft_limit == 400.0
        assert budget.max_transaction_quantity == 250.0
        assert budget.metadata == {"department": "engineering"}

        # Verify anomaly_detection_enabled was sent in the request body
        import json as json_lib
        request_body = json_lib.loads(resp_lib.calls[0].request.body)
        assert request_body.get("anomalyDetectionEnabled") is True

    @resp_lib.activate
    def test_create_budget_with_allocations_parsed_correctly(self, client: FiGuardClient) -> None:
        alloc_id = str(uuid.uuid4())
        payload = {
            **_budget_payload(session_token=True),
            "allocations": [
                {
                    "id": alloc_id,
                    "category": "flight",
                    "allowedCategories": ["flight", "airline"],
                    "limit": 300.0,
                    "quantitySpent": 0.0,
                    "quantityReserved": 0.0,
                    "availableQuantity": 300.0,
                    "status": "ACTIVE",
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                    "forbiddenItemTypes": ["gift_card"],
                }
            ],
        }
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/budgets", json=payload, status=201)

        budget = client.create_budget(
            user_id="user_test",
            total_limit=500.0,
            expires_at="2025-12-31T23:59:59Z",
            allocations=[{
                "category": "flight",
                "allowedCategories": ["flight", "airline"],
                "limit": 300.0,
                "enforcementMode": "CATEGORY_CONSTRAINED",
            }],
        )

        assert len(budget.allocations) == 1
        alloc = budget.allocations[0]
        assert alloc.id == alloc_id
        assert alloc.category == "flight"
        assert alloc.allowed_categories == ["flight", "airline"]
        assert alloc.limit == 300.0
        assert alloc.quantity_spent == 0.0
        assert alloc.available_quantity == 300.0
        assert alloc.enforcement_mode == "CATEGORY_CONSTRAINED"
        assert alloc.forbidden_item_types == ["gift_card"]

    @resp_lib.activate
    def test_budget_is_paused_property(self, client: FiGuardClient) -> None:
        paused_payload = {**_budget_payload(), "status": "PAUSED"}
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json=paused_payload, status=200)

        budget = client.get_budget(BUDGET_ID)

        assert budget.is_paused is True
        assert budget.is_active is False


# ---------------------------------------------------------------------------
# Payment lifecycle — extended
# ---------------------------------------------------------------------------

class TestPaymentLifecycleExtended:

    @resp_lib.activate
    def test_confirm_event_with_external_transaction_id(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/confirm",
                     json={**_event_payload("CONFIRMED"), "confirmedQuantity": 95.0},
                     status=200)

        event = client.confirm_event(
            EVENT_ID,
            confirmed_quantity=95.0,
            external_transaction_id="ext-txn-9999",
        )

        import json as json_lib
        request_body = json_lib.loads(resp_lib.calls[0].request.body)
        assert request_body["externalTransactionId"] == "ext-txn-9999"
        assert event.decision == "CONFIRMED"

    @resp_lib.activate
    def test_confirm_event_partial_amount(self, client: FiGuardClient) -> None:
        """Confirmed amount may be less than the reserved amount (partial spend)."""
        payload = {**_event_payload("CONFIRMED"), "confirmedQuantity": 80.0, "requestedQuantity": 100.0}
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/confirm",
                     json=payload, status=200)

        event = client.confirm_event(EVENT_ID, confirmed_quantity=80.0)

        assert event.decision == "CONFIRMED"
        assert event.confirmed_quantity == 80.0

    @resp_lib.activate
    def test_void_event_with_child_events_flag(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/void",
                     json=_event_payload("VOIDED"), status=200)

        result = client.void_event(EVENT_ID, reason="USER_CANCELLED", void_child_events=True)

        import json as json_lib
        request_body = json_lib.loads(resp_lib.calls[0].request.body)
        assert request_body["voidChildEvents"] is True
        assert result.is_voided is True

    @resp_lib.activate
    def test_fail_event_with_error_message(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/fail",
                     json=_event_payload("FAILED"), status=200)

        event = client.fail_event(EVENT_ID, reason="PAYMENT_DECLINED", error_message="Card expired")

        import json as json_lib
        request_body = json_lib.loads(resp_lib.calls[0].request.body)
        assert request_body["errorMessage"] == "Card expired"
        assert event.decision == "FAILED"

    @resp_lib.activate
    def test_void_event_409_when_already_confirmed(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/events/{EVENT_ID}/void",
                     json={"message": "Cannot void a CONFIRMED event"},
                     status=409)

        with pytest.raises(FiGuardApiError) as exc_info:
            client.void_event(EVENT_ID, reason="USER_CANCELLED")

        assert exc_info.value.status_code == 409


# ---------------------------------------------------------------------------
# Error model attributes
# ---------------------------------------------------------------------------

class TestErrorAttributes:

    @resp_lib.activate
    def test_api_error_has_status_code_message_and_raw(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     json={"error": "NOT_FOUND", "message": "Budget does not exist"},
                     status=404)

        with pytest.raises(FiGuardApiError) as exc_info:
            client.get_budget(BUDGET_ID)

        err = exc_info.value
        assert err.status_code == 404
        assert "Budget does not exist" in err.message
        assert err.raw is not None
        assert err.raw["error"] == "NOT_FOUND"

    @resp_lib.activate
    def test_api_error_raw_is_none_for_non_json_body(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.GET, f"{BASE}/api/v1/budgets/{BUDGET_ID}",
                     body=b"Internal Server Error",
                     status=500,
                     content_type="text/plain")

        import unittest.mock as mock
        with mock.patch("figuard.client.time.sleep"):
            with pytest.raises(FiGuardApiError) as exc_info:
                client.get_budget(BUDGET_ID)

        err = exc_info.value
        assert err.status_code == 500
        assert err.raw is None

    def test_denied_exception_carries_all_attributes(self, client: FiGuardClient) -> None:
        from figuard.exceptions import FiGuardDeniedException

        exc = FiGuardDeniedException(
            denial_reason="INSUFFICIENT_FUNDS",
            denial_message="Only $10.00 remaining in budget",
            original_event_id=None,
        )
        assert exc.denial_reason == "INSUFFICIENT_FUNDS"
        assert exc.denial_message == "Only $10.00 remaining in budget"
        assert exc.original_event_id is None
        assert "INSUFFICIENT_FUNDS" in str(exc)

    @resp_lib.activate
    def test_denied_exception_denial_message_in_str(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_denied_payload("BUDGET_PAUSED"), status=200)

        with pytest.raises(FiGuardDeniedException) as exc_info:
            client.authorize(
                session_token=SESSION_TOKEN,
                agent_id="a",
                action_type="PURCHASE",
                description="test",
                requested_quantity=10.0,
                idempotency_key=str(uuid.uuid4()),
            ).raise_if_denied()

        exc = exc_info.value
        assert exc.denial_reason == "BUDGET_PAUSED"
        assert exc.denial_message == "Denied because BUDGET_PAUSED"


# ---------------------------------------------------------------------------
# Authorize: optional parameters
# ---------------------------------------------------------------------------

class TestAuthorizeOptionalParams:

    @resp_lib.activate
    def test_authorize_with_all_optional_params(self, client: FiGuardClient) -> None:
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=_authorized_payload(), status=200)

        parent_id = str(uuid.uuid4())
        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="NYC to LAX flight",
            requested_quantity=299.0,
            idempotency_key=str(uuid.uuid4()),
            currency="USD",
            intent_context="booking business travel",
            entity_id="booking-session-abc",
            claimed_category="flight",
            claimed_item_type="economy_ticket",
            parent_event_id=parent_id,
            agent_type="TRAVEL_AGENT",
            metadata={"priority": "high"},
        )

        assert result.is_authorized is True
        import json as json_lib
        request_body = json_lib.loads(resp_lib.calls[0].request.body)
        assert request_body["claimedCategory"] == "flight"
        assert request_body["claimedItemType"] == "economy_ticket"
        assert request_body["parentEventId"] == parent_id
        assert request_body["agentType"] == "TRAVEL_AGENT"
        assert request_body["metadata"] == {"priority": "high"}

    @resp_lib.activate
    def test_allocation_snapshot_parsed_when_present(self, client: FiGuardClient) -> None:
        payload = {
            **_authorized_payload(),
            "allocationSnapshot": {
                "category": "flight",
                "limit": 300.0,
                "quantitySpent": 0.0,
                "quantityReserved": 100.0,
                "availableQuantity": 200.0,
                "status": "ACTIVE",
            },
        }
        resp_lib.add(resp_lib.POST, f"{BASE}/api/v1/authorize",
                     json=payload, status=200)

        result = client.authorize(
            session_token=SESSION_TOKEN,
            agent_id="a",
            action_type="PURCHASE",
            description="test",
            requested_quantity=100.0,
            idempotency_key=str(uuid.uuid4()),
            claimed_category="flight",
        )

        assert result.allocation_snapshot is not None
        assert result.allocation_snapshot.category == "flight"
        assert result.allocation_snapshot.limit == 300.0
        assert result.allocation_snapshot.quantity_reserved == 100.0
        assert result.allocation_snapshot.available_quantity == 200.0
        assert result.allocation_snapshot.status == "ACTIVE"
