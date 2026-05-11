"""
Enforcement scenario tests — Refund Fleet

Covers every enforcement knob described in figuard_rampup.md
"Enforcement capabilities reference — Refund Fleet scenarios".

If a scenario description in that document changes behavior, a test here
should fail. When adding a new enforcement attribute or DenialCode, add
a corresponding test here and update the enforcement matrix in figuard_rampup.md.

Run:
    make run
    pytest tests/live/test_enforcement_scenarios.py -v
"""

from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from figuard import (
    FiGuardClient,
    FiGuardApiError,
    CompositeGuard,
    GuardedResource,
)


def _expires_at() -> str:
    ts = datetime.now(timezone.utc) + timedelta(hours=23)
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# S1 — Flat monetary budget: currency mismatch + intent scope violation
# ---------------------------------------------------------------------------

class TestFlatBudgetEnforcement:
    """S1: totalLimit, currency, intentTags enforcement on flat budgets."""

    def test_currency_mismatch_is_denied(self, client: FiGuardClient):
        """Authorizing EUR on a USD budget must produce CURRENCY_MISMATCH."""
        budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
        )

        result = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="currency mismatch test",
            requested_quantity=100.00,
            currency="EUR",
            idempotency_key=str(uuid4()),
        )

        assert not result.is_authorized
        assert result.denial_reason == "CURRENCY_MISMATCH"

    def test_insufficient_funds_when_cap_exhausted(self, client: FiGuardClient):
        """Request exceeding totalLimit must produce INSUFFICIENT_FUNDS."""
        budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=50.00,
            expires_at=_expires_at(),
            currency="USD",
        )

        result = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="over-limit refund",
            requested_quantity=200.00,
            idempotency_key=str(uuid4()),
        )

        assert not result.is_authorized
        assert result.denial_reason == "INSUFFICIENT_FUNDS"

    def test_intent_scope_violation_on_flat_budget(self, client: FiGuardClient):
        """
        Flat budget with intentTags must deny requests whose intentContext
        does not match any tag. INTENT_SCOPE_VIOLATION is only enforced on
        flat (no-allocation) budgets.
        """
        budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
            intent_tags=["daily-refund-run"],
        )

        # No intentContext — must be denied
        result = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="missing intent context",
            requested_quantity=50.00,
            idempotency_key=str(uuid4()),
        )
        assert not result.is_authorized
        assert result.denial_reason == "INTENT_SCOPE_VIOLATION"

        # Wrong intentContext — must also be denied
        result2 = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="wrong intent context",
            requested_quantity=50.00,
            intent_context="unrelated-agent-run",
            idempotency_key=str(uuid4()),
        )
        assert not result2.is_authorized
        assert result2.denial_reason == "INTENT_SCOPE_VIOLATION"

        # Correct intentContext — must be authorized
        result3 = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="correct intent context",
            requested_quantity=50.00,
            intent_context="daily-refund-run",
            idempotency_key=str(uuid4()),
        )
        assert result3.is_authorized

    def test_intent_tags_not_enforced_on_allocated_budget(self, client: FiGuardClient):
        """
        intentTags set on an allocated budget must NOT block requests.
        Category routing is the gating mechanism on allocated budgets.
        """
        budget = client.create_budget(
            user_id="refund-orchestrator",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
            intent_tags=["weekly-refund-ops"],
            allocations=[
                {
                    "category": "PAYOUT",
                    "allowedCategories": ["STANDARD_REFUND"],
                    "limit": 1000.00,
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                }
            ],
        )

        # No intentContext but correct claimedCategory — must be authorized
        result = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-orchestrator",
            action_type="REFUND_PAYOUT",
            description="allocated budget, no intentContext",
            requested_quantity=50.00,
            claimed_category="STANDARD_REFUND",
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized, (
            f"Expected AUTHORIZED but got DENIED ({result.denial_reason}). "
            "intentTags must not gate requests on allocated budgets."
        )


# ---------------------------------------------------------------------------
# S2 — Category allocations (CATEGORY_CONSTRAINED)
# ---------------------------------------------------------------------------

class TestCategoryAllocationEnforcement:
    """S2: allowedCategories, per-allocation limits, missing/no-match denial."""

    @pytest.fixture
    def refund_budget(self, client: FiGuardClient):
        """Orchestrator budget with PAYOUT and DISPUTE_FEES allocations."""
        return client.create_budget(
            user_id="refund-orchestrator",
            total_limit=500.00,
            expires_at=_expires_at(),
            currency="USD",
            allocations=[
                {
                    "category": "PAYOUT",
                    "allowedCategories": ["STANDARD_REFUND", "EXPRESS_REFUND"],
                    "limit": 400.00,
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                },
                {
                    "category": "DISPUTE_FEES",
                    "allowedCategories": ["CHARGEBACK_FEE", "ARBITRATION_FEE"],
                    "limit": 100.00,
                    "enforcementMode": "CATEGORY_CONSTRAINED",
                },
            ],
        )

    def test_missing_claimed_category_is_denied(self, client, refund_budget):
        """Budget with allocations and no claimedCategory → MISSING_CLAIMED_CATEGORY."""
        result = client.authorize(
            session_token=refund_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="forgot claimed category",
            requested_quantity=50.00,
            idempotency_key=str(uuid4()),
        )
        assert not result.is_authorized
        assert result.denial_reason == "MISSING_CLAIMED_CATEGORY"

    def test_unrecognized_category_is_denied(self, client, refund_budget):
        """claimedCategory not in any allocation's allowedCategories → NO_MATCHING_ALLOCATION."""
        result = client.authorize(
            session_token=refund_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="unknown category",
            requested_quantity=50.00,
            claimed_category="CRYPTO_REFUND",
            idempotency_key=str(uuid4()),
        )
        assert not result.is_authorized
        assert result.denial_reason == "NO_MATCHING_ALLOCATION"

    def test_correct_category_is_authorized(self, client, refund_budget):
        """STANDARD_REFUND hits the PAYOUT allocation and is authorized."""
        result = client.authorize(
            session_token=refund_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="standard refund ORD-001",
            requested_quantity=100.00,
            claimed_category="STANDARD_REFUND",
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized
        assert result.allocation_snapshot is not None
        assert result.allocation_snapshot.category == "PAYOUT"

    def test_allocations_are_independent_buckets(self, client, refund_budget):
        """
        Exhausting PAYOUT must not affect DISPUTE_FEES allocation.
        Both draw from the same total but track their own sub-limits.
        """
        # Exhaust the PAYOUT allocation ($400)
        r1 = client.authorize(
            session_token=refund_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="large standard refund",
            requested_quantity=400.00,
            claimed_category="STANDARD_REFUND",
            idempotency_key=str(uuid4()),
        )
        assert r1.is_authorized

        # PAYOUT is now exhausted
        r2 = client.authorize(
            session_token=refund_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="another standard refund — should fail",
            requested_quantity=1.00,
            claimed_category="STANDARD_REFUND",
            idempotency_key=str(uuid4()),
        )
        assert not r2.is_authorized
        assert r2.denial_reason == "ALLOCATION_EXHAUSTED"

        # DISPUTE_FEES allocation is still open
        r3 = client.authorize(
            session_token=refund_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="FEE_DEDUCTION",
            description="chargeback fee DISP-001",
            requested_quantity=25.00,
            claimed_category="CHARGEBACK_FEE",
            idempotency_key=str(uuid4()),
        )
        assert r3.is_authorized, (
            f"DISPUTE_FEES should still be open after PAYOUT exhaustion, "
            f"got: {r3.denial_reason}"
        )


# ---------------------------------------------------------------------------
# S3 — STRICT mode + forbidden item types
# ---------------------------------------------------------------------------

class TestStrictModeEnforcement:
    """S3: forbiddenItemTypes only evaluated under STRICT enforcementMode."""

    @pytest.fixture
    def strict_budget(self, client: FiGuardClient):
        """Budget with STRICT PAYOUT allocation blocking crypto payouts."""
        return client.create_budget(
            user_id="refund-orchestrator",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
            allocations=[
                {
                    "category": "PAYOUT",
                    "allowedCategories": ["STANDARD_REFUND", "EXPRESS_REFUND"],
                    "limit": 1000.00,
                    "enforcementMode": "STRICT",
                    "forbiddenItemTypes": ["CRYPTO_PAYOUT", "WIRE_TRANSFER_INTL"],
                }
            ],
        )

    def test_forbidden_item_type_is_denied(self, client, strict_budget):
        """Category matches but claimedItemType is forbidden → FORBIDDEN_ITEM_TYPE."""
        result = client.authorize(
            session_token=strict_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="crypto refund ORD-999",
            requested_quantity=500.00,
            claimed_category="EXPRESS_REFUND",
            claimed_item_type="CRYPTO_PAYOUT",
            idempotency_key=str(uuid4()),
        )
        assert not result.is_authorized
        assert result.denial_reason == "FORBIDDEN_ITEM_TYPE"

    def test_allowed_item_type_is_authorized(self, client, strict_budget):
        """Same category with a non-forbidden item type passes STRICT mode."""
        result = client.authorize(
            session_token=strict_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="bank transfer refund ORD-998",
            requested_quantity=100.00,
            claimed_category="EXPRESS_REFUND",
            claimed_item_type="BANK_TRANSFER_DOM",
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized

    def test_no_item_type_passes_strict_when_not_required(self, client, strict_budget):
        """
        STRICT mode only blocks items in the forbidden list.
        Omitting claimedItemType entirely is allowed — the check is
        'is this item explicitly forbidden?', not 'is this item explicitly allowed?'.
        """
        result = client.authorize(
            session_token=strict_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="standard refund, no item type",
            requested_quantity=50.00,
            claimed_category="STANDARD_REFUND",
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized


# ---------------------------------------------------------------------------
# S4 — Per-transaction cap (maxTransactionQuantity)
# ---------------------------------------------------------------------------

class TestPerTransactionCap:
    """S4: maxTransactionQuantity ceiling checked before balance check."""

    @pytest.fixture
    def capped_budget(self, client: FiGuardClient):
        """$10,000 budget with $2,000 per-transaction ceiling."""
        return client.create_budget(
            user_id="refund-processor-agent",
            total_limit=10000.00,
            expires_at=_expires_at(),
            currency="USD",
            max_transaction_quantity=2000.00,
        )

    def test_exceeds_per_transaction_cap(self, client, capped_budget):
        """Single request over the ceiling → EXCEEDS_QUANTITY_LIMIT."""
        result = client.authorize(
            session_token=capped_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="large refund requiring manual approval",
            requested_quantity=3500.00,
            idempotency_key=str(uuid4()),
        )
        assert not result.is_authorized
        assert result.denial_reason == "EXCEEDS_QUANTITY_LIMIT"

    def test_at_ceiling_is_authorized(self, client, capped_budget):
        """Request exactly at the ceiling must be authorized."""
        result = client.authorize(
            session_token=capped_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="refund at ceiling",
            requested_quantity=2000.00,
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized

    def test_under_ceiling_is_authorized(self, client, capped_budget):
        """Normal refund well under the ceiling passes."""
        result = client.authorize(
            session_token=capped_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="standard refund ORD-100",
            requested_quantity=149.99,
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized


# ---------------------------------------------------------------------------
# S5 — Entity dedup
# ---------------------------------------------------------------------------

class TestEntityDedup:
    """S5: entityDedupEnabled blocks second authorize for same entityId."""

    @pytest.fixture
    def dedup_budget(self, client: FiGuardClient):
        """Budget with entity dedup enabled."""
        return client.create_budget(
            user_id="refund-processor-agent",
            total_limit=5000.00,
            expires_at=_expires_at(),
            currency="USD",
            entity_dedup_enabled=True,
        )

    def test_same_entity_id_is_denied_on_second_attempt(self, client, dedup_budget):
        """First authorize for an entityId succeeds; second is ENTITY_ALREADY_AUTHORIZED."""
        order_id = f"ORD-{uuid4().hex[:8]}"

        r1 = client.authorize(
            session_token=dedup_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"refund {order_id}",
            requested_quantity=149.99,
            entity_id=order_id,
            idempotency_key=str(uuid4()),
        )
        assert r1.is_authorized

        r2 = client.authorize(
            session_token=dedup_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"duplicate refund attempt {order_id}",
            requested_quantity=149.99,
            entity_id=order_id,
            idempotency_key=str(uuid4()),  # different key — not a retry
        )
        assert not r2.is_authorized
        assert r2.denial_reason == "ENTITY_ALREADY_AUTHORIZED"
        assert r2.original_event_id == r1.event_id

    def test_same_idempotency_key_replays_original_decision(self, client, dedup_budget):
        """
        Same idempotencyKey is a safe retry, not a dedup violation.
        Must return the same event_id regardless of entityId.
        """
        key = str(uuid4())
        order_id = f"ORD-{uuid4().hex[:8]}"

        r1 = client.authorize(
            session_token=dedup_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"refund {order_id}",
            requested_quantity=75.00,
            entity_id=order_id,
            idempotency_key=key,
        )
        assert r1.is_authorized

        r2 = client.authorize(
            session_token=dedup_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"retry refund {order_id}",
            requested_quantity=75.00,
            entity_id=order_id,
            idempotency_key=key,  # same key — safe retry
        )
        assert r2.is_authorized
        assert r2.event_id == r1.event_id  # same event, not a new one

    def test_different_entity_ids_on_same_budget_are_independent(
        self, client, dedup_budget
    ):
        """Two different orders on the same budget are allowed independently."""
        order_a = f"ORD-{uuid4().hex[:8]}"
        order_b = f"ORD-{uuid4().hex[:8]}"

        ra = client.authorize(
            session_token=dedup_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"refund {order_a}",
            requested_quantity=100.00,
            entity_id=order_a,
            idempotency_key=str(uuid4()),
        )
        assert ra.is_authorized

        rb = client.authorize(
            session_token=dedup_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"refund {order_b}",
            requested_quantity=100.00,
            entity_id=order_b,
            idempotency_key=str(uuid4()),
        )
        assert rb.is_authorized
        assert ra.event_id != rb.event_id


# ---------------------------------------------------------------------------
# TraceId — ledger filtering
# ---------------------------------------------------------------------------

class TestTraceId:
    """traceId roundtrip: events tagged with a trace are filterable on the ledger."""

    def test_trace_id_appears_on_spend_event(self, client: FiGuardClient):
        """traceId passed to authorize must be present on the spend event response."""
        budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
        )
        trace = f"run-{uuid4().hex[:12]}"

        result = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="refund with trace",
            requested_quantity=50.00,
            trace_id=trace,
            idempotency_key=str(uuid4()),
        )
        assert result.is_authorized

        event = client.confirm_event(result.event_id, confirmed_quantity=50.00)
        assert event.trace_id == trace

    def test_ledger_trace_id_filter_returns_only_matching_events(
        self, client: FiGuardClient
    ):
        """get_ledger?traceId= returns only events from that trace."""
        budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
        )
        trace_a = f"run-{uuid4().hex[:12]}"
        trace_b = f"run-{uuid4().hex[:12]}"

        # Two events under trace_a
        for i in range(2):
            r = client.authorize(
                session_token=budget.session_token,
                agent_id="refund-processor-agent",
                action_type="REFUND_PAYOUT",
                description=f"refund trace_a order {i}",
                requested_quantity=10.00,
                trace_id=trace_a,
                idempotency_key=str(uuid4()),
            )
            assert r.is_authorized

        # One event under trace_b
        r = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="refund trace_b order 0",
            requested_quantity=10.00,
            trace_id=trace_b,
            idempotency_key=str(uuid4()),
        )
        assert r.is_authorized

        page_a = client.get_ledger(budget.id, trace_id=trace_a, size=50)
        assert all(e.trace_id == trace_a for e in page_a.events)
        assert len(page_a.events) >= 2

        page_b = client.get_ledger(budget.id, trace_id=trace_b, size=50)
        assert all(e.trace_id == trace_b for e in page_b.events)
        assert len(page_b.events) >= 1

        # Traces must not bleed into each other
        trace_a_ids = {e.id for e in page_a.events}
        trace_b_ids = {e.id for e in page_b.events}
        assert trace_a_ids.isdisjoint(trace_b_ids)


# ---------------------------------------------------------------------------
# S7 — Orchestrator + per-agent budgets (independent, same trace)
# ---------------------------------------------------------------------------

class TestOrchestratorAndPerAgentBudgets:
    """
    S7: two separate budgets hit independently with the same traceId.
    Both must authorize for the operation to proceed. If the per-agent budget
    is exhausted, only the orchestrator budget has a dangling reservation.
    """

    def test_both_budgets_authorized_independently(self, client: FiGuardClient):
        """
        Orchestrator and processor each authorize independently on the same order.
        Both must succeed. Same traceId groups all events.
        """
        trace = f"batch-run-{uuid4().hex[:8]}"
        order_id = f"ORD-{uuid4().hex[:8]}"

        orch_budget = client.create_budget(
            user_id="refund-orchestrator",
            total_limit=50000.00,
            expires_at=_expires_at(),
            currency="USD",
            authorization_expiry_seconds=600,
            allocations=[
                {
                    "category": "PAYOUT",
                    "allowedCategories": ["STANDARD_REFUND"],
                    "limit": 50000.00,
                    "enforcementMode": "STRICT",
                    "forbiddenItemTypes": ["CRYPTO_PAYOUT"],
                }
            ],
        )

        proc_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=10000.00,
            expires_at=_expires_at(),
            currency="USD",
            max_transaction_quantity=2000.00,
            authorization_expiry_seconds=300,
        )

        # Orchestrator authorizes first
        orch_result = client.authorize(
            session_token=orch_budget.session_token,
            agent_id="refund-orchestrator",
            agent_type="ORCHESTRATOR",
            action_type="REFUND_PAYOUT",
            description=f"authorize payout for {order_id}",
            requested_quantity=149.99,
            claimed_category="STANDARD_REFUND",
            entity_id=order_id,
            trace_id=trace,
            idempotency_key=f"orch-{order_id}",
        )
        assert orch_result.is_authorized

        # Processor authorizes on its own budget
        proc_result = client.authorize(
            session_token=proc_budget.session_token,
            agent_id="refund-processor-agent",
            agent_type="PAYMENT_PROCESSOR",
            action_type="REFUND_PAYOUT",
            description=f"execute payout for {order_id}",
            requested_quantity=149.99,
            entity_id=order_id,
            trace_id=trace,
            idempotency_key=f"proc-{order_id}",
        )
        assert proc_result.is_authorized

        # Both events must appear under the same trace on their respective budgets
        orch_page = client.get_ledger(orch_budget.id, trace_id=trace, size=50)
        proc_page = client.get_ledger(proc_budget.id, trace_id=trace, size=50)
        assert any(e.id == orch_result.event_id for e in orch_page.events)
        assert any(e.id == proc_result.event_id for e in proc_page.events)

    def test_dangling_orchestrator_reservation_when_processor_exhausted(
        self, client: FiGuardClient
    ):
        """
        Demonstrates the coordination gap: if the processor budget is exhausted,
        the orchestrator's authorization succeeds but has no matching processor
        authorization. The orchestrator is left with a dangling reservation.
        authorizationExpirySeconds on the orchestrator budget is the cleanup backstop.
        """
        orch_budget = client.create_budget(
            user_id="refund-orchestrator",
            total_limit=5000.00,
            expires_at=_expires_at(),
            currency="USD",
            authorization_expiry_seconds=60,  # short expiry for cleanup
        )

        # Exhausted processor budget
        proc_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=10.00,
            expires_at=_expires_at(),
            currency="USD",
        )

        order_id = f"ORD-{uuid4().hex[:8]}"

        orch_result = client.authorize(
            session_token=orch_budget.session_token,
            agent_id="refund-orchestrator",
            action_type="REFUND_PAYOUT",
            description=f"authorize {order_id}",
            requested_quantity=500.00,
            idempotency_key=f"orch-{order_id}",
        )
        assert orch_result.is_authorized  # orchestrator succeeds

        proc_result = client.authorize(
            session_token=proc_budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description=f"execute {order_id}",
            requested_quantity=500.00,
            idempotency_key=f"proc-{order_id}",
        )
        assert not proc_result.is_authorized  # processor fails — gap demonstrated
        assert proc_result.denial_reason == "INSUFFICIENT_FUNDS"

        # Orchestrator has a dangling reservation — void it manually to clean up
        # (in production: authorizationExpirySeconds recycles it after the window)
        client.void_event(orch_result.event_id, reason="PROCESSOR_BUDGET_EXHAUSTED")
        orch_after_void = client.get_budget(orch_budget.id)
        assert orch_after_void.quantity_reserved == 0.0


# ---------------------------------------------------------------------------
# S8 — CompositeGuard: atomic multi-resource authorization
# ---------------------------------------------------------------------------

class TestCompositeGuard:
    """S8: CompositeGuard voids prior authorizations when any resource denies."""

    def test_all_authorized_success_and_confirm(self, client: FiGuardClient):
        """
        CompositeGuard with two healthy budgets must authorize both and
        allow confirmation of both with distinct confirmed quantities.
        """
        dollar_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
        )
        token_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=100000,
            expires_at=_expires_at(),
            unit="tokens",
        )

        guard = CompositeGuard([
            GuardedResource(
                client=client,
                session_token=dollar_budget.session_token,
                resource="USD",
            ),
            GuardedResource(
                client=client,
                session_token=token_budget.session_token,
                resource="tokens",
            ),
        ])

        trace = f"composite-run-{uuid4().hex[:8]}"
        result = guard.authorize(
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="payout + memo for ORD-composite-001",
            requested={"USD": 149.99, "tokens": 8000},
            idempotency_key=str(uuid4()),
            trace_id=trace,
        )

        assert result.all_authorized
        assert len(result.authorizations) == 2
        assert all(r.is_authorized for r in result.authorizations)

        events = guard.confirm(result, confirmed={"USD": 149.99, "tokens": 7412})
        assert len(events) == 2
        assert all(e.decision == "CONFIRMED" for e in events)

    def test_partial_denial_voids_already_authorized_resources(
        self, client: FiGuardClient
    ):
        """
        If resource N denies, resources 0..N-1 that were already authorized
        must be voided. The first resource's budget must have its reservation
        fully released.
        """
        # Token budget (first) — will succeed
        token_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=100000,
            expires_at=_expires_at(),
            unit="tokens",
        )
        # Dollar budget (second) — will fail (tiny)
        tiny_dollar_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=5.00,
            expires_at=_expires_at(),
            currency="USD",
        )

        token_available_before = client.get_budget(token_budget.id).available_quantity

        guard = CompositeGuard([
            GuardedResource(
                client=client,
                session_token=token_budget.session_token,
                resource="tokens",
            ),
            GuardedResource(
                client=client,
                session_token=tiny_dollar_budget.session_token,
                resource="USD",
            ),
        ])

        result = guard.authorize(
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="payout + memo — dollar budget will deny",
            requested={"tokens": 8000, "USD": 500.00},
            idempotency_key=str(uuid4()),
        )

        assert not result.all_authorized
        assert result.first_denial_resource == "USD"
        assert result.first_denial.denial_reason == "INSUFFICIENT_FUNDS"

        # Token budget reservation must have been voided
        token_after = client.get_budget(token_budget.id)
        assert token_after.available_quantity == token_available_before, (
            f"Token budget available_quantity should be restored to {token_available_before} "
            f"after void, got {token_after.available_quantity}"
        )

    def test_composite_idempotency_key_is_namespaced_by_resource(
        self, client: FiGuardClient
    ):
        """
        The idempotency key is namespaced as '{key}:{resource_label}' per resource.
        Reusing the same composite key must replay the original decisions without
        creating new events.
        """
        dollar_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=1000.00,
            expires_at=_expires_at(),
            currency="USD",
        )
        token_budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=100000,
            expires_at=_expires_at(),
            unit="tokens",
        )

        guard = CompositeGuard([
            GuardedResource(client=client, session_token=dollar_budget.session_token, resource="USD"),
            GuardedResource(client=client, session_token=token_budget.session_token, resource="tokens"),
        ])

        key = str(uuid4())

        r1 = guard.authorize(
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="idempotency test",
            requested={"USD": 50.00, "tokens": 1000},
            idempotency_key=key,
        )
        assert r1.all_authorized

        # Same key — retry — must return same event IDs
        r2 = guard.authorize(
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="idempotency test retry",
            requested={"USD": 50.00, "tokens": 1000},
            idempotency_key=key,
        )
        assert r2.all_authorized
        assert r1.authorizations[0].event_id == r2.authorizations[0].event_id
        assert r1.authorizations[1].event_id == r2.authorizations[1].event_id


# ---------------------------------------------------------------------------
# S9 — Authorization auto-expiry (stale reservation recycling)
# ---------------------------------------------------------------------------

class TestAuthorizationAutoExpiry:
    """
    S9: authorizationExpirySeconds recycles stale AUTHORIZED reservations
    back into the available pool at the next authorize() call.
    """

    def test_stale_reservation_recycled_after_expiry_window(
        self, client: FiGuardClient
    ):
        """
        1. Budget has $100 total and a 2-second expiry window.
        2. Authorize $90 — leaves $10 available. Immediately a $50 request fails.
        3. Wait 3 seconds for the reservation to age out.
        4. $50 request succeeds because the $90 is no longer counted as reserved.
        """
        budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=100.00,
            expires_at=_expires_at(),
            currency="USD",
            authorization_expiry_seconds=2,
        )

        # Reserve $90 (simulates a crashed agent — never confirmed or failed)
        r1 = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="crash reservation",
            requested_quantity=90.00,
            idempotency_key=str(uuid4()),
        )
        assert r1.is_authorized

        # Immediately: only $10 left — $50 request must fail
        r2 = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="should fail — only $10 available",
            requested_quantity=50.00,
            idempotency_key=str(uuid4()),
        )
        assert not r2.is_authorized
        assert r2.denial_reason == "INSUFFICIENT_FUNDS"

        # Wait for the expiry window to pass
        time.sleep(3)

        # Now the $90 reservation is older than 2 seconds — recycled
        r3 = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="should succeed — stale reservation recycled",
            requested_quantity=50.00,
            idempotency_key=str(uuid4()),
        )
        assert r3.is_authorized, (
            f"Expected AUTHORIZED after expiry window but got DENIED ({r3.denial_reason}). "
            "authorizationExpirySeconds may not be working."
        )

    def test_stale_event_remains_in_ledger_as_authorized(
        self, client: FiGuardClient
    ):
        """
        Auto-expiry does not mutate events. The original crashed reservation
        must remain in the ledger as AUTHORIZED (not VOIDED).
        """
        budget = client.create_budget(
            user_id="refund-processor-agent",
            total_limit=100.00,
            expires_at=_expires_at(),
            currency="USD",
            authorization_expiry_seconds=1,
        )

        r1 = client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="stale reservation for audit test",
            requested_quantity=50.00,
            idempotency_key=str(uuid4()),
        )
        assert r1.is_authorized

        time.sleep(2)

        # Trigger auto-expiry by making a new authorize call
        client.authorize(
            session_token=budget.session_token,
            agent_id="refund-processor-agent",
            action_type="REFUND_PAYOUT",
            description="trigger expiry evaluation",
            requested_quantity=10.00,
            idempotency_key=str(uuid4()),
        )

        # Original event must still be AUTHORIZED in the ledger — not mutated
        page = client.get_ledger(budget.id, decision="AUTHORIZED", size=50)
        stale_event = next((e for e in page.events if e.id == r1.event_id), None)
        assert stale_event is not None, (
            "Original AUTHORIZED event should still be in the ledger after auto-expiry."
        )
        assert stale_event.decision == "AUTHORIZED"
