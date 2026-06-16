"""The embedded enforcement kernel.

A faithful port of the Java core's *flat* (single-budget, single-process) authorize →
confirm/fail/void path. Behavior is asserted identical to the server by the conformance
suite. Scope is deliberately the frozen kernel — fleet features (delegation, entitlements,
allocations, anomaly) raise FiGuardCapabilityError, drawing the embedded/server line in
software (Concern #2).

Source-of-truth references (com.figuard.service):
  - capacity: AgentBudget.canAccommodateWith → available = totalLimit - spent - reserved
  - approve: reserve=true adds to quantityReserved + sets confirmationTimeout; reserve=false
    holds nothing and sets no timeout (AuthorizationService.approve)
  - confirm: reserved → spent (PaymentLifecycleService.confirmEvent)
  - fail/void: release reserved (PaymentLifecycleService.failEvent/voidEvent)
"""

from __future__ import annotations

import functools
import hashlib
import threading
import uuid
import json
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Optional

from . import store
from .enums import BudgetStatus, DenialCode, SpendDecision
from .errors import EventStateError, FiGuardCapabilityError, InvalidParentError, NotFoundError

_Q = Decimal("0.0001")
CONFIRMATION_TIMEOUT_SECONDS = 300


def _d(x) -> Decimal:
    return Decimal(str(x))


def _q(x: Decimal) -> str:
    return str(_d(x).quantize(_Q))


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _parse_ts(s: str) -> datetime:
    """Parse an ISO-8601 expiry to a tz-aware UTC datetime. Unparseable → far future
    (treat as 'no expiry' rather than risk a spurious BUDGET_EXPIRED)."""
    try:
        dt = datetime.fromisoformat(str(s).replace("Z", "+00:00"))
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except (ValueError, TypeError):
        return datetime.max.replace(tzinfo=timezone.utc)


def _new_id() -> str:
    return str(uuid.uuid4())


def _locked(fn):
    """Serialize a public engine method on self._lock so one engine (one SQLite connection)
    is safe to share across threads — the embedded equivalent of the server's row lock, here
    covering the whole connection (single-writer, which is the embedded model)."""
    @functools.wraps(fn)
    def wrapper(self, *args, **kwargs):
        with self._lock:
            return fn(self, *args, **kwargs)
    return wrapper


class LiteEngine:
    """In-process FiGuard enforcement against a local SQLite file. backend == 'embedded'."""

    backend = "embedded"

    def __init__(self, db_path: str = ":memory:"):
        self.conn = store.connect(db_path)
        self._lock = threading.RLock()   # serializes all conn access (thread-safe sharing)

    # -- budgets -----------------------------------------------------------------

    @_locked
    def create_budget(self, *, total_limit, unit: str = "usd", currency: Optional[str] = None,
                       user_id: Optional[str] = None,
                       max_transaction_quantity=None, intent_tags=None, entity_dedup_enabled: bool = False,
                       velocity_max_per_minute=None, velocity_max_amount_per_hour=None,
                       velocity_max_per_day=None,
                       status: str = "ACTIVE", expires_at: Optional[str] = None) -> str:
        bid = _new_id()
        with store.write_txn(self.conn) as c:
            c.execute(
                """INSERT INTO budgets(id,user_id,total_limit,unit,currency,max_transaction_quantity,
                       intent_tags,entity_dedup_enabled,velocity_max_per_minute,
                       velocity_max_amount_per_hour,velocity_max_per_day,status,expires_at,
                       quantity_spent,quantity_reserved,created_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (bid, user_id, _q(total_limit), unit, currency,
                 _q(max_transaction_quantity) if max_transaction_quantity is not None else None,
                 json.dumps(intent_tags) if intent_tags else None,
                 1 if entity_dedup_enabled else 0,
                 velocity_max_per_minute,
                 _q(velocity_max_amount_per_hour) if velocity_max_amount_per_hour is not None else None,
                 velocity_max_per_day,
                 status, expires_at, "0.0000", "0.0000", _now().isoformat()),
            )
        return bid

    # -- session tokens (persisted, hashed) --------------------------------------

    @staticmethod
    def _hash_token(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()

    @_locked
    def put_session_token(self, token: str, budget_id: str) -> None:
        """Persist a session token (by hash) so it survives a restart — without this a
        persisted budget could be read but never authorized against in a new process."""
        with store.write_txn(self.conn) as c:
            c.execute("INSERT OR REPLACE INTO session_tokens(token_hash, budget_id) VALUES(?,?)",
                      (self._hash_token(token), budget_id))

    @_locked
    def resolve_session_token(self, token: Optional[str]) -> Optional[str]:
        """Return the budget_id for a raw session token (looked up by hash), or None."""
        if not token:
            return None
        row = self.conn.execute("SELECT budget_id FROM session_tokens WHERE token_hash=?",
                                (self._hash_token(token),)).fetchone()
        return row["budget_id"] if row else None

    @_locked
    def get_budget(self, budget_id: str) -> dict:
        row = self.conn.execute("SELECT * FROM budgets WHERE id=?", (budget_id,)).fetchone()
        if row is None:
            raise NotFoundError(f"Budget not found: {budget_id}")
        return self._snapshot(row)

    def _snapshot(self, row) -> dict:
        total = _d(row["total_limit"])
        spent = _d(row["quantity_spent"])
        reserved = _d(row["quantity_reserved"])
        # Includes create-time metadata (unit/currency/limits/...) so GET /budgets/{id} is
        # faithful — it rebuilds the response from this snapshot, not from the create request.
        return {
            "total_limit": _q(total),
            "quantity_spent": _q(spent),
            "quantity_reserved": _q(reserved),
            "available": _q(total - spent - reserved),
            "status": row["status"],
            "user_id": row["user_id"],
            "unit": row["unit"],
            "currency": row["currency"],
            "max_transaction_quantity": row["max_transaction_quantity"],
            "intent_tags": json.loads(row["intent_tags"]) if row["intent_tags"] else None,
            "velocity_max_per_minute": row["velocity_max_per_minute"],
            "velocity_max_amount_per_hour": row["velocity_max_amount_per_hour"],
            "velocity_max_per_day": row["velocity_max_per_day"],
            "expires_at": row["expires_at"],
            "created_at": row["created_at"],
        }

    @_locked
    def get_tree(self, budget_id: str) -> dict:
        """Hierarchical view of the budget's events, built from parent_event_id links.

        Mirrors the server's GET /budgets/{id}/tree: a forest of {event, children} nodes
        ordered by creation, with roots = events that have no (resolvable) parent. Every
        recorded event is included, denials included, matching the append-only ledger."""
        if self.conn.execute("SELECT 1 FROM budgets WHERE id=?", (budget_id,)).fetchone() is None:
            raise NotFoundError(f"Budget not found: {budget_id}")
        rows = self.conn.execute(
            "SELECT * FROM spend_events WHERE budget_id=? ORDER BY created_at ASC, rowid ASC",
            (budget_id,)).fetchall()
        nodes = {r["id"]: {"event": dict(r), "children": []} for r in rows}
        roots = []
        for r in rows:
            pid = r["parent_event_id"]
            node = nodes[r["id"]]
            if pid and pid in nodes:
                nodes[pid]["children"].append(node)
            else:
                roots.append(node)
        return {"roots": roots, "total_events": len(rows)}

    # -- authorize ---------------------------------------------------------------

    @_locked
    def authorize(self, *, budget_id: str, amount, idempotency_key: Optional[str] = None,
                  entity_id: Optional[str] = None, reserve: bool = True,
                  currency: Optional[str] = None, claimed_category: Optional[str] = None,
                  parent_event_id: Optional[str] = None, intent_context: Optional[str] = None,
                  agent_id: Optional[str] = None, action_type: Optional[str] = None,
                  description: Optional[str] = None) -> dict:
        amount = _d(amount)
        with store.write_txn(self.conn) as c:
            b = c.execute("SELECT * FROM budgets WHERE id=?", (budget_id,)).fetchone()
            if b is None:
                raise NotFoundError(f"Budget not found: {budget_id}")

            # idempotency replay — a seen key returns the original decision (DUPLICATE_REQUEST)
            if idempotency_key:
                prior = c.execute(
                    "SELECT * FROM spend_events WHERE budget_id=? AND idempotency_key=? LIMIT 1",
                    (budget_id, idempotency_key)).fetchone()
                if prior is not None:
                    return self._replay(c, b, prior)

            # status gates
            deny = self._status_denial(b)
            if deny:
                return self._deny(c, b, amount, *deny, entity_id, idempotency_key)

            # velocity (rolling windows: per-minute count → hourly amount → per-day count)
            vdeny = self._velocity_denial(c, b, amount, entity_id, idempotency_key)
            if vdeny is not None:
                return vdeny

            # currency
            if currency and b["currency"] and currency != b["currency"]:
                return self._deny(c, b, amount, DenialCode.CURRENCY_MISMATCH,
                                  f"requested currency {currency} != budget currency {b['currency']}",
                                  entity_id, idempotency_key)

            # entity dedup
            if b["entity_dedup_enabled"] and entity_id:
                existing = c.execute(
                    """SELECT * FROM spend_events WHERE budget_id=? AND entity_id=?
                       AND decision IN ('AUTHORIZED','CONFIRMED') LIMIT 1""",
                    (budget_id, entity_id)).fetchone()
                if existing is not None:
                    return self._deny(c, b, amount, DenialCode.ENTITY_ALREADY_AUTHORIZED,
                                      f"entity {entity_id} already has a live event on this budget",
                                      entity_id, idempotency_key, original_event_id=existing["id"])

            # max transaction quantity
            if b["max_transaction_quantity"] is not None and amount > _d(b["max_transaction_quantity"]):
                return self._deny(c, b, amount, DenialCode.EXCEEDS_QUANTITY_LIMIT,
                                  f"requested {amount} exceeds maxTransactionQuantity {b['max_transaction_quantity']}",
                                  entity_id, idempotency_key)

            # causal-chain parent validation. Like the server, an invalid parent is a REQUEST
            # ERROR (raises), not a DENIED spend decision — and writes no event.
            parent_chain_root = None
            if parent_event_id is not None:
                parent = c.execute("SELECT * FROM spend_events WHERE id=?", (parent_event_id,)).fetchone()
                if (parent is None or parent["budget_id"] != budget_id
                        or parent["decision"] not in (SpendDecision.AUTHORIZED.value,
                                                       SpendDecision.CONFIRMED.value)):
                    raise InvalidParentError(f"parent {parent_event_id}")
                parent_chain_root = parent["chain_root_event_id"]

            # intent scope (flat budgets only): if the budget declares intentTags, the request's
            # intentContext must contain at least one tag (case-insensitive substring).
            ideny = self._intent_denial(c, b, amount, intent_context, entity_id, idempotency_key)
            if ideny is not None:
                return ideny

            # capacity — reserve=false holds nothing, so the capacity check does not apply
            available = _d(b["total_limit"]) - _d(b["quantity_spent"]) - _d(b["quantity_reserved"])
            if reserve and amount > available:
                return self._deny(c, b, amount, DenialCode.INSUFFICIENT_FUNDS,
                                  self._insufficient_msg(b, amount, available), entity_id, idempotency_key)

            # approve
            return self._approve(c, b, amount, reserve, entity_id, idempotency_key,
                                 currency, claimed_category, parent_event_id, parent_chain_root,
                                 agent_id, action_type, description)

    def _velocity_denial(self, c, b, amount, entity_id, idempotency_key):
        """Rolling-window rate limits, in priority order; first violation short-circuits.
        Counts/sums span ALL events in the window (matching the server's countAttemptsAfter /
        sumAttemptedQuantityAfter — denials included)."""
        now = _now()
        bid = b["id"]

        def count_after(cutoff_iso) -> int:
            return c.execute("SELECT COUNT(*) FROM spend_events WHERE budget_id=? AND created_at>?",
                             (bid, cutoff_iso)).fetchone()[0]

        def sum_after(cutoff_iso) -> Decimal:
            total = Decimal("0")
            for (rq,) in c.execute(
                    "SELECT requested_quantity FROM spend_events WHERE budget_id=? AND created_at>?",
                    (bid, cutoff_iso)).fetchall():
                total += _d(rq)
            return total

        if b["velocity_max_per_minute"] is not None:
            if count_after((now - timedelta(seconds=60)).isoformat()) >= b["velocity_max_per_minute"]:
                return self._deny(c, b, amount, DenialCode.VELOCITY_LIMIT_EXCEEDED,
                                  f"maxPerMinute={b['velocity_max_per_minute']}", entity_id, idempotency_key)
        if b["velocity_max_amount_per_hour"] is not None:
            if sum_after((now - timedelta(hours=1)).isoformat()) + amount > _d(b["velocity_max_amount_per_hour"]):
                return self._deny(c, b, amount, DenialCode.VELOCITY_LIMIT_EXCEEDED,
                                  f"maxAmountPerHour={b['velocity_max_amount_per_hour']}", entity_id, idempotency_key)
        if b["velocity_max_per_day"] is not None:
            if count_after((now - timedelta(days=1)).isoformat()) >= b["velocity_max_per_day"]:
                return self._deny(c, b, amount, DenialCode.VELOCITY_LIMIT_EXCEEDED,
                                  f"maxPerDay={b['velocity_max_per_day']}", entity_id, idempotency_key)
        return None

    def _intent_denial(self, c, b, amount, intent_context, entity_id, idempotency_key):
        """Flat-budget intent gate (mirrors IntentScopeValidator): no tags → pass; tags but no
        context → deny; tags + context → pass iff any tag is a case-insensitive substring."""
        tags_json = b["intent_tags"]
        if not tags_json:
            return None
        tags = json.loads(tags_json)
        if not tags:
            return None
        if not intent_context or not intent_context.strip():
            return self._deny(c, b, amount, DenialCode.INTENT_SCOPE_VIOLATION,
                              f"budget requires intentContext (intentTags: {tags})",
                              entity_id, idempotency_key)
        lower = intent_context.lower()
        if not any(t and t.lower() in lower for t in tags):
            return self._deny(c, b, amount, DenialCode.INTENT_SCOPE_VIOLATION,
                              f"intentContext {intent_context!r} matches no intentTags {tags}",
                              entity_id, idempotency_key)
        return None

    def _status_denial(self, b):
        st = b["status"]
        if st == BudgetStatus.EXHAUSTED:
            return (DenialCode.BUDGET_EXHAUSTED, "budget is exhausted")
        if st == BudgetStatus.PAUSED:
            return (DenialCode.BUDGET_PAUSED, "budget is paused")
        if st == BudgetStatus.CANCELLED:
            return (DenialCode.BUDGET_CANCELLED, "budget was cancelled")
        if st == BudgetStatus.EXPIRED:
            return (DenialCode.BUDGET_EXPIRED, "budget has expired")
        if b["expires_at"] and _now() > _parse_ts(b["expires_at"]):
            return (DenialCode.BUDGET_EXPIRED, "budget has passed expiresAt")
        return None

    def _insufficient_msg(self, b, amount, available) -> str:
        base = f"Budget has {_q(available)} available, requested {_q(amount)}"
        reserved = _d(b["quantity_reserved"])
        spent = _d(b["quantity_spent"])
        if reserved > 0 and spent < _d(b["total_limit"]):
            return (base + f". {_q(reserved)} is reserved by unconfirmed authorizations "
                    f"(only {_q(spent)} of {_q(_d(b['total_limit']))} actually spent) — "
                    "confirm or void them to free capacity")
        return base

    def _approve(self, c, b, amount, reserve, entity_id, idempotency_key, currency, claimed_category,
                 parent_event_id=None, parent_chain_root=None,
                 agent_id=None, action_type=None, description=None) -> dict:
        eid = _new_id()
        now = _now()
        timeout = (now + timedelta(seconds=CONFIRMATION_TIMEOUT_SECONDS)).isoformat() if reserve else None
        chain_root = parent_chain_root or eid   # root self-references; children inherit the root
        if reserve:
            new_reserved = _d(b["quantity_reserved"]) + amount
            c.execute("UPDATE budgets SET quantity_reserved=? WHERE id=?",
                      (_q(new_reserved), b["id"]))
        # agent_id/action_type/description are persisted on APPROVED events only — they label the
        # spend-tree nodes (human-readable, not just amounts). Denials stay label-light by design
        # (every _deny call site would otherwise need them), and a denial node shows decision+amount.
        c.execute(
            """INSERT INTO spend_events(id,budget_id,decision,requested_quantity,currency,
                   claimed_category,idempotency_key,entity_id,reserved,parent_event_id,
                   chain_root_event_id,confirmation_timeout_at,agent_id,action_type,description,created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (eid, b["id"], SpendDecision.AUTHORIZED.value, _q(amount), currency or b["currency"],
             claimed_category, idempotency_key, entity_id, 1 if reserve else 0, parent_event_id,
             chain_root, timeout, agent_id, action_type, description, now.isoformat()))
        b2 = c.execute("SELECT * FROM budgets WHERE id=?", (b["id"],)).fetchone()
        return {
            "decision": SpendDecision.AUTHORIZED.value,
            "event_id": eid,
            "approved_quantity": _q(amount),
            "reserved": bool(reserve),
            "budget": self._snapshot(b2),
        }

    def _deny(self, c, b, amount, code: DenialCode, message: str, entity_id, idempotency_key,
              original_event_id=None) -> dict:
        eid = _new_id()
        # every denial is recorded in the append-only ledger, like the server
        c.execute(
            """INSERT INTO spend_events(id,budget_id,decision,denial_reason,requested_quantity,
                   idempotency_key,entity_id,reserved,created_at)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            (eid, b["id"], SpendDecision.DENIED.value, code.value, _q(amount),
             idempotency_key, entity_id, 0, _now().isoformat()))
        return {
            "decision": SpendDecision.DENIED.value,
            "event_id": eid,
            "denial_reason": code.value,
            "denial_message": message,
            "original_event_id": original_event_id,
            "budget": self._snapshot(b),
        }

    def _replay(self, c, b, prior) -> dict:
        if prior["decision"] == SpendDecision.DENIED.value:
            return {"decision": SpendDecision.DENIED.value, "event_id": prior["id"],
                    "denial_reason": prior["denial_reason"], "duplicate": True,
                    "budget": self._snapshot(b)}
        return {"decision": prior["decision"], "event_id": prior["id"],
                "approved_quantity": prior["requested_quantity"], "duplicate": True,
                "budget": self._snapshot(b)}

    # -- lifecycle ---------------------------------------------------------------

    @_locked
    def confirm(self, *, event_id: str, confirmed_quantity) -> dict:
        confirmed = _d(confirmed_quantity)
        with store.write_txn(self.conn) as c:
            e = self._load_authorized(c, event_id)
            reserved = _d(e["requested_quantity"])
            b = c.execute("SELECT * FROM budgets WHERE id=?", (e["budget_id"],)).fetchone()
            # Always record the confirmed actual as spend. Only release a reservation that was
            # actually held — a reserve=false event held none (releasing would go negative).
            new_reserved = _d(b["quantity_reserved"]) - reserved if e["reserved"] else _d(b["quantity_reserved"])
            new_spent = _d(b["quantity_spent"]) + confirmed
            c.execute("UPDATE budgets SET quantity_reserved=?, quantity_spent=? WHERE id=?",
                      (_q(new_reserved), _q(new_spent), e["budget_id"]))
            c.execute("UPDATE spend_events SET decision=?, confirmed_quantity=? WHERE id=?",
                      (SpendDecision.CONFIRMED.value, _q(confirmed), event_id))
            return self._event_result(c, event_id, SpendDecision.CONFIRMED)

    @_locked
    def fail(self, *, event_id: str, reason: Optional[str] = None) -> dict:
        return self._release(event_id, SpendDecision.FAILED)

    @_locked
    def void(self, *, event_id: str, reason: Optional[str] = None) -> dict:
        return self._release(event_id, SpendDecision.VOIDED)

    def _release(self, event_id: str, to: SpendDecision) -> dict:
        with store.write_txn(self.conn) as c:
            e = self._load_authorized(c, event_id)
            if e["reserved"]:
                b = c.execute("SELECT * FROM budgets WHERE id=?", (e["budget_id"],)).fetchone()
                c.execute("UPDATE budgets SET quantity_reserved=? WHERE id=?",
                          (_q(_d(b["quantity_reserved"]) - _d(e["requested_quantity"])), e["budget_id"]))
            c.execute("UPDATE spend_events SET decision=? WHERE id=?", (to.value, event_id))
            return self._event_result(c, event_id, to)

    def _load_authorized(self, c, event_id: str):
        e = c.execute("SELECT * FROM spend_events WHERE id=?", (event_id,)).fetchone()
        if e is None:
            raise NotFoundError(f"Event not found: {event_id}")
        if e["decision"] != SpendDecision.AUTHORIZED.value:
            raise EventStateError(
                f"Event is not in AUTHORIZED state (current: {e['decision']})")
        return e

    def _event_result(self, c, event_id: str, decision: SpendDecision) -> dict:
        e = c.execute("SELECT * FROM spend_events WHERE id=?", (event_id,)).fetchone()
        b = c.execute("SELECT * FROM budgets WHERE id=?", (e["budget_id"],)).fetchone()
        return {
            "decision": decision.value,
            "event_id": event_id,
            "requested_quantity": e["requested_quantity"],
            "confirmed_quantity": e["confirmed_quantity"],
            "currency": e["currency"],
            "created_at": e["created_at"],
            "budget": self._snapshot(b),
        }

    # -- capability boundary (Concern #2) ---------------------------------------

    def create_delegation_token(self, *a, **k):
        raise FiGuardCapabilityError("Delegation tokens")

    def create_subscription(self, *a, **k):
        raise FiGuardCapabilityError("Subscriptions & entitlements")

    def register_webhook(self, *a, **k):
        raise FiGuardCapabilityError("Webhooks")
