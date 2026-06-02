"""
FiGuard Python SDK — pre-flight spend authorization for AI agents.

Zero-config demo (no account needed)::

    from figuard import FiGuardClient

    client = FiGuardClient()  # connects to shared public sandbox automatically

One-line framework wiring::

    from figuard.integrations.langchain import auto_guard_langchain
    executor = auto_guard_langchain(executor)  # $500 / 24h budget, auto-wired

    from figuard.integrations.crewai import auto_guard_crewai
    auto_guard_crewai(my_tool)  # wraps tool._run in-place

Full quickstart::

    from figuard import FiGuardClient, FiGuardDeniedException

    client = FiGuardClient(api_key="fg_live_...")

    budget = client.create_budget(
        user_id="user_123",
        total_limit=500.00,
        expires_in="24h",
        currency="USD",
    )

    try:
        result = client.authorize(
            session_token=budget.primary_token.session_token,
            agent_id="agent_001",
            action_type="PURCHASE",
            description="NYC flight",
            requested_quantity=299.00,
            idempotency_key="txn-abc-001",
        ).raise_if_denied()
        # proceed with transaction...
        client.confirm_event(result.event_id, confirmed_quantity=299.00)
    except FiGuardDeniedException as e:
        print(f"Spend denied: {e.denial_reason}")
"""

__version__ = "0.5.0"

from .client import FiGuardClient
from .exceptions import FiGuardApiError, FiGuardConnectionError, FiGuardDeniedException, FiGuardError, FiGuardWebhookVerificationError
from .composite import CompositeGuard, GuardedResource, CompositeAuthorizationResult
from .denial_reasons import DenialReason

try:
    from .async_client import AsyncFiGuardClient  # available when aiohttp is installed
    from .async_composite import AsyncCompositeGuard, AsyncGuardedResource
    _has_async = True
except ImportError:
    _has_async = False  # type: ignore[assignment]

from .models import (
    AllocationResponse,
    AllocationSnapshot,
    ApiKey,
    AuthorizationResult,
    Budget,
    BudgetFundingResult,
    BudgetSnapshot,
    BudgetStateSnapshot,
    BudgetTimeline,
    EntitlementItem,
    LedgerPage,
    ReplayAllocationState,
    SpendEventResponse,
    SpendTree,
    SpendTreeNode,
    Subscription,
    TimelineEvent,
    VoidResult,
    VoidTreeResult,
    WebhookConfig,
    WebhookDelivery,
    WebhookTestResult,
)
from .context import figuard_scope, figuard_run_in_executor, get_current_event_id, clear_current_event_id

__all__ = [
    "__version__",
    "FiGuardClient",
    # Denial reasons
    "DenialReason",
    # Exceptions
    "FiGuardError",
    "FiGuardApiError",
    "FiGuardDeniedException",
    "FiGuardConnectionError",
    "FiGuardWebhookVerificationError",
    # Models
    "Budget",
    "AuthorizationResult",
    "SpendEventResponse",
    "VoidResult",
    "VoidTreeResult",
    "LedgerPage",
    "SpendTree",
    "SpendTreeNode",
    "BudgetSnapshot",
    "AllocationSnapshot",
    "AllocationResponse",
    "BudgetFundingResult",
    "ApiKey",
    "EntitlementItem",
    "Subscription",
    "BudgetStateSnapshot",
    "BudgetTimeline",
    "ReplayAllocationState",
    "TimelineEvent",
    "WebhookConfig",
    "WebhookDelivery",
    "WebhookTestResult",
    # Context propagation
    "figuard_scope",
    "figuard_run_in_executor",
    "get_current_event_id",
    "clear_current_event_id",
    # Multi-resource
    "CompositeGuard",
    "GuardedResource",
    "CompositeAuthorizationResult",
]

if _has_async:
    __all__ += ["AsyncFiGuardClient", "AsyncCompositeGuard", "AsyncGuardedResource"]

# figuard.testing is a standalone module — not imported here to avoid
# pulling test helpers into production bundles.
# Usage: from figuard.testing import MockFiGuardClient
