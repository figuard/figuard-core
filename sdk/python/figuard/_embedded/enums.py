"""Decision + denial vocabulary — mirrors the Java core's enums byte-for-byte.

These string values ARE the contract. The conformance suite asserts that embedded
(this) and the Java server produce identical decision/denialReason strings, so these
must never drift from com.figuard.domain.enums.{SpendDecision,DenialCode}.
"""

from __future__ import annotations

from enum import Enum


class SpendDecision(str, Enum):
    AUTHORIZED = "AUTHORIZED"
    CONFIRMED = "CONFIRMED"
    FAILED = "FAILED"
    DENIED = "DENIED"
    VOIDED = "VOIDED"


class BudgetStatus(str, Enum):
    ACTIVE = "ACTIVE"
    PAUSED = "PAUSED"
    EXHAUSTED = "EXHAUSTED"
    EXPIRED = "EXPIRED"
    CANCELLED = "CANCELLED"


class DenialCode(str, Enum):
    # Only the subset reachable on the embedded (flat, single-process) kernel is used here,
    # but the full set is mirrored so the contract stays aligned with the Java enum.
    CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
    INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    BUDGET_PAUSED = "BUDGET_PAUSED"
    BUDGET_EXPIRED = "BUDGET_EXPIRED"
    BUDGET_CANCELLED = "BUDGET_CANCELLED"
    DUPLICATE_REQUEST = "DUPLICATE_REQUEST"
    EXCEEDS_QUANTITY_LIMIT = "EXCEEDS_QUANTITY_LIMIT"
    INTENT_SCOPE_VIOLATION = "INTENT_SCOPE_VIOLATION"
    ENTITY_ALREADY_AUTHORIZED = "ENTITY_ALREADY_AUTHORIZED"
    VELOCITY_LIMIT_EXCEEDED = "VELOCITY_LIMIT_EXCEEDED"
    # --- server-only (lite raises FiGuardCapabilityError before these can occur) ---
    NO_MATCHING_ALLOCATION = "NO_MATCHING_ALLOCATION"
    DELEGATE_CAP_EXCEEDED = "DELEGATE_CAP_EXCEEDED"
    ENTITLEMENT_LIMIT_REACHED = "ENTITLEMENT_LIMIT_REACHED"
    ANOMALY_DETECTED = "ANOMALY_DETECTED"
