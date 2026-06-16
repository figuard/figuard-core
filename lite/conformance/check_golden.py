"""Differential check: figuard-lite must reproduce the Java core's golden vectors.

Loads the fuzz scenarios + the golden.json the Java core produced, runs each scenario
through Python-lite, and asserts the per-step decision/denial/approved and the final budget
state match the Java outputs exactly. A mismatch is a real embedded↔server divergence.

    python conformance/check_golden.py \
        --scenarios conformance/fuzz/scenarios.yaml --golden conformance/fuzz/golden.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import yaml

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from conformance.runner import PythonLiteAdapter, _equal, _resolve_refs  # noqa: E402


def _record(adapter, engine, bid, scenario) -> dict:
    step_results = []
    step_records = []
    for step in scenario.get("steps", []):
        req = _resolve_refs(step.get("request", {}), step_results)
        resp = adapter.execute(engine, bid, step["op"], req)
        step_results.append(resp)
        rec = {"decision": resp.get("decision")}
        if resp.get("denial_reason") is not None:
            rec["denial_reason"] = resp["denial_reason"]
        if resp.get("approved_quantity") is not None:
            rec["approved_quantity"] = resp["approved_quantity"]
        step_records.append(rec)
    snap = adapter.final_state(engine, bid)
    return {
        "steps": step_records,
        "final_state": {k: snap[k] for k in ("available", "quantity_reserved", "quantity_spent")},
    }


def check(scenario: dict, golden: dict, failures: list):
    adapter = PythonLiteAdapter()
    engine, bid = adapter.new_budget(scenario["budget"])
    actual = _record(adapter, engine, bid, scenario)
    sid = scenario["id"]

    g_steps, a_steps = golden.get("steps", []), actual["steps"]
    if len(g_steps) != len(a_steps):
        failures.append(f"{sid}: step count java={len(g_steps)} lite={len(a_steps)}")
        return
    for i, (g, a) in enumerate(zip(g_steps, a_steps)):
        for k, gv in g.items():
            if not _equal(gv, a.get(k)):
                failures.append(f"{sid} step[{i}] {k}: java={gv!r} lite={a.get(k)!r}")
    for k, gv in golden.get("final_state", {}).items():
        if not _equal(gv, actual["final_state"].get(k)):
            failures.append(f"{sid} final_state {k}: java={gv!r} lite={actual['final_state'].get(k)!r}")


def main() -> int:
    here = os.path.dirname(__file__)
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", default=os.path.join(here, "fuzz", "scenarios.yaml"))
    ap.add_argument("--golden", default=os.path.join(here, "fuzz", "golden.json"))
    args = ap.parse_args()

    with open(args.scenarios) as f:
        scenarios = yaml.safe_load(f) or []
    with open(args.golden) as f:
        golden = {g["id"]: g for g in json.load(f)}

    failures: list = []
    for sc in scenarios:
        g = golden.get(sc["id"])
        if g is None:
            failures.append(f"{sc['id']}: no golden vector from Java")
            continue
        check(sc, g, failures)

    if failures:
        print(f"DIFFERENTIAL FUZZ FAILED ({len(failures)} mismatches):")
        for m in failures[:50]:
            print(f"  {m}")
        return 1
    print(f"DIFFERENTIAL FUZZ PASSED — Python-lite matched the Java core on {len(scenarios)} random scenarios.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
