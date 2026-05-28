"""
FiGuard exception hierarchy.

All SDK exceptions derive from FiGuardError so callers can catch broadly
or narrowly depending on their needs.
"""

from __future__ import annotations

from typing import Optional


class FiGuardError(Exception):
    """Base class for all FiGuard SDK exceptions."""


class FiGuardApiError(FiGuardError):
    """
    An HTTP error response from the FiGuard API (4xx or 5xx that was not retried away).

    Attributes:
        status_code: HTTP status code
        message:     Error message from the API response body
        raw:         Full response body dict (may be None for non-JSON responses)
    """

    def __init__(self, status_code: int, message: str, raw: Optional[dict] = None) -> None:
        super().__init__(f"FiGuard API error {status_code}: {message}")
        self.status_code = status_code
        self.message = message
        self.raw = raw


class FiGuardDeniedException(FiGuardError):
    """
    Raised by ``AuthorizationResult.raise_if_denied()`` when the API returned DENIED.

    Attributes:
        denial_reason:      DenialCode string (e.g. ``"INSUFFICIENT_FUNDS"``)
        denial_message:     Human-readable explanation from the API
        original_event_id:  Set when ``denial_reason == "ENTITY_ALREADY_AUTHORIZED"``
                            — the UUID of the existing authorized/confirmed event for
                            this entity_id so the caller can confirm or void it.
    """

    def __init__(
        self,
        denial_reason: str,
        denial_message: Optional[str],
        original_event_id: Optional[str] = None,
    ) -> None:
        super().__init__(
            f"Spend denied: {denial_reason}"
            + (f" — {denial_message}" if denial_message else "")
        )
        self.denial_reason = denial_reason
        self.denial_message = denial_message
        self.original_event_id = original_event_id


class FiGuardConnectionError(FiGuardError):
    """Network-level error (timeout, DNS failure, etc.) after all retries exhausted."""


class FiGuardWebhookVerificationError(FiGuardError):
    """
    Raised by ``FiGuardClient.verify_webhook()`` when the HMAC-SHA256 signature
    on an incoming webhook does not match the expected value.

    Always raise an HTTP 400 in response to this exception — do not process
    the payload.
    """
