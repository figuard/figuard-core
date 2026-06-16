"""Differential-fuzz scenario generator.

Emits N seeded random scenarios (budget + a valid op sequence) with NO expected outputs —
the expectations come from running them against the authoritative Java core (golden vectors),
which figuard-lite must then reproduce. Catches drift in edge cases no hand-written scenario
covers, including reserve=false confirm/void (the case that previously diverged).

Fidelity rules so the two backends compare cleanly (these are harness concerns, not kernel
semantics):
  - available is tracked in EXACT Decimal, mirroring the engine, so amounts never land on the
    wrong side of a capacity boundary due to float drift.
  - confirm/fail/void only reference genuinely-AUTHORIZED events; the reserved flag is tracked
    so available updates match (confirm always spends; only a real reservation is released).
  - denials are kept unambiguous: non-dedup budgets deny only on capacity (INSUFFICIENT_FUNDS);
    dedup budgets are authorize-only with tiny amounts on a large limit so only dedup
    (ENTITY_ALREADY_AUTHORIZED) can fire. maxTransactionQuantity is covered by curated scenarios.

    python conformance/fuzz_generate.py --n 80 --seed 21
"""

from __future__ import annotations

import argparse
import os
import random
from decimal import ROUND_DOWN, Decimal

import yaml

TWO = Decimal("0.01")


def q2(x) -> Decimal:
    return Decimal(str(x)).quantize(TWO, rounding=ROUND_DOWN)


def _dedup_scenario(idx: int, rnd: random.Random) -> dict:
    total = q2(rnd.uniform(500, 1000))            # large limit so capacity never binds
    budget = {"total_limit": str(total), "currency": "USD", "entity_dedup_enabled": True}
    steps, used = [], []
    for j in range(rnd.randint(1, 6)):
        if used and rnd.random() < 0.4:
            ent = rnd.choice(used)                # reuse -> ENTITY_ALREADY_AUTHORIZED
        else:
            ent = f"e{idx}-{j}"
            used.append(ent)
        amt = q2(rnd.uniform(0.5, 5.0))           # tiny vs limit -> capacity never the reason
        steps.append({"op": "authorize",
                      "request": {"amount": str(amt), "entity_id": ent, "idempotency_key": f"k{idx}-{j}"}})
    return {"id": f"fuzz-{idx:03d}", "budget": budget, "steps": steps}


def _flow_scenario(idx: int, rnd: random.Random) -> dict:
    total = q2(rnd.uniform(50, 200))
    budget = {"total_limit": str(total), "currency": "USD"}
    steps: list = []
    available = total
    outstanding: list = []                        # (step_index, amount: Decimal, held: bool)

    for j in range(rnd.randint(1, 6)):
        key = f"k{idx}-{j}"
        if outstanding and rnd.random() < 0.35:
            op = rnd.choice(["confirm", "fail", "void"])
            i, amt, held = outstanding.pop(rnd.randrange(len(outstanding)))
            req = {"event_ref": f"$steps[{i}].event_id"}
            if op == "confirm":
                conf = q2(rnd.uniform(0, float(amt)))
                if conf < TWO:
                    conf = TWO if amt >= TWO else amt
                if conf > amt:
                    conf = amt
                req["confirmed_quantity"] = str(conf)
                available += (amt - conf) if held else (-conf)   # confirm always spends
            elif held:
                available += amt                                  # fail/void frees a real reservation
            steps.append({"op": op, "request": req})
        else:
            reserve = rnd.random() > 0.2
            if reserve:
                if rnd.random() < 0.3 or available < Decimal("1"):
                    amt = q2(float(max(available, Decimal("0"))) + rnd.uniform(1, 20))   # > available -> denial
                else:
                    amt = q2(rnd.uniform(0.01, float(available)))
                    if amt < TWO:
                        amt = TWO
                    if amt > available:
                        amt = available
                    outstanding.append((j, amt, True))
                    available -= amt
            else:
                amt = q2(rnd.uniform(0.5, float(total) * 0.3))   # reserve=false holds nothing
                if amt < TWO:
                    amt = TWO
                outstanding.append((j, amt, False))
            steps.append({"op": "authorize",
                          "request": {"amount": str(amt), "idempotency_key": key, "reserve": reserve}})

    return {"id": f"fuzz-{idx:03d}", "budget": budget, "steps": steps}


def gen_scenario(idx: int, rnd: random.Random) -> dict:
    return _dedup_scenario(idx, rnd) if rnd.random() < 0.3 else _flow_scenario(idx, rnd)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=80)
    ap.add_argument("--seed", type=int, default=21)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "fuzz", "scenarios.yaml"))
    args = ap.parse_args()

    rnd = random.Random(args.seed)
    scenarios = [gen_scenario(i, rnd) for i in range(args.n)]
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        yaml.safe_dump(scenarios, f, sort_keys=False, default_flow_style=False)
    print(f"wrote {len(scenarios)} fuzz scenarios (seed={args.seed}) to {args.out}")


if __name__ == "__main__":
    main()
