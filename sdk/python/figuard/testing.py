"""
MockFiGuardClient — in-memory FiGuard client for unit tests.

No network calls. Enforces budget limits, tracks reservations and
confirmations, handles idempotency and entity dedup — same behavioral
contract as the real server for the happy path and common denial cases.

Usage::

    from figuard.testing import MockFiGuardClient
    from figuard import DenialReason

    def test_flight_booking():
        client = MockFiGuardClient(total_limit=500, currency="USD")

        result = client.authorize(
            session_token=client.sandbox_token,
            agent_id="travel_agent",
            action_type="PURCHASE",
            description="NYC flight",
            requested_quantity=300,
            idempotency_key="txn-001",
        )
        assert result.is_authorized
        client.confirm_event(result.event_id, confirmed_quantity=300)

        # Over budget
        result2 = client.authorize(
            session_token=client.sandbox_token,
            agent_id="travel_agent",
            action_type="PURCHASE",
            description="Hotel",
            requested_quantity=300,
            idempotency_key="txn-002",
        )
        assert result2.denial_reason == DenialReason.BUDGET_EXHAUSTED

        client.assert_authorized(count=1)
        client.assert_denied(reason=DenialReason.BUDGET_EXHAUSTED, count=1)
        client.assert_spent(300)
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, Iterator, List, Optional

from .models import (
    AllocationResponse,
    AuthorizationResult,
    Budget,
    BudgetSnapshot,
    BudgetToken,
    LedgerPage,
    SpendEventResponse,
    VoidResult,
    VoidTreeResult,
)
from .denial_reasons import DenialReason
from .exceptions import FiGuardDeniedException


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class _AllocationState:
    category: str
    limit: float
    reserved: float = 0.0
    spent: float = 0.0

    @property
    def available(self) -> float:
        return max(0.0, self.limit - self.reserved - self.spent)


class MockFiGuardClient:
    """
    In-memory FiGuard client for unit and integration tests.

    **What it simulates**

    - Budget exhaustion (``BUDGET_EXHAUSTED``) — tracks reservations and confirmed spend
    - Allocation enforcement (``ALLOCATION_EXHAUSTED``) — optional per-category limits
    - Idempotency — same ``idempotency_key`` returns the original result, no double-charge
    - Entity dedup (``ENTITY_ALREADY_AUTHORIZED``) — same ``entity_id`` is blocked
    - ``dry_run=True`` — same as real: no state change
    - ``confirm_event`` / ``fail_event`` / ``void_event`` / ``void_tree`` — move events
      between states and release capacity correctly

    **What it does NOT simulate**

    - Velocity limits (timing-dependent)
    - Anomaly detection or auto-pause
    - Webhooks
    - Token expiry or ``INVALID_SESSION_TOKEN``
    """

    def __init__(
        self,
        total_limit: float = 1000.0,
        currency: Optional[str] = "USD",
        unit: Optional[str] = None,
        allocations: Optional[Dict[str, float]] = None,
    ) -> None:
        """
        :param total_limit:  Total spend limit.
        :param currency:     ISO 4217 code for monetary budgets (e.g. ``"USD"``).
                             Set to ``None`` for resource budgets.
        :param unit:         Unit name for resource budgets (e.g. ``"tokens"``).
        :param allocations:  Optional per-category limits:
                             ``{"flights": 300, "hotels": 200}``.
        """
        self._total_limit = total_limit
        self._currency = currency
        self._unit = unit
        self._budget_id = _new_id("mock_bdg")
        self._token = _new_id("mock_tok")

        # Running totals
        self._reserved: float = 0.0
        self._spent: float = 0.0

        # event_id → {"event": SpendEventResponse, "reserved": float}
        self._store: Dict[str, Dict[str, Any]] = {}
        # Insertion-order list of event IDs for deterministic iteration
        self._order: List[str] = []

        # Idempotency: idempotency_key → event_id
        self._idem: Dict[str, str] = {}

        # Entity dedup: entity_id → event_id
        self._entities: Dict[str, str] = {}

        # Allocations
        self._allocs: Dict[str, _AllocationState] = {}
        if allocations:
            for cat, limit in allocations.items():
                self._allocs[cat] = _AllocationState(category=cat, limit=limit)

    # -----------------------------------------------------------------------
    # Properties
    # -----------------------------------------------------------------------

    @property
    def sandbox_token(self) -> str:
        """Pre-created session token — use directly without calling ``create_budget()``."""
        return self._token

    @property
    def events(self) -> List[SpendEventResponse]:
        """All spend events in creation order."""
        return [self._store[eid]["event"] for eid in self._order]

    @property
    def authorized_events(self) -> List[SpendEventResponse]:
        """
        Events that were ever authorized — decision is ``"AUTHORIZED"``,
        ``"CONFIRMED"``, ``"FAILED"``, or ``"VOIDED"`` (all started as AUTHORIZED).
        """
        return [e for e in self.events if e.decision in ("AUTHORIZED", "CONFIRMED", "FAILED", "VOIDED")]

    @property
    def denied_events(self) -> List[SpendEventResponse]:
        """Events with decision ``"DENIED"``."""
        return [e for e in self.events if e.decision == "DENIED"]

    @property
    def available_quantity(self) -> float:
        """Current available capacity (total_limit − reserved − spent)."""
        return max(0.0, self._total_limit - self._reserved - self._spent)

    # -----------------------------------------------------------------------
    # Budget stubs
    # -----------------------------------------------------------------------

    def create_budget(self, user_id: str = "test_user", **kwargs: Any) -> Budget:
        """
        Return a fake ``Budget``. The ``sandbox_token`` is embedded in ``tokens``.

        Accepts (and ignores) all ``create_budget`` keyword args so you can swap
        ``MockFiGuardClient`` in place of ``FiGuardClient`` without changing call sites.
        """
        return Budget(
            id=self._budget_id,
            user_id=user_id,
            total_limit=self._total_limit,
            quantity_spent=self._spent,
            quantity_reserved=self._reserved,
            available_quantity=self.available_quantity,
            status="ACTIVE",
            expires_at="2099-12-31T23:59:59Z",
            currency=self._currency,
            unit=self._unit,
            allocations=self._allocation_responses(),
            tokens=[BudgetToken(category="default", session_token=self._token)],
        )

    def get_budget(self, budget_id: str, **kwargs: Any) -> Budget:
        """Return the current mock budget state."""
        return self.create_budget()

    # -----------------------------------------------------------------------
    # Authorization
    # -----------------------------------------------------------------------

    def authorize(
        self,
        session_token: str,
        agent_id: str,
        action_type: str,
        description: str,
        requested_quantity: float,
        idempotency_key: str,
        entity_id: Optional[str] = None,
        claimed_category: Optional[str] = None,
        dry_run: bool = False,
        **kwargs: Any,
    ) -> AuthorizationResult:
        """
        Authorize a spend request against the mock budget.

        Denial precedence:
        1. Idempotency key already used — return original result
        2. Entity ID already active — ``ENTITY_ALREADY_AUTHORIZED``
        3. Category allocation exhausted — ``ALLOCATION_EXHAUSTED``
        4. Budget exhausted — ``BUDGET_EXHAUSTED``
        5. Authorized

        ``dry_run=True`` runs the checks but does not mutate any state.
        """
        # 1. Idempotency
        if idempotency_key in self._idem:
            original_id = self._idem[idempotency_key]
            original = self._store[original_id]["event"]
            return self._make_result(
                event_id=original_id,
                decision=original.decision,
                denial_reason=original.denial_reason,
            )

        # 2. Entity dedup
        if entity_id and entity_id in self._entities:
            original_id = self._entities[entity_id]
            return self._deny(
                agent_id=agent_id,
                action_type=action_type,
                description=description,
                requested_quantity=requested_quantity,
                idempotency_key=idempotency_key,
                entity_id=entity_id,
                claimed_category=claimed_category,
                denial_reason=DenialReason.ENTITY_ALREADY_AUTHORIZED,
                original_event_id=original_id,
                dry_run=dry_run,
            )

        # 3. Allocation check
        if claimed_category and claimed_category in self._allocs:
            alloc = self._allocs[claimed_category]
            if alloc.available < requested_quantity:
                return self._deny(
                    agent_id=agent_id,
                    action_type=action_type,
                    description=description,
                    requested_quantity=requested_quantity,
                    idempotency_key=idempotency_key,
                    entity_id=entity_id,
                    claimed_category=claimed_category,
                    denial_reason=DenialReason.ALLOCATION_EXHAUSTED,
                    dry_run=dry_run,
                )

        # 4. Budget check
        if self.available_quantity < requested_quantity:
            return self._deny(
                agent_id=agent_id,
                action_type=action_type,
                description=description,
                requested_quantity=requested_quantity,
                idempotency_key=idempotency_key,
                entity_id=entity_id,
                claimed_category=claimed_category,
                denial_reason=DenialReason.BUDGET_EXHAUSTED,
                dry_run=dry_run,
            )

        # 5. Authorize
        event_id = _new_id("mock_evt")
        if not dry_run:
            self._reserved += requested_quantity
            if claimed_category and claimed_category in self._allocs:
                self._allocs[claimed_category].reserved += requested_quantity
            self._idem[idempotency_key] = event_id
            if entity_id:
                self._entities[entity_id] = event_id

        self._record(
            event_id=event_id,
            agent_id=agent_id,
            action_type=action_type,
            description=description,
            requested_quantity=requested_quantity,
            idempotency_key=idempotency_key,
            decision="AUTHORIZED",
            entity_id=entity_id,
            claimed_category=claimed_category,
            reserved=requested_quantity if not dry_run else 0.0,
        )
        return self._make_result(event_id=event_id, decision="AUTHORIZED")

    # -----------------------------------------------------------------------
    # Event lifecycle
    # -----------------------------------------------------------------------

    def confirm_event(
        self,
        event_id: str,
        confirmed_quantity: Optional[float] = None,
        **kwargs: Any,
    ) -> SpendEventResponse:
        """Move an AUTHORIZED event to CONFIRMED. Releases the reservation, records actual spend."""
        entry = self._get_entry(event_id, required_decision="AUTHORIZED")
        event = entry["event"]
        qty = confirmed_quantity if confirmed_quantity is not None else event.requested_quantity
        reserved = entry["reserved"]

        self._reserved -= reserved
        self._spent += qty
        cat = event.claimed_category
        if cat and cat in self._allocs:
            self._allocs[cat].reserved -= reserved
            self._allocs[cat].spent += qty

        entry["reserved"] = 0.0
        updated = _replace_event(event, decision="CONFIRMED", confirmed_quantity=qty)
        entry["event"] = updated
        return updated

    def fail_event(
        self,
        event_id: str,
        failure_reason: str = "PAYMENT_FAILED",
        **kwargs: Any,
    ) -> SpendEventResponse:
        """Move an AUTHORIZED event to FAILED. Releases the reservation."""
        entry = self._get_entry(event_id, required_decision="AUTHORIZED")
        event = entry["event"]
        reserved = entry["reserved"]

        self._reserved -= reserved
        cat = event.claimed_category
        if cat and cat in self._allocs:
            self._allocs[cat].reserved -= reserved

        entry["reserved"] = 0.0
        updated = _replace_event(event, decision="FAILED", failure_reason=failure_reason)
        entry["event"] = updated
        return updated

    def void_event(
        self,
        event_id: str,
        reason: str = "VOIDED",
        **kwargs: Any,
    ) -> VoidResult:
        """Move an AUTHORIZED event to VOIDED. Releases the reservation and removes entity lock."""
        entry = self._get_entry(event_id, required_decision="AUTHORIZED")
        event = entry["event"]
        reserved = entry["reserved"]

        self._reserved -= reserved
        cat = event.claimed_category
        if cat and cat in self._allocs:
            self._allocs[cat].reserved -= reserved

        # Release entity lock so the same entity_id can be re-authorized
        if event.entity_id and event.entity_id in self._entities:
            del self._entities[event.entity_id]

        entry["reserved"] = 0.0
        updated = _replace_event(event, decision="VOIDED", failure_reason=reason)
        entry["event"] = updated
        return VoidResult(event=updated)

    def void_tree(self, event_id: str, reason: str = "VOIDED", **kwargs: Any) -> VoidTreeResult:
        """
        Void an AUTHORIZED event and all its AUTHORIZED descendants.

        The mock tracks ``parent_event_id`` and cascades one level deep.
        For deeper chains, use ``void_event()`` on each node manually.
        """
        to_void = [event_id] + [
            e.id for e in self.events
            if e.parent_event_id == event_id and e.decision == "AUTHORIZED"
        ]
        total_released = 0.0
        voided_ids: List[str] = []
        for eid in to_void:
            if eid in self._store and self._store[eid]["event"].decision == "AUTHORIZED":
                result = self.void_event(eid, reason=reason)
                total_released += result.event.requested_quantity
                voided_ids.append(eid)

        return VoidTreeResult(
            root_event_id=event_id,
            voided_count=len(voided_ids),
            total_quantity_released=total_released,
            currency=self._currency,
            voided_event_ids=voided_ids,
            reason=reason,
        )

    # -----------------------------------------------------------------------
    # Ledger
    # -----------------------------------------------------------------------

    def get_ledger(
        self,
        budget_id: str,
        page: int = 0,
        size: int = 20,
        decision: Optional[str] = None,
        **kwargs: Any,
    ) -> LedgerPage:
        """Return a paginated view of mock events, optionally filtered by decision."""
        all_events = self.events if not decision else [e for e in self.events if e.decision == decision]
        total = len(all_events)
        start = page * size
        page_events = all_events[start:start + size]
        total_pages = max(1, (total + size - 1) // size)
        return LedgerPage(
            events=page_events,
            total_elements=total,
            total_pages=total_pages,
            page=page,
            size=size,
            has_next=page < total_pages - 1,
        )

    def iter_events(
        self,
        budget_id: str,
        decision: Optional[str] = None,
        **kwargs: Any,
    ) -> Iterator[SpendEventResponse]:
        """Iterate over all mock events. No pagination needed — yields directly."""
        for event in self.events:
            if decision is None or event.decision == decision:
                yield event

    # -----------------------------------------------------------------------
    # Test helpers
    # -----------------------------------------------------------------------

    def assert_authorized(self, count: Optional[int] = None) -> None:
        """
        Assert that at least one AUTHORIZED event exists, or exactly ``count`` if given.

        :raises AssertionError: if the condition is not met.
        """
        authorized = self.authorized_events
        if count is not None:
            assert len(authorized) == count, (
                f"Expected {count} authorized event(s), got {len(authorized)}"
            )
        else:
            assert authorized, "Expected at least one authorized event, got none"

    def assert_denied(
        self,
        reason: Optional[str] = None,
        count: Optional[int] = None,
    ) -> None:
        """
        Assert that at least one DENIED event exists, optionally matching a specific
        ``DenialReason`` and/or exact ``count``.

        :raises AssertionError: if the condition is not met.
        """
        denied = self.denied_events
        if reason:
            denied = [e for e in denied if e.denial_reason == reason]
        if count is not None:
            label = f" with reason={reason}" if reason else ""
            assert len(denied) == count, (
                f"Expected {count} denied event(s){label}, got {len(denied)}"
            )
        else:
            label = f" with reason={reason}" if reason else ""
            assert denied, f"Expected at least one denied event{label}, got none"

    def assert_spent(self, amount: float, tolerance: float = 0.01) -> None:
        """
        Assert that confirmed spend equals ``amount`` within ``tolerance``.

        :raises AssertionError: if the condition is not met.
        """
        assert abs(self._spent - amount) <= tolerance, (
            f"Expected {amount} spent, got {self._spent}"
        )

    def assert_reserved(self, amount: float, tolerance: float = 0.01) -> None:
        """
        Assert that outstanding reservations equal ``amount`` within ``tolerance``.

        :raises AssertionError: if the condition is not met.
        """
        assert abs(self._reserved - amount) <= tolerance, (
            f"Expected {amount} reserved, got {self._reserved}"
        )

    def assert_available(self, amount: float, tolerance: float = 0.01) -> None:
        """
        Assert that available capacity equals ``amount`` within ``tolerance``.

        :raises AssertionError: if the condition is not met.
        """
        assert abs(self.available_quantity - amount) <= tolerance, (
            f"Expected {amount} available, got {self.available_quantity}"
        )

    def reset(self) -> None:
        """
        Clear all state. Call between test cases that share a client instance
        (or just construct a new ``MockFiGuardClient`` per test — cheaper).
        """
        self._reserved = 0.0
        self._spent = 0.0
        self._store.clear()
        self._order.clear()
        self._idem.clear()
        self._entities.clear()
        for alloc in self._allocs.values():
            alloc.reserved = 0.0
            alloc.spent = 0.0

    # -----------------------------------------------------------------------
    # Internals
    # -----------------------------------------------------------------------

    def _get_entry(
        self,
        event_id: str,
        required_decision: Optional[str] = None,
    ) -> Dict[str, Any]:
        entry = self._store.get(event_id)
        if not entry:
            raise ValueError(f"MockFiGuardClient: event {event_id!r} not found")
        if required_decision and entry["event"].decision != required_decision:
            actual = entry["event"].decision
            raise ValueError(
                f"MockFiGuardClient: event {event_id!r} is {actual}, expected {required_decision}"
            )
        return entry

    def _record(
        self,
        event_id: str,
        agent_id: str,
        action_type: str,
        description: str,
        requested_quantity: float,
        idempotency_key: str,
        decision: str,
        denial_reason: Optional[str] = None,
        entity_id: Optional[str] = None,
        claimed_category: Optional[str] = None,
        reserved: float = 0.0,
    ) -> SpendEventResponse:
        event = SpendEventResponse(
            id=event_id,
            decision=decision,
            requested_quantity=requested_quantity,
            created_at=_now(),
            agent_id=agent_id,
            action_type=action_type,
            description=description,
            idempotency_key=idempotency_key,
            denial_reason=denial_reason,
            entity_id=entity_id,
            claimed_category=claimed_category,
            currency=self._currency,
        )
        self._store[event_id] = {"event": event, "reserved": reserved}
        self._order.append(event_id)
        return event

    def _deny(
        self,
        agent_id: str,
        action_type: str,
        description: str,
        requested_quantity: float,
        idempotency_key: str,
        denial_reason: str,
        entity_id: Optional[str] = None,
        claimed_category: Optional[str] = None,
        original_event_id: Optional[str] = None,
        dry_run: bool = False,
    ) -> AuthorizationResult:
        event_id = _new_id("mock_evt")
        self._record(
            event_id=event_id,
            agent_id=agent_id,
            action_type=action_type,
            description=description,
            requested_quantity=requested_quantity,
            idempotency_key=idempotency_key,
            decision="DENIED",
            denial_reason=denial_reason,
            entity_id=entity_id,
            claimed_category=claimed_category,
        )
        if not dry_run:
            self._idem[idempotency_key] = event_id
        return self._make_result(
            event_id=event_id,
            decision="DENIED",
            denial_reason=denial_reason,
            original_event_id=original_event_id,
        )

    def _make_result(
        self,
        event_id: str,
        decision: str,
        denial_reason: Optional[str] = None,
        original_event_id: Optional[str] = None,
    ) -> AuthorizationResult:
        snapshot = BudgetSnapshot(
            total_limit=self._total_limit,
            quantity_spent=self._spent,
            quantity_reserved=self._reserved,
            available_quantity=self.available_quantity,
            status="ACTIVE",
        )
        return AuthorizationResult(
            event_id=event_id,
            decision=decision,
            budget_snapshot=snapshot,
            denial_reason=denial_reason,
            original_event_id=original_event_id,
        )

    def _allocation_responses(self) -> List[AllocationResponse]:
        return [
            AllocationResponse(
                id=f"mock_alloc_{a.category}",
                category=a.category,
                allowed_categories=[a.category],
                limit=a.limit,
                quantity_spent=a.spent,
                quantity_reserved=a.reserved,
                available_quantity=a.available,
                status="ACTIVE",
                enforcement_mode="CATEGORY_CONSTRAINED",
            )
            for a in self._allocs.values()
        ]


def _replace_event(event: SpendEventResponse, **overrides: Any) -> SpendEventResponse:
    """Return a new SpendEventResponse with selected fields replaced."""
    return SpendEventResponse(
        id=event.id,
        decision=overrides.get("decision", event.decision),
        requested_quantity=event.requested_quantity,
        created_at=event.created_at,
        agent_id=event.agent_id,
        action_type=event.action_type,
        description=event.description,
        confirmed_quantity=overrides.get("confirmed_quantity", event.confirmed_quantity),
        currency=event.currency,
        entity_id=event.entity_id,
        claimed_category=event.claimed_category,
        idempotency_key=event.idempotency_key,
        denial_reason=event.denial_reason,
        failure_reason=overrides.get("failure_reason", event.failure_reason),
        parent_event_id=event.parent_event_id,
        trace_id=event.trace_id,
        metadata=event.metadata,
    )
