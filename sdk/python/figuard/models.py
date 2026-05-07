"""
Typed data-transfer objects returned by FiGuardClient.

All models are plain dataclasses — immutable, no ORM dependency.
Field names follow the JSON snake_case convention used by the API.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .exceptions import FiGuardDeniedException


# ---------------------------------------------------------------------------
# Budget
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class AllocationResponse:
    id: str
    category: str
    allowed_categories: List[str]
    limit: float
    amount_spent: float
    amount_reserved: float
    available_amount: float
    status: str
    enforcement_mode: str
    forbidden_item_types: Optional[List[str]] = None


@dataclass(frozen=True)
class Budget:
    id: str
    user_id: str
    total_limit: float
    currency: str
    amount_spent: float
    amount_reserved: float
    available_amount: float
    status: str
    expires_at: str
    session_token_prefix: str
    created_at: Optional[str] = None
    intent_context: Optional[str] = None
    intent_tags: Optional[List[str]] = None
    external_reference: Optional[str] = None
    soft_limit: Optional[float] = None
    max_transaction_amount: Optional[float] = None
    allocations: List[AllocationResponse] = field(default_factory=list)
    cancelled_at: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    # Only present immediately after create_budget(); None on all subsequent reads.
    session_token: Optional[str] = None

    @property
    def is_active(self) -> bool:
        return self.status == "ACTIVE"

    @property
    def is_paused(self) -> bool:
        return self.status == "PAUSED"


# ---------------------------------------------------------------------------
# Authorization
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class BudgetSnapshot:
    total_limit: float
    amount_spent: float
    amount_reserved: float
    available_amount: float
    status: str


@dataclass(frozen=True)
class AllocationSnapshot:
    category: str
    limit: float
    amount_spent: float
    amount_reserved: float
    available_amount: float
    status: str


@dataclass(frozen=True)
class AuthorizationResult:
    """
    Returned by ``FiGuardClient.authorize()``.

    Use ``is_authorized`` to check outcome; use ``raise_if_denied()`` to turn a
    denial into an exception for exception-driven control flow.
    """

    event_id: str
    decision: str
    budget_snapshot: Optional[BudgetSnapshot] = None
    allocation_snapshot: Optional[AllocationSnapshot] = None
    approved_amount: Optional[float] = None
    authorized_at: Optional[str] = None
    denial_reason: Optional[str] = None
    denial_message: Optional[str] = None
    # Set when denial_reason == "ENTITY_ALREADY_AUTHORIZED"
    original_event_id: Optional[str] = None
    original_event_status: Optional[str] = None

    @property
    def is_authorized(self) -> bool:
        """True when decision is AUTHORIZED."""
        return self.decision == "AUTHORIZED"

    def raise_if_denied(self) -> "AuthorizationResult":
        """
        Raise ``FiGuardDeniedException`` if the decision is DENIED.
        Returns self if authorized, so it can be used fluently::

            result = client.authorize(...).raise_if_denied()
        """
        if not self.is_authorized:
            raise FiGuardDeniedException(
                denial_reason=self.denial_reason or "UNKNOWN",
                denial_message=self.denial_message,
                original_event_id=self.original_event_id,
            )
        return self


# ---------------------------------------------------------------------------
# Spend events
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SpendEventResponse:
    id: str
    decision: str
    requested_amount: float
    currency: str
    created_at: str
    agent_id: Optional[str] = None
    agent_type: Optional[str] = None
    action_type: Optional[str] = None
    description: Optional[str] = None
    confirmed_amount: Optional[float] = None
    entity_id: Optional[str] = None
    claimed_category: Optional[str] = None
    claimed_item_type: Optional[str] = None
    intent_context: Optional[str] = None
    idempotency_key: Optional[str] = None
    denial_reason: Optional[str] = None
    failure_reason: Optional[str] = None
    parent_event_id: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None


# ---------------------------------------------------------------------------
# Void
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class VoidResult:
    """Thin wrapper so void has a distinct return type from confirm/fail."""
    event: SpendEventResponse

    @property
    def is_voided(self) -> bool:
        return self.event.decision == "VOIDED"


# ---------------------------------------------------------------------------
# Ledger
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class LedgerPage:
    events: List[SpendEventResponse]
    total_elements: int
    total_pages: int
    page: int
    size: int

    @property
    def has_next(self) -> bool:
        return self.page < self.total_pages - 1


# ---------------------------------------------------------------------------
# Spend tree
# ---------------------------------------------------------------------------

@dataclass
class SpendTreeNode:
    event: SpendEventResponse
    children: List["SpendTreeNode"] = field(default_factory=list)


@dataclass(frozen=True)
class SpendTree:
    budget_id: str
    roots: List[SpendTreeNode]
    total_events: int
