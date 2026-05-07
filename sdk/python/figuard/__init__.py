"""
FiGuard Python SDK — pre-flight spend authorization for AI agents.

Quick start::

    from figuard import FiGuardClient, FiGuardDeniedException

    client = FiGuardClient(api_key="ab_live_...")

    budget = client.create_budget(
        user_id="user_123",
        total_limit=500.00,
        expires_at="2024-12-31T23:59:59Z",
    )

    try:
        result = client.authorize(
            session_token=budget.session_token,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="NYC flight",
            requested_amount=299.00,
            idempotency_key="txn-abc-001",
        ).raise_if_denied()
        # proceed with transaction...
        client.confirm_event(result.event_id, confirmed_amount=299.00)
    except FiGuardDeniedException as e:
        print(f"Spend denied: {e.denial_reason}")
"""

__version__ = "0.1.1"

from .client import FiGuardClient
from .exceptions import FiGuardApiError, FiGuardConnectionError, FiGuardDeniedException, FiGuardError

try:
    from .async_client import AsyncFiGuardClient  # available when aiohttp is installed
    _has_async = True
except ImportError:
    _has_async = False  # type: ignore[assignment]

from .models import (
    AllocationResponse,
    AllocationSnapshot,
    AuthorizationResult,
    Budget,
    BudgetSnapshot,
    LedgerPage,
    SpendEventResponse,
    SpendTree,
    SpendTreeNode,
    VoidResult,
)

__all__ = [
    "__version__",
    "FiGuardClient",
    # Exceptions
    "FiGuardError",
    "FiGuardApiError",
    "FiGuardDeniedException",
    "FiGuardConnectionError",
    # Models
    "Budget",
    "AuthorizationResult",
    "SpendEventResponse",
    "VoidResult",
    "LedgerPage",
    "SpendTree",
    "SpendTreeNode",
    "BudgetSnapshot",
    "AllocationSnapshot",
    "AllocationResponse",
]

if _has_async:
    __all__ += ["AsyncFiGuardClient"]
