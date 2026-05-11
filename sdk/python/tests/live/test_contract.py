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
        Verifies: id, user_id, total_limit, currency, quantity_spent,
        quantity_reserved, available_quantity, status, expires_at,
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
        assert budget.quantity_spent == 0.0
        assert budget.quantity_reserved == 0.0
        assert budget.available_quantity == 100.00
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
        assert flight.quantity_spent == 0.0
        assert flight.quantity_reserved == 0.0
        assert flight.available_quantity == 150.00
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
        event_id, decision, approved_quantity, budget_snapshot with all sub-fields.
        """
        result = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="contract test authorization",
            requested_quantity=25.00,
            idempotency_key=str(uuid4()),
        )

        assert isinstance(result, AuthorizationResult)
        assert result.event_id and len(result.event_id) == 36
        assert result.decision == "AUTHORIZED"
        assert result.is_authorized is True
        assert result.approved_quantity == 25.00
        assert result.denial_reason is None
        assert result.denial_message is None

        # Budget snapshot must always be present on authorized response
        snap = result.budget_snapshot
        assert snap is not None
        assert snap.total_limit == 500.00
        assert snap.quantity_reserved == 25.00
        assert snap.quantity_spent == 0.0
        assert snap.available_quantity == 475.00
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
            requested_quantity=999.00,    # way over the $10 limit
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
            requested_quantity=50.00,
            claimed_category="flight",
            idempotency_key=str(uuid4()),
        )

        assert result.is_authorized is True
        alloc = result.allocation_snapshot
        assert alloc is not None
        assert alloc.category == "flight"
        assert alloc.limit == 300.00
        assert alloc.quantity_reserved == 50.00
        assert alloc.quantity_spent == 0.0
        assert alloc.available_quantity == 250.00
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
            requested_quantity=30.00,
            idempotency_key=key,
        )

        r2 = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="duplicate call",
            requested_quantity=30.00,
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
            requested_quantity=40.00,
            idempotency_key=str(uuid4()),
        )

        event = client.confirm_event(auth.event_id, confirmed_quantity=40.00)

        assert isinstance(event, SpendEventResponse)
        assert event.id == auth.event_id
        assert event.decision == "CONFIRMED"
        assert event.confirmed_quantity == 40.00
        assert event.requested_quantity == 40.00
        assert event.currency == "USD"
        assert event.created_at   # non-empty

    def test_fail_event_response_shape(
        self, client: FiGuardClient, flat_budget
    ):
        """
        fail_event must return a SpendEventResponse with decision FAILED.
        Reserved funds must be released (budget available_quantity restored).
        """
        auth = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="fail contract test",
            requested_quantity=60.00,
            idempotency_key=str(uuid4()),
        )

        budget_after_auth = client.get_budget(flat_budget.id)
        assert budget_after_auth.quantity_reserved >= 60.00

        event = client.fail_event(auth.event_id, reason="PAYMENT_DECLINED")

        assert isinstance(event, SpendEventResponse)
        assert event.id == auth.event_id
        assert event.decision == "FAILED"

        # Reserved funds must be released
        budget_after_fail = client.get_budget(flat_budget.id)
        assert budget_after_fail.quantity_reserved < budget_after_auth.quantity_reserved


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
            requested_quantity=10.00,
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
        assert event.requested_quantity == 10.00
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
            requested_quantity=5.00,
            idempotency_key=key,
        )
        client.confirm_event(auth.event_id, confirmed_quantity=5.00)

        confirmed_page = client.get_ledger(
            flat_budget.id, decision="CONFIRMED", size=50
        )

        assert all(e.decision == "CONFIRMED" for e in confirmed_page.events)
        assert any(e.id == auth.event_id for e in confirmed_page.events)


# ---------------------------------------------------------------------------
# Spend tree contract
# ---------------------------------------------------------------------------

class TestSpendTreeContract:
    """Validate get_spend_tree response shape."""

    def test_spend_tree_response_shape(self, client: FiGuardClient, flat_budget):
        """
        get_spend_tree must return a SpendTree with budget_id, roots list,
        and total_events count. Each node must have an event and children list.
        """
        from figuard.models import SpendTree

        auth = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="tree contract test",
            requested_quantity=15.00,
            idempotency_key=str(uuid4()),
        )
        client.confirm_event(auth.event_id, confirmed_quantity=15.00)

        tree = client.get_spend_tree(flat_budget.id)

        assert isinstance(tree, SpendTree)
        assert tree.budget_id == flat_budget.id
        assert isinstance(tree.roots, list)
        assert isinstance(tree.total_events, int)
        assert tree.total_events >= 1

        # Verify at least one root node has the correct shape
        confirmed_node = None
        for root in tree.roots:
            if root.event.id == auth.event_id:
                confirmed_node = root
                break

        assert confirmed_node is not None
        assert confirmed_node.event.id == auth.event_id
        assert confirmed_node.event.decision == "CONFIRMED"
        assert confirmed_node.event.agent_id == "contract_agent"
        assert confirmed_node.event.action_type == "TOOL_CALL"
        assert isinstance(confirmed_node.children, list)

    def test_spend_tree_empty_for_new_budget(self, client: FiGuardClient):
        """
        A newly created budget with no events must have an empty roots list
        and total_events == 0.
        """
        from datetime import datetime, timedelta, timezone
        budget = client.create_budget(
            user_id="contract_test_user",
            total_limit=100.00,
            expires_at=(
                datetime.now(timezone.utc) + timedelta(hours=23)
            ).strftime("%Y-%m-%dT%H:%M:%SZ"),
        )

        tree = client.get_spend_tree(budget.id)

        assert tree.roots == []
        assert tree.total_events == 0


# ---------------------------------------------------------------------------
# Rotate session token contract
# ---------------------------------------------------------------------------

class TestRotateSessionTokenContract:
    """Validate rotate_session_token response shape."""

    def test_rotate_returns_new_token_string(self, client: FiGuardClient, flat_budget):
        """
        rotate_session_token must return a non-empty string that starts with
        the session token prefix.
        """
        new_token = client.rotate_session_token(flat_budget.id)

        assert isinstance(new_token, str)
        assert len(new_token) > 10
        assert new_token.startswith("st_")
        # New token must differ from the original
        assert new_token != flat_budget.session_token

    def test_new_token_can_authorize(self, client: FiGuardClient, flat_budget):
        """
        After rotation, the new token must successfully authorize a spend.
        """
        new_token = client.rotate_session_token(flat_budget.id)

        result = client.authorize(
            session_token=new_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="post-rotation authorization",
            requested_quantity=10.00,
            idempotency_key=str(uuid4()),
        )

        assert result.is_authorized is True


# ---------------------------------------------------------------------------
# Void event contract
# ---------------------------------------------------------------------------

class TestVoidEventContract:
    """Validate void_event response shape."""

    def test_void_event_response_shape(self, client: FiGuardClient, flat_budget):
        """
        void_event must return a VoidResult with decision VOIDED.
        The reservation must be released (available_quantity restored).
        """
        from figuard.models import VoidResult

        auth = client.authorize(
            session_token=flat_budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="void contract test",
            requested_quantity=50.00,
            idempotency_key=str(uuid4()),
        )

        budget_after_auth = client.get_budget(flat_budget.id)
        assert budget_after_auth.quantity_reserved >= 50.00

        result = client.void_event(auth.event_id, reason="USER_CANCELLED")

        assert isinstance(result, VoidResult)
        assert result.is_voided is True
        assert result.event.id == auth.event_id
        assert result.event.decision == "VOIDED"

        # Reserved funds must be released
        budget_after_void = client.get_budget(flat_budget.id)
        assert budget_after_void.quantity_reserved < budget_after_auth.quantity_reserved


# ---------------------------------------------------------------------------
# Budget optional fields contract
# ---------------------------------------------------------------------------

class TestBudgetOptionalFieldsContract:
    """Validate server returns expected fields when optional budget params are set."""

    def test_create_budget_with_metadata(self, client: FiGuardClient):
        """
        metadata is stored and returned by the server.
        """
        from datetime import datetime, timedelta, timezone
        budget = client.create_budget(
            user_id="contract_test_user",
            total_limit=100.00,
            expires_at=(
                datetime.now(timezone.utc) + timedelta(hours=23)
            ).strftime("%Y-%m-%dT%H:%M:%SZ"),
            metadata={"department": "engineering", "project": "contract-test"},
        )

        # Server may or may not echo metadata — verify it doesn't crash the parser
        assert budget.id
        assert budget.status == "ACTIVE"

    def test_create_budget_with_anomaly_detection(self, client: FiGuardClient):
        """
        Anomaly-detection-enabled budgets are created and authorized normally.
        """
        from datetime import datetime, timedelta, timezone
        budget = client.create_budget(
            user_id="contract_test_user",
            total_limit=500.00,
            expires_at=(
                datetime.now(timezone.utc) + timedelta(hours=23)
            ).strftime("%Y-%m-%dT%H:%M:%SZ"),
            anomaly_detection_enabled=True,
        )

        assert budget.status == "ACTIVE"
        assert budget.session_token is not None

        result = client.authorize(
            session_token=budget.session_token,
            agent_id="contract_agent",
            action_type="TOOL_CALL",
            description="anomaly detection test",
            requested_quantity=50.00,
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized is True
