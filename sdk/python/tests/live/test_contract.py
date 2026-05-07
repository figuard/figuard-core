"""
Server contract tests.

These tests verify that the server's actual response shapes match what the
SDK's parsers expect. They catch the class of bug where unit-test mocks assume
a field exists that the real server doesn't return (or vice versa).

Every field the SDK reads from an API response must be covered here.

Run:
    make run                        # start figuard-core container
    pytest tests/live/test_contract.py -v
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from figuard import FiGuardClient
from figuard.models import (
    AuthorizationResult,
    Budget,
    LedgerPage,
    SpendEventResponse,
    SpendTree,
)


def _expires_at() -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# Budget response contract
# ---------------------------------------------------------------------------

class TestBudgetContract:
    """Validate every field Budget parser reads from the server."""

    def test_create_budget_flat_response_shape(self, client: FiGuardClient):
        """
        create_budget returns a Budget with all expected fields populated.
        Verifies: id, user_id, total_limit, currency, amount_spent,
        amount_reserved, available_amount, status, expires_at,
        session_token_prefix, session_token (first call only), allocations.
        """
        budget = client.create_budget(
            user_id="contract_test_user",
            total_limit=100.00,
            expires_at=_expires_at(),
        )

        assert isinstance(budget, Budget)
        assert budget.id and len(budget.id) == 36          # UUID
        assert budget.user_id == "contract_test_user"
        assert budget.total_limit == 100.00
        assert budget.currency == "USD"
        assert budget.amount_spent == 0.0
        assert budget.amount_reserved == 0.0
        assert budget.available_amount == 100.00
        assert budget.status == "ACTIVE"
        assert budget.expires_at                            # non-empty ISO string
        assert budget.session_token_prefix                  # e.g. "st_abc123"
        # session_token only on create — must be present and non-empty
        assert budget.session_token and budget.session_token.startswith("st_")
        assert budget.allocations == []
        # created_at may or may not be present — do not assert on it

    def test_get_budget_has_no_session_token(self, client: FiGuardClient):
        """
        get_budget must NOT return session_token (security requirement).
        The session_token field must be None on all reads after creation.
        """
        budget = client.create_budget(
            user_id="contract_test_user",
            total_limit=50.00,
            expires_at=_expires_at(),
        )

        fetched = client.get_budget(budget.id)

        assert isinstance(fetched, Budget)
        assert fetched.id == budget.id
        assert fetched.session_token is None

    def test_create_budget_with_allocations_response_shape(
        self, client: FiGuardClient
    ):
        """
        Allocation objects in the response must have all fields the
        AllocationResponse parser expects.
        """
        budget = client.create_budget(
            user_id="contract_test_user",
            total_limit=200.00,
            expires_at=_expires_at(),
            allocations=[
                {
                    "category": "flight",
                    "allowedCategories": ["flight"],
                    "limit": 150.00,
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                },
                {
                    "category": "hotel",
                    "allowedCategories": ["hotel"],
                    "limit": 50.00,
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                },
            ],
        )

        assert len(budget.allocations) == 2

        flight = next(a for a in budget.allocations if a.category == "flight")
        assert flight.id                                    # UUID
        assert flight.category == "flight"
        assert flight.allowed_categories == ["flight"]
        assert flight.limit == 150.00
        assert flight.amount_spent == 0.0
        assert flight.amount_reserved == 0.0
        assert flight.available_amount == 150.00
        assert flight.status == "ACTIVE"
        assert flight.enforcement_mode == "CATEGORY_CONSTRAINED"


# ---------------------------------------------------------------------------
# Authorization response contract
# ---------------------------------------------------------------------------

class TestAuthorizationContract:
    """Validate every field AuthorizationResult parser reads from the server."""

    def test_authorized_response_shape(self, client: FiGuardClient, flat_budget):
        """
        An authorized call must return all fields the parser expects:
        event_id, decision, approved_amount, budget_snapshot with all sub-fields.
        """
        result = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="contract test authorization",
            requested_amount=25.00,
            idempotency_key=str(uuid4()),
        )

        assert isinstance(result, AuthorizationResult)
        assert result.event_id and len(result.event_id) == 36
        assert result.decision == "AUTHORIZED"
        assert result.is_authorized is True
        assert result.approved_amount == 25.00
        assert result.denial_reason is None
        assert result.denial_message is None

        # Budget snapshot must always be present on authorized response
        snap = result.budget_snapshot
        assert snap is not None
        assert snap.total_limit == 500.00
        assert snap.amount_reserved == 25.00
        assert snap.amount_spent == 0.0
        assert snap.available_amount == 475.00
        assert snap.status == "ACTIVE"

    def test_denied_response_shape(self, client: FiGuardClient, tiny_budget):
        """
        A denied call (INSUFFICIENT_FUNDS) must have denial_reason populated
        and is_authorized False. event_id must still be present.
        """
        result = client.authorize(
            session_token=tiny_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="contract test denial",
            requested_amount=999.00,    # way over the $10 limit
            idempotency_key=str(uuid4()),
        )

        assert isinstance(result, AuthorizationResult)
        assert result.event_id and len(result.event_id) == 36
        assert result.decision == "DENIED"
        assert result.is_authorized is False
        assert result.denial_reason == "INSUFFICIENT_FUNDS"

    def test_allocation_snapshot_present_on_allocation_path(
        self, client: FiGuardClient, allocated_budget
    ):
        """
        When authorization goes through an allocation, allocationSnapshot
        must be present in the response with all expected fields.
        """
        result = client.authorize(
            session_token=allocated_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="flight booking",
            requested_amount=50.00,
            claimed_category="flight",
            idempotency_key=str(uuid4()),
        )

        assert result.is_authorized is True
        alloc = result.allocation_snapshot
        assert alloc is not None
        assert alloc.category == "flight"
        assert alloc.limit == 300.00
        assert alloc.amount_reserved == 50.00
        assert alloc.amount_spent == 0.0
        assert alloc.available_amount == 250.00
        assert alloc.status == "ACTIVE"

    def test_idempotency_returns_same_event_id(
        self, client: FiGuardClient, flat_budget
    ):
        """
        Duplicate idempotency key must return the original event_id and decision,
        not create a new event.
        """
        key = str(uuid4())

        r1 = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="first call",
            requested_amount=30.00,
            idempotency_key=key,
        )

        r2 = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="duplicate call",
            requested_amount=30.00,
            idempotency_key=key,
        )

        assert r1.event_id == r2.event_id
        assert r1.decision == r2.decision


# ---------------------------------------------------------------------------
# Payment lifecycle contract
# ---------------------------------------------------------------------------

class TestPaymentLifecycleContract:
    """Validate confirm_event and fail_event response shapes."""

    def test_confirm_event_response_shape(
        self, client: FiGuardClient, flat_budget
    ):
        """
        confirm_event must return a SpendEventResponse with decision CONFIRMED
        and confirmedAmount populated.
        """
        auth = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="confirm contract test",
            requested_amount=40.00,
            idempotency_key=str(uuid4()),
        )

        event = client.confirm_event(auth.event_id, confirmed_amount=40.00)

        assert isinstance(event, SpendEventResponse)
        assert event.id == auth.event_id
        assert event.decision == "CONFIRMED"
        assert event.confirmed_amount == 40.00
        assert event.requested_amount == 40.00
        assert event.currency == "USD"
        assert event.created_at   # non-empty

    def test_fail_event_response_shape(
        self, client: FiGuardClient, flat_budget
    ):
        """
        fail_event must return a SpendEventResponse with decision FAILED.
        Reserved funds must be released (budget available_amount restored).
        """
        auth = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="fail contract test",
            requested_amount=60.00,
            idempotency_key=str(uuid4()),
        )

        budget_after_auth = client.get_budget(flat_budget.id)
        assert budget_after_auth.amount_reserved >= 60.00

        event = client.fail_event(auth.event_id, reason="PAYMENT_DECLINED")

        assert isinstance(event, SpendEventResponse)
        assert event.id == auth.event_id
        assert event.decision == "FAILED"

        # Reserved funds must be released
        budget_after_fail = client.get_budget(flat_budget.id)
        assert budget_after_fail.amount_reserved < budget_after_auth.amount_reserved


# ---------------------------------------------------------------------------
# Ledger contract
# ---------------------------------------------------------------------------

class TestLedgerContract:
    """Validate get_ledger response shape and pagination fields."""

    def test_ledger_response_shape(self, client: FiGuardClient, flat_budget):
        """
        get_ledger must return a LedgerPage with correct pagination metadata
        and SpendEventResponse objects with all expected fields.
        """
        # Create an event to ensure the ledger is non-empty
        auth = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="ledger contract test",
            requested_amount=10.00,
            idempotency_key=str(uuid4()),
        )

        page = client.get_ledger(flat_budget.id, page=0, size=20)

        assert isinstance(page, LedgerPage)
        assert page.total_elements >= 1
        assert page.total_pages >= 1
        assert page.page == 0
        assert page.size == 20
        assert len(page.events) >= 1

        event = next(e for e in page.events if e.id == auth.event_id)
        assert event.id == auth.event_id
        assert event.decision == "AUTHORIZED"
        assert event.requested_amount == 10.00
        assert event.currency == "USD"
        assert event.created_at   # non-empty ISO string
        assert event.agent_id == "contract_agent"
        assert event.action_type == "TOOL_CALL"

    def test_ledger_decision_filter(self, client: FiGuardClient, flat_budget):
        """
        Filtering by decision must return only events with that decision.
        """
        key = str(uuid4())
        auth = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="filter test",
            requested_amount=5.00,
            idempotency_key=key,
        )
        client.confirm_event(auth.event_id, confirmed_amount=5.00)

        confirmed_page = client.get_ledger(
            flat_budget.id, decision="CONFIRMED", size=50
        )

        assert all(e.decision == "CONFIRMED" for e in confirmed_page.events)
        assert any(e.id == auth.event_id for e in confirmed_page.events)
