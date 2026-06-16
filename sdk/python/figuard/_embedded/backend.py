"""Embedded backend — serves the FiGuard REST contract in-process against local SQLite.

It implements the same ``(method, path, json, headers) -> server-shaped JSON`` contract the
HTTP transport provides, so ``FiGuardClient``'s methods work UNCHANGED on either backend. The
engine returns decimal-string / snake_case results; this layer reshapes them into the server's
JSON (camelCase, numbers) that the SDK's ``_parse_*`` functions already consume.

Fleet endpoints (delegation, subscriptions, entitlements, webhooks, replay, …) raise
FiGuardCapabilityError — the embedded backend draws the embedded/server line in software.
"""

from __future__ import annotations

import re
import uuid
from typing import Optional

from ..exceptions import FiGuardApiError
from .engine import LiteEngine
from .errors import FiGuardCapabilityError, InvalidParentError

_CONFIRM = re.compile(r"^/api/v1/events/([^/]+)/confirm$")
_FAIL = re.compile(r"^/api/v1/events/([^/]+)/fail$")
_VOID = re.compile(r"^/api/v1/events/([^/]+)/void$")
_GET_BUDGET = re.compile(r"^/api/v1/budgets/([^/]+)$")
_TREE = re.compile(r"^/api/v1/budgets/([^/]+)/tree$")


def _f(x):
    return float(x) if x is not None else None


def _feature_for(path: str) -> str:
    if "delegation" in path:
        return "Delegation tokens"
    if "subscription" in path or "entitlement" in path:
        return "Subscriptions & entitlements"
    if "webhook" in path:
        return "Webhooks"
    if "replay" in path:
        return "Spend replay"
    return f"This operation ({path})"


def _snap_json(snap: dict) -> dict:
    return {
        "totalLimit": _f(snap["total_limit"]),
        "quantitySpent": _f(snap["quantity_spent"]),
        "quantityReserved": _f(snap["quantity_reserved"]),
        "availableQuantity": _f(snap["available"]),
        "status": snap["status"],
    }


class EmbeddedBackend:
    backend = "embedded"

    def __init__(self, database: str = ":memory:"):
        self.database = database
        self.engine = LiteEngine(database)
        # session_token → budget_id is PERSISTED in SQLite (engine.put/resolve_session_token),
        # not held in memory — so budgets survive a restart and stay authorizable (multi-day budgets).

    # The transport contract: same signature surface as the HTTP _request path.
    def request(self, method: str, path: str, json: Optional[dict] = None,
                params=None, headers: Optional[dict] = None) -> dict:
        body = json or {}
        headers = headers or {}

        if method == "POST" and path == "/api/v1/budgets":
            return self._create_budget(body)
        if method == "POST" and path == "/api/v1/authorize":
            return self._authorize(body, headers.get("X-Session-Token"))
        if method == "POST":
            m = _CONFIRM.match(path)
            if m:
                return self._event_json(self.engine.confirm(
                    event_id=m.group(1), confirmed_quantity=body["confirmedQuantity"]))
            m = _FAIL.match(path)
            if m:
                return self._event_json(self.engine.fail(event_id=m.group(1), reason=body.get("reason")))
            m = _VOID.match(path)
            if m:
                return self._event_json(self.engine.void(event_id=m.group(1), reason=body.get("reason")))
        if method == "GET":
            m = _TREE.match(path)
            if m:
                return self._tree_json(self.engine.get_tree(m.group(1)))
            m = _GET_BUDGET.match(path)
            if m:
                return self._budget_json(m.group(1), {}, None, self.engine.get_budget(m.group(1)))

        # anything else is a fleet/server-only endpoint
        raise FiGuardCapabilityError(_feature_for(path))

    # -- handlers ----------------------------------------------------------------

    def _reject_server_only_create_opts(self, body: dict) -> None:
        """Refuse create-time options embedded can't enforce — so a budget never *looks*
        configured (category caps, shadow mode, anomaly) while silently enforcing nothing of
        the sort. Mirrors the runtime capability boundary. (soft_limit is advisory → allowed.)"""
        if body.get("allocations"):
            raise FiGuardCapabilityError("Category allocations")
        trust_mode = body.get("trustMode")
        if trust_mode and str(trust_mode).upper() != "STRICT":
            raise FiGuardCapabilityError("Shadow / trust modes")
        if body.get("anomalyDetectionEnabled"):
            raise FiGuardCapabilityError("Anomaly detection")

    def _create_budget(self, body: dict) -> dict:
        self._reject_server_only_create_opts(body)
        bid = self.engine.create_budget(
            total_limit=body["totalLimit"],
            user_id=body.get("userId"),
            currency=body.get("currency"),
            unit=body.get("unit") if body.get("unit") else (None if body.get("currency") else "usd"),
            expires_at=body.get("expiresAt"),   # enforced by the engine when present
            max_transaction_quantity=body.get("maxTransactionQuantity"),
            intent_tags=body.get("intentTags"),
            entity_dedup_enabled=bool(body.get("entityDedupEnabled", False)),
            velocity_max_per_minute=body.get("velocityMaxPerMinute"),
            velocity_max_amount_per_hour=body.get("velocityMaxAmountPerHour"),
            velocity_max_per_day=body.get("velocityMaxPerDay"),
        )
        token = "st_" + uuid.uuid4().hex
        self.engine.put_session_token(token, bid)
        return self._budget_json(bid, body, token, self.engine.get_budget(bid))

    def _authorize(self, body: dict, session_token: Optional[str]) -> dict:
        budget_id = self.engine.resolve_session_token(session_token)
        if budget_id is None:
            raise FiGuardApiError(401, "INVALID_SESSION_TOKEN")
        try:
            r = self.engine.authorize(
                budget_id=budget_id,
                amount=body["requestedQuantity"],
                idempotency_key=body.get("idempotencyKey"),
                entity_id=body.get("entityId"),
                reserve=body.get("reserve", True),
                currency=body.get("currency"),
                claimed_category=body.get("claimedCategory"),
                parent_event_id=body.get("parentEventId"),
                intent_context=body.get("intentContext"),
                agent_id=body.get("agentId"),
                action_type=body.get("actionType"),
                description=body.get("description"),
            )
        except InvalidParentError as e:
            raise FiGuardApiError(400, str(e))
        return {
            "eventId": r.get("event_id"),
            "decision": r["decision"],
            "approvedQuantity": _f(r.get("approved_quantity")),
            "denialReason": r.get("denial_reason"),
            "denialMessage": r.get("denial_message"),
            "originalEventId": r.get("original_event_id"),
            "budgetSnapshot": _snap_json(r["budget"]),
        }

    def _event_json(self, r: dict) -> dict:
        return {
            "id": r["event_id"],
            "decision": r["decision"],
            "requestedQuantity": _f(r.get("requested_quantity")),
            "confirmedQuantity": _f(r.get("confirmed_quantity")),
            "currency": r.get("currency"),
            "createdAt": r.get("created_at") or "",
        }

    # Reshape the engine's tree (snake_case event dicts, nested {event, children}) into the
    # server's GET /tree JSON (camelCase) so the SDK's _parse_tree_node consumes either backend
    # identically. Recurses depth-first; the engine guarantees a forest (no cycles).
    def _tree_json(self, tree: dict) -> dict:
        return {
            "roots": [self._tree_node_json(n) for n in tree["roots"]],
            "totalEvents": tree["total_events"],
        }

    def _tree_node_json(self, node: dict) -> dict:
        return {
            "event": self._tree_event_json(node["event"]),
            "children": [self._tree_node_json(c) for c in node["children"]],
        }

    def _tree_event_json(self, e: dict) -> dict:
        return {
            "id": e["id"],
            "decision": e["decision"],
            "requestedQuantity": _f(e.get("requested_quantity")),
            "confirmedQuantity": _f(e.get("confirmed_quantity")),
            "currency": e.get("currency"),
            "entityId": e.get("entity_id"),
            "claimedCategory": e.get("claimed_category"),
            "agentId": e.get("agent_id"),
            "actionType": e.get("action_type"),
            "description": e.get("description"),
            "denialReason": e.get("denial_reason"),
            "parentEventId": e.get("parent_event_id"),
            "createdAt": e.get("created_at") or "",
        }

    def _budget_json(self, bid: str, create_body: dict, token: Optional[str], snapshot: dict) -> dict:
        # Metadata is read from the snapshot (which now carries it) so a GET is identical to the
        # CREATE response; create_body only supplies userId (not persisted on the budget row).
        return {
            "id": bid,
            "userId": snapshot.get("user_id") or create_body.get("userId", "embedded"),
            "totalLimit": _f(snapshot["total_limit"]),
            "quantitySpent": _f(snapshot["quantity_spent"]),
            "quantityReserved": _f(snapshot["quantity_reserved"]),
            "availableQuantity": _f(snapshot["available"]),
            "status": snapshot["status"],
            "expiresAt": snapshot.get("expires_at") or "",
            "currency": snapshot.get("currency"),
            "unit": snapshot.get("unit") or (None if snapshot.get("currency") else "usd"),
            "intentTags": snapshot.get("intent_tags"),
            "maxTransactionQuantity": _f(snapshot.get("max_transaction_quantity")),
            "velocityMaxPerMinute": snapshot.get("velocity_max_per_minute"),
            "velocityMaxAmountPerHour": _f(snapshot.get("velocity_max_amount_per_hour")),
            "velocityMaxPerDay": snapshot.get("velocity_max_per_day"),
            "tokens": [{"sessionToken": token, "category": None}] if token else None,
        }
