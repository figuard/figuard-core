"""Embedded enforcement backend for FiGuardClient — in-process SQLite, zero infra.

Internal. The public surface is `FiGuardClient(mode="embedded")` / the zero-config default.
The enforcement kernel here is held byte-for-byte conformant with the server by the shared
conformance suite (conformance/scenarios drive both the Java core and this engine).
"""

from .backend import EmbeddedBackend
from .engine import LiteEngine
from .errors import EventStateError, FiGuardCapabilityError, InvalidParentError, NotFoundError

__all__ = ["EmbeddedBackend", "LiteEngine", "FiGuardCapabilityError",
           "InvalidParentError", "EventStateError", "NotFoundError"]
