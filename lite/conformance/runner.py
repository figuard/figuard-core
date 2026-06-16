"""Conformance runner — drives the scenarios against an implementation and checks parity.

Implementation-agnostic by design: today it runs the in-process Python LiteEngine; the same
runner will later drive the Java core over HTTP and assert the *same* expectations. That is
the mechanism that makes embedded↔server drift a failing build, not a latent bug.

    python conformance/runner.py            # run all scenarios against Python-lite
"""

from __future__ import annotations

import glob
import os
import re
import sys
from decimal import Decimal, InvalidOperation

import yaml

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "sdk", "python"))
from figuard._embedded import LiteEngine  # noqa: E402  (engine now lives in the SDK)
from figuard import FiGuardClient  # noqa: E402
try:
    from figuard import clear_current_event_id as _clear_current_event_id  # noqa: E402
except ImportError:  # context module shape differs
    from figuard.context import clear_current_event_id as _clear_current_event_id  # noqa: E402

_REF = re.compile(r"^\$steps\[(\d+)\]\.(\w+)$")


def _optf(x):
    return float(x) if x is not None else None


class PythonLiteAdapter:
    """Runs a scenario against an in-process LiteEngine (fresh DB per scenario)."""

    name = "python-lite"

    def new_budget(self, spec: dict) -> tuple:
        engine = LiteEngine()  # :memory:
        bid = engine.create_budget(**spec)
        return engine, bid

    def execute(self, engine, bid: str, op: str, request: dict) -> dict:
        if op == "authorize":
            return engine.authorize(budget_id=bid, **request)
        if op == "confirm":
            return engine.confirm(event_id=request["event_ref"],
                                  confirmed_quantity=request["confirmed_quantity"])
        if op == "fail":
            return engine.fail(event_id=request["event_ref"], reason=request.get("reason"))
        if op == "void":
            return engine.void(event_id=request["event_ref"], reason=request.get("reason"))
        if op == "get_budget":
            return {"budget": engine.get_budget(bid)}
        raise ValueError(f"unknown op: {op}")

    def final_state(self, engine, bid: str) -> dict:
        return engine.get_budget(bid)


class ClientEmbeddedAdapter:
    """Runs a scenario through the PUBLIC FiGuardClient(mode='embedded') stack
    (client → backend → engine), proving the whole embedded path conforms — not just the
    raw engine. Skips non-ACTIVE-status scenarios (create makes ACTIVE budgets only)."""

    name = "FiGuardClient(mode='embedded')"

    def new_budget(self, spec: dict):
        client = FiGuardClient(mode="embedded", database=":memory:", log=False)  # isolated per scenario
        budget = client.create_budget(
            user_id="conformance",
            total_limit=float(spec["total_limit"]),
            currency=spec.get("currency"),
            unit=spec.get("unit"),
            max_transaction_quantity=_optf(spec.get("max_transaction_quantity")),
            intent_tags=spec.get("intent_tags"),
            entity_dedup_enabled=bool(spec.get("entity_dedup_enabled", False)),
            velocity_max_per_minute=spec.get("velocity_max_per_minute"),
            velocity_max_amount_per_hour=_optf(spec.get("velocity_max_amount_per_hour")),
            velocity_max_per_day=spec.get("velocity_max_per_day"),
        )
        return client, budget

    def execute(self, client, budget, op: str, request: dict) -> dict:
        if op == "authorize":
            a = client.authorize(
                budget=budget,
                amount=float(request["amount"]),
                idempotency_key=request.get("idempotency_key"),
                entity_id=request.get("entity_id"),
                reserve=request.get("reserve", True),
                currency=request.get("currency"),
                claimed_category=request.get("claimed_category"),
                parent_event_id=request.get("parent_event_id"),
                intent_context=request.get("intent_context"),
            )
            return {"decision": a.decision, "event_id": a.event_id,
                    "approved_quantity": a.approved_quantity, "denial_reason": a.denial_reason}
        if op == "confirm":
            ev = client.confirm_event(request["event_ref"], float(request["confirmed_quantity"]))
            return {"decision": ev.decision}
        if op == "fail":
            ev = client.fail_event(request["event_ref"], request.get("reason") or "x")
            return {"decision": ev.decision}
        if op == "void":
            vr = client.void_event(request["event_ref"], request.get("reason") or "x")
            return {"decision": vr.event.decision}
        if op == "get_budget":
            return {"budget": self.final_state(client, budget)}
        raise ValueError(f"unknown op: {op}")

    def final_state(self, client, budget) -> dict:
        snap = client.get_budget(budget.id)
        return {"available": snap.available_quantity,
                "quantity_reserved": snap.quantity_reserved,
                "quantity_spent": snap.quantity_spent}


def _resolve_refs(request: dict, step_results: list) -> dict:
    out = {}
    for k, v in request.items():
        if isinstance(v, str):
            m = _REF.match(v)
            if m:
                out[k] = step_results[int(m.group(1))][m.group(2)]
                continue
        out[k] = v
    return out


def _equal(expected, actual) -> bool:
    if isinstance(expected, bool) or isinstance(actual, bool):
        return bool(expected) == bool(actual)
    try:
        return Decimal(str(expected)) == Decimal(str(actual))
    except (InvalidOperation, ValueError):
        return str(expected) == str(actual)


def _check(expect: dict, actual: dict, where: str, failures: list):
    for key, exp in expect.items():
        if key not in actual:
            failures.append(f"{where}: missing key '{key}' (expected {exp!r})")
        elif not _equal(exp, actual[key]):
            failures.append(f"{where}: {key} expected {exp!r}, got {actual[key]!r}")


def load_scenarios(scenarios_dir: str) -> list:
    scenarios = []
    for path in sorted(glob.glob(os.path.join(scenarios_dir, "*.yaml"))):
        with open(path) as f:
            scenarios.extend(yaml.safe_load(f) or [])
    return scenarios


def run(adapter, scenarios: list) -> list:
    results = []
    for sc in scenarios:
        failures: list = []
        # Each scenario is an independent budget — clear any ambient parent-event the SDK's
        # ContextVar carried over from a prior scenario (harmless for the engine adapter).
        _clear_current_event_id()
        try:
            engine, bid = adapter.new_budget(sc["budget"])
            step_results = []
            for i, step in enumerate(sc.get("steps", [])):
                req = _resolve_refs(step.get("request", {}), step_results)
                expect = step.get("expect")
                where = f"{sc['id']} step[{i}]"
                # An `error` expectation means the op must raise/4xx with a matching message
                # (e.g. INVALID_PARENT_EVENT) rather than return a decision.
                if expect and "error" in expect:
                    try:
                        adapter.execute(engine, bid, step["op"], req)
                        failures.append(f"{where}: expected error containing {expect['error']!r} but none raised")
                    except Exception as ex:  # noqa: BLE001
                        if expect["error"] not in str(ex):
                            failures.append(f"{where}: expected error {expect['error']!r}, got {ex!r}")
                    step_results.append({})
                    continue
                resp = adapter.execute(engine, bid, step["op"], req)
                step_results.append(resp)
                if expect:
                    _check(expect, resp, where, failures)
            if "final_state" in sc:
                _check(sc["final_state"], adapter.final_state(engine, bid),
                       f"{sc['id']} final_state", failures)
        except Exception as e:  # noqa: BLE001
            failures.append(f"{sc['id']}: raised {type(e).__name__}: {e}")
        results.append((sc["id"], failures))
    return results


def main() -> int:
    scenarios_dir = os.path.join(os.path.dirname(__file__), "scenarios")
    scenarios = load_scenarios(scenarios_dir)
    adapter = PythonLiteAdapter()
    results = run(adapter, scenarios)

    passed = sum(1 for _, f in results if not f)
    for sid, failures in results:
        mark = "PASS" if not failures else "FAIL"
        print(f"[{mark}] {sid}")
        for msg in failures:
            print(f"        {msg}")
    print(f"\n{passed}/{len(results)} scenarios passed against {adapter.name}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
