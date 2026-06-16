"""Internal embedded-engine errors, wired into the SDK's public exception hierarchy.

FiGuardCapabilityError is the public, unified one (figuard.exceptions) — the capability
boundary that draws the embedded/server line in software.
"""

from __future__ import annotations

from ..exceptions import FiGuardCapabilityError, FiGuardError  # re-export the public ones

__all__ = ["FiGuardCapabilityError", "FiGuardError", "EventStateError", "NotFoundError",
           "InvalidParentError"]


class EventStateError(FiGuardError):
    """Lifecycle transition attempted from the wrong state (e.g. confirm a VOIDED event)."""


class NotFoundError(FiGuardError):
    """Budget or event not found."""


class InvalidParentError(FiGuardError):
    """parentEventId is unknown, on a different budget, or in a terminal state.

    Mirrors the server's INVALID_PARENT_EVENT (HTTP 400) — a request error, not a DENIED
    spend decision. The message carries the code so it surfaces identically to the server."""

    def __init__(self, detail: str = ""):
        msg = "INVALID_PARENT_EVENT"
        super().__init__(f"{msg}: {detail}" if detail else msg)
