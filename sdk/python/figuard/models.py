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
    quantity_spent: float
    quantity_reserved: float
    available_quantity: float
    status: str
    enforcement_mode: str
    forbidden_item_types: Optional[List[str]] = None


@dataclass(frozen=True)
class BudgetToken:
    """A single session token entry returned in the ``tokens`` list on budget creation."""
    category: str
    session_token: Optional[str] = None
    session_token_prefix: Optional[str] = None
    unit: Optional[str] = None
    currency: Optional[str] = None


@dataclass(frozen=True)
class Budget:
    id: str
    user_id: str
    total_limit: float
    quantity_spent: float
    quantity_reserved: float
    available_quantity: float
    status: str
    expires_at: str
    # Exactly one of currency or unit is set
    currency: Optional[str] = None
    unit: Optional[str] = None
    created_at: Optional[str] = None
    intent_context: Optional[str] = None
    intent_tags: Optional[List[str]] = None
    external_reference: Optional[str] = None
    soft_limit: Optional[float] = None
    max_transaction_quantity: Optional[float] = None
    authorization_expiry_seconds: Optional[int] = None
    velocity_max_per_minute: Optional[int] = None
    velocity_max_amount_per_hour: Optional[float] = None
    velocity_max_per_day: Optional[int] = None
    allocations: List[AllocationResponse] = field(default_factory=list)
    cancelled_at: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    # List of session tokens — only populated immediately after create_budget().
    # Use primary_token or the session_token convenience shim for the common case.
    tokens: Optional[List[BudgetToken]] = None

    @property
    def primary_token(self) -> Optional[BudgetToken]:
        """Return the first token in the tokens list, or None if tokens is absent."""
        return self.tokens[0] if self.tokens else None

    @property
    def session_token(self) -> Optional[str]:
        # Deprecated: use budget.primary_token.session_token or budget.tokens[0].session_token.
        # This shim remains for backwards compatibility during the transition period.
        return self.primary_token.session_token if self.primary_token else None

    @property
    def is_active(self) -> bool:
        return self.status == "ACTIVE"

    @property
    def is_paused(self) -> bool:
        return self.status == "PAUSED"

    @property
    def is_monetary(self) -> bool:
        """True for currency-based budgets; False for resource budgets (unit set)."""
        return self.currency is not None and self.currency.strip() != ""


# ---------------------------------------------------------------------------
# Authorization
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class BudgetSnapshot:
    total_limit: float
    quantity_spent: float
    quantity_reserved: float
    available_quantity: float
    status: str


@dataclass(frozen=True)
class AllocationSnapshot:
    category: str
    limit: float
    quantity_spent: float
    quantity_reserved: float
    available_quantity: float
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
    approved_quantity: Optional[float] = None
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
    requested_quantity: float
    created_at: str
    agent_id: Optional[str] = None
    agent_type: Optional[str] = None
    action_type: Optional[str] = None
    description: Optional[str] = None
    confirmed_quantity: Optional[float] = None
    # currency is None for resource budgets (unit-based)
    currency: Optional[str] = None
    entity_id: Optional[str] = None
    claimed_category: Optional[str] = None
    claimed_item_type: Optional[str] = None
    intent_context: Optional[str] = None
    idempotency_key: Optional[str] = None
    denial_reason: Optional[str] = None
    failure_reason: Optional[str] = None
    parent_event_id: Optional[str] = None
    trace_id: Optional[str] = None
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


@dataclass(frozen=True)
class VoidTreeResult:
    """
    Returned by ``void_tree()`` — summarises the entire causal subtree that was
    atomically voided.
    """
    root_event_id: str
    voided_count: int
    total_quantity_released: float
    voided_event_ids: List[str]
    reason: str
    currency: Optional[str] = None  # None for unit-based budgets


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


# ---------------------------------------------------------------------------
# Delegation tokens
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class DelegationTokenAllocation:
    id: str
    category: str
    total_limit: float
    quantity_spent: float
    quantity_reserved: float
    available_quantity: float


@dataclass(frozen=True)
class DelegationToken:
    id: str
    parent_budget_id: str
    label: str
    status: str
    session_token_prefix: str
    caps: List[DelegationTokenAllocation]
    # Only present immediately after create_delegation_token(). None on all subsequent reads.
    session_token: Optional[str] = None
    revoked_at: Optional[str] = None
    created_at: Optional[str] = None

    @property
    def is_active(self) -> bool:
        return self.status == "ACTIVE"

    @property
    def is_revoked(self) -> bool:
        return self.status == "REVOKED"


# ---------------------------------------------------------------------------
# Fund budget
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class BudgetFundingResult:
    """Returned by fund_budget(). Shows state before and after the operation."""
    budget_id: str
    operation: str  # CREDIT | DEBIT | RESET | RESET_SPENT
    amount: float
    previous_total_limit: float
    total_limit: float
    quantity_spent: float
    quantity_reserved: float
    available_quantity: float
    status: str
    reason: Optional[str] = None
    updated_at: Optional[str] = None
    trace_id: Optional[str] = None


# ---------------------------------------------------------------------------
# API keys
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ApiKey:
    """
    Represents a tenant API key.

    ``raw_key`` is only populated immediately after creation or rotation.
    Store it securely — it cannot be retrieved again.
    """
    id: str
    key_prefix: str
    active: bool
    description: Optional[str] = None
    created_at: Optional[str] = None
    last_used_at: Optional[str] = None
    # fg_live_... — returned ONCE at creation/rotation. None on all subsequent reads.
    raw_key: Optional[str] = None


# ---------------------------------------------------------------------------
# Subscriptions & Entitlements
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class EntitlementItem:
    id: str
    category: str
    period_limit: float
    overage_policy: str          # BLOCK | WARN_ONLY
    renewal_period: str          # MONTHLY | QUARTERLY | ANNUALLY
    current_period_consumed: float
    current_period_reserved: float
    state: str                   # NORMAL | APPROACHING | LIMIT_REACHED
    warn_at_percentage: Optional[float] = None
    next_renewal_at: Optional[str] = None
    created_at: Optional[str] = None

    @property
    def available(self) -> float:
        return max(0.0, self.period_limit - self.current_period_consumed - self.current_period_reserved)


@dataclass(frozen=True)
class Subscription:
    id: str
    external_subscriber_id: str
    plan: str
    status: str                  # ACTIVE | PAUSED | CANCELLED
    renewal_period: str
    entitlements: List[EntitlementItem]
    starts_at: Optional[str] = None
    created_at: Optional[str] = None
    updated_at: Optional[str] = None
