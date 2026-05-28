"""
Denial reason constants returned by ``AuthorizationResult.denial_reason``.

Use these instead of raw strings to get IDE autocomplete and typo protection::

    from figuard import DenialReason

    result = client.authorize(...)
    if result.denial_reason == DenialReason.BUDGET_EXHAUSTED:
        # total budget has no remaining capacity
        ...
    elif result.denial_reason == DenialReason.ALLOCATION_EXHAUSTED:
        # specific category allocation is exhausted
        ...
"""


class DenialReason:
    """
    String constants for every denial reason code returned by FiGuard.

    All values are the literal strings returned in ``AuthorizationResult.denial_reason``
    and ``FiGuardDeniedException.denial_reason``.
    """

    # -----------------------------------------------------------------------
    # Budget-level denials
    # -----------------------------------------------------------------------

    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    """Total budget has no remaining capacity. ``available_quantity`` is 0."""

    BUDGET_EXPIRED = "BUDGET_EXPIRED"
    """Budget has passed its expiry time. Create a new budget to continue."""

    BUDGET_PAUSED = "BUDGET_PAUSED"
    """
    Budget was manually paused or paused by anomaly detection.
    Resume with ``client.resume_budget(budget_id, override_reason=...)``.
    """

    BUDGET_CANCELLED = "BUDGET_CANCELLED"
    """Budget was cancelled. Create a new budget to continue."""

    # -----------------------------------------------------------------------
    # Category / allocation denials
    # -----------------------------------------------------------------------

    ALLOCATION_EXHAUSTED = "ALLOCATION_EXHAUSTED"
    """
    A specific category allocation has no remaining capacity.
    The total budget may still have funds — only this category is exhausted.
    """

    MISSING_CLAIMED_CATEGORY = "MISSING_CLAIMED_CATEGORY"
    """
    ``claimed_category`` was required (STRICT or CATEGORY_CONSTRAINED enforcement
    mode) but was not provided, or did not match any configured allocation.
    Add ``claimed_category`` to the ``authorize()`` call.
    """

    # -----------------------------------------------------------------------
    # Velocity denials
    # -----------------------------------------------------------------------

    VELOCITY_LIMIT_EXCEEDED = "VELOCITY_LIMIT_EXCEEDED"
    """
    Too many authorization requests within the configured velocity window
    (``velocity_max_per_minute``, ``velocity_max_per_day``, or
    ``velocity_max_amount_per_hour``). Retry after the window resets.
    """

    # -----------------------------------------------------------------------
    # Idempotency / entity denials
    # -----------------------------------------------------------------------

    ENTITY_ALREADY_AUTHORIZED = "ENTITY_ALREADY_AUTHORIZED"
    """
    The ``entity_id`` supplied already has an active (AUTHORIZED) reservation.
    Check ``result.original_event_id`` to confirm or void the existing event
    before authorizing again.
    """

    # -----------------------------------------------------------------------
    # Session / token denials
    # -----------------------------------------------------------------------

    INVALID_SESSION_TOKEN = "INVALID_SESSION_TOKEN"
    """
    Session token does not exist, has expired, or belongs to a different tenant.
    Verify the token or re-create the budget.
    """

    # -----------------------------------------------------------------------
    # Causal chain denials
    # -----------------------------------------------------------------------

    SUBTREE_CAP_EXCEEDED = "SUBTREE_CAP_EXCEEDED"
    """
    The total spend across the causal chain rooted at the root event has
    exceeded the ``max_subtree_quantity`` ceiling set on that root event.
    The orchestration job is over its chain-level cap.
    """

    # -----------------------------------------------------------------------
    # Subscription denials
    # -----------------------------------------------------------------------

    SUBSCRIPTION_PAUSED = "SUBSCRIPTION_PAUSED"
    """
    The subscription linked to this budget is paused.
    Resume the subscription to restore authorization capability.
    """
