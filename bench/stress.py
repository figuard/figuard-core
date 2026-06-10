#!/usr/bin/env python3
"""
FiGuard concurrency stress harness.

Proves three invariants that a counter-library cannot:

  1. NO OVERSPEND   — N agents hammer one shared budget; the ledger never
                      exceeds the limit, even by a cent.
  2. NO DOUBLE-SPEND — the same idempotency key fired M times in parallel
                      produces exactly one event.
  3. (latency)      — p50 / p95 / p99 under sustained concurrent load.

The oracle is a SQL query against Postgres, NOT the HTTP responses. The test
audits the ledger itself — the database is the ground truth. That is the part
a stateless counter cannot reproduce.

Usage:
    # bring the stack up first:  docker compose up -d
    python bench/stress.py
    python bench/stress.py --workers 200 --runs 5      # heavier

Requires: httpx  (pip install httpx)
Postgres oracle runs via `docker exec <container> psql` — no DB driver needed.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass

try:
    import httpx
except ImportError:
    sys.exit("This harness needs httpx:  pip install httpx")


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

BASE_URL = os.environ.get("FIGUARD_BASE_URL", "http://localhost:8080")
API_KEY = os.environ.get("FIGUARD_API_KEY", "fg_live_demo")
PG_CONTAINER = os.environ.get("FIGUARD_PG_CONTAINER", "spentinel-core-postgres-1")
PG_USER = os.environ.get("FIGUARD_PG_USER", "figuard")
PG_DB = os.environ.get("FIGUARD_PG_DB", "figuard")

HEADERS = {"X-Agent-Budget-Key": API_KEY, "Content-Type": "application/json"}


# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

def section(title: str) -> None:
    print(f"\n{'=' * 70}\n  {title}\n{'=' * 70}")

def ok(msg: str) -> None:    print(f"  \033[32m✓\033[0m {msg}")
def bad(msg: str) -> None:   print(f"  \033[31m✗\033[0m {msg}")
def info(msg: str) -> None:  print(f"    {msg}")


# ---------------------------------------------------------------------------
# Postgres oracle — the ground truth
# ---------------------------------------------------------------------------

def psql(query: str) -> str:
    """Run a SQL query inside the Postgres container, return trimmed scalar output."""
    result = subprocess.run(
        ["docker", "exec", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB,
         "-tAc", query],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip()


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

async def create_budget(client: httpx.AsyncClient, total_limit: float) -> tuple[str, str]:
    """Create a budget, return (budget_id, session_token)."""
    resp = await client.post(
        f"{BASE_URL}/api/v1/budgets",
        headers=HEADERS,
        json={
            "userId": f"bench_{uuid.uuid4().hex[:8]}",
            "totalLimit": total_limit,
            "currency": "USD",
            "intentContext": "stress benchmark",
            "expiresAt": _expiry(),
        },
    )
    resp.raise_for_status()
    body = resp.json()
    return body["id"], body["tokens"][0]["sessionToken"]


def _expiry() -> str:
    from datetime import datetime, timezone, timedelta
    return (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()


async def authorize(client: httpx.AsyncClient, token: str, amount: float,
                    idem_key: str) -> tuple[str, float]:
    """Fire one authorize. Returns (decision, latency_ms)."""
    t0 = time.perf_counter()
    resp = await client.post(
        f"{BASE_URL}/api/v1/authorize",
        headers={**HEADERS, "X-Session-Token": token},
        json={
            "agentId": "bench_agent",
            "actionType": "BENCH",
            "description": "stress authorization",
            "requestedQuantity": amount,
            "currency": "USD",
            "idempotencyKey": idem_key,
        },
    )
    latency_ms = (time.perf_counter() - t0) * 1000
    if resp.status_code != 200:
        return f"HTTP_{resp.status_code}", latency_ms
    return resp.json().get("decision", "UNKNOWN"), latency_ms


# ---------------------------------------------------------------------------
# Scenario 1 — contention: no overspend, ever
# ---------------------------------------------------------------------------

@dataclass
class ContentionResult:
    workers: int
    authorized: int
    denied: int
    expected_authorized: int
    spent_plus_reserved: float
    total_limit: float
    latencies: list[float]


async def scenario_contention(client: httpx.AsyncClient, *, workers: int,
                              amount: float, total_limit: float) -> ContentionResult:
    """
    `workers` agents each request `amount` against one shared budget of
    `total_limit`. Exactly floor(total_limit / amount) should be AUTHORIZED.
    The oracle: quantity_spent + quantity_reserved <= total_limit. Always.
    """
    budget_id, token = await create_budget(client, total_limit)
    # At most `capacity` can be authorized, but never more than we actually fire.
    capacity = int(total_limit // amount)
    expected = min(workers, capacity)

    tasks = [
        authorize(client, token, amount, f"contention-{uuid.uuid4().hex}")
        for _ in range(workers)
    ]
    results = await asyncio.gather(*tasks)

    decisions = [d for d, _ in results]
    latencies = [lat for _, lat in results]
    authorized = sum(1 for d in decisions if d == "AUTHORIZED")
    denied = sum(1 for d in decisions if d == "DENIED")

    # Oracle — ask Postgres, not the HTTP responses
    row = psql(
        f"SELECT quantity_spent + quantity_reserved, total_limit "
        f"FROM agent_budgets WHERE id = '{budget_id}'"
    )
    committed_str, limit_str = row.split("|")
    return ContentionResult(
        workers=workers,
        authorized=authorized,
        denied=denied,
        expected_authorized=expected,
        spent_plus_reserved=float(committed_str),
        total_limit=float(limit_str),
        latencies=latencies,
    )


# ---------------------------------------------------------------------------
# Scenario 2 — retry storm: no double-spend, ever
# ---------------------------------------------------------------------------

@dataclass
class RetryStormResult:
    keys: int
    fires_per_key: int
    total_requests: int
    events_in_ledger: int
    duplicate_keys: int


async def scenario_retry_storm(client: httpx.AsyncClient, *, keys: int,
                               fires_per_key: int, amount: float) -> RetryStormResult:
    """
    `keys` unique idempotency keys, each fired `fires_per_key` times concurrently.
    Oracle: exactly `keys` events in the ledger, zero duplicates per key.
    """
    total_limit = keys * amount * 2  # headroom so all unique keys authorize
    budget_id, token = await create_budget(client, total_limit)

    idem_keys = [f"retry-{uuid.uuid4().hex}" for _ in range(keys)]
    tasks = [
        authorize(client, token, amount, k)
        for k in idem_keys
        for _ in range(fires_per_key)
    ]
    await asyncio.gather(*tasks)

    # Oracle — count events and check no key produced more than one
    events = int(psql(
        f"SELECT count(*) FROM spend_events WHERE budget_id = '{budget_id}'"
    ))
    dupes = int(psql(
        f"SELECT count(*) FROM ("
        f"  SELECT idempotency_key FROM spend_events WHERE budget_id = '{budget_id}'"
        f"  GROUP BY idempotency_key HAVING count(*) > 1"
        f") d"
    ))
    return RetryStormResult(
        keys=keys,
        fires_per_key=fires_per_key,
        total_requests=keys * fires_per_key,
        events_in_ledger=events,
        duplicate_keys=dupes,
    )


# ---------------------------------------------------------------------------
# Scenario 3 — uncontended latency: each call on its own budget
# ---------------------------------------------------------------------------

async def scenario_latency_uncontended(client: httpx.AsyncClient, *,
                                       count: int, concurrency: int) -> list[float]:
    """
    The 'typical' authorize cost: each call hits a different budget, so there is
    no lock contention. This is the number a developer asking 'what does
    authorize() add to my p99' actually wants. Distinct from the single-hot-budget
    worst case, where requests serialize on one row lock by design.
    """
    # Pre-create budgets so budget creation isn't counted in authorize latency.
    budgets = await asyncio.gather(*[
        create_budget(client, 1000.0) for _ in range(count)
    ])

    sem = asyncio.Semaphore(concurrency)

    async def one(token: str) -> float:
        async with sem:
            _, lat = await authorize(client, token, 1.0, f"lat-{uuid.uuid4().hex}")
            return lat

    return await asyncio.gather(*[one(tok) for _, tok in budgets])


# ---------------------------------------------------------------------------
# Latency summary
# ---------------------------------------------------------------------------

def pct(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = min(int(len(s) * p), len(s) - 1)
    return s[idx]


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def main(args: argparse.Namespace) -> int:
    failures = 0
    all_latencies: list[float] = []
    total_authorizations = 0
    total_retry_requests = 0
    total_dupes = 0

    async with httpx.AsyncClient(timeout=30.0, limits=httpx.Limits(
        max_connections=args.workers + 50, max_keepalive_connections=args.workers + 50
    )) as client:

        # Sanity: server reachable
        try:
            h = await client.get(f"{BASE_URL}/actuator/health")
            if h.status_code != 200:
                bad(f"Server health check returned {h.status_code} at {BASE_URL}")
                return 1
        except Exception as exc:
            bad(f"Cannot reach FiGuard at {BASE_URL}: {exc}")
            info("Bring the stack up first:  docker compose up -d")
            return 1

        # ── Scenario 1: contention ──────────────────────────────────────────
        section(f"SCENARIO 1 — CONTENTION ({args.runs} runs × {args.workers} concurrent agents)")
        info(f"Each run: {args.workers} agents request $50 against one $1,000 budget.")
        info(f"Expected per run: 20 AUTHORIZED, {args.workers - 20} DENIED. Oracle: spent+reserved ≤ limit.\n")

        for run in range(1, args.runs + 1):
            r = await scenario_contention(client, workers=args.workers,
                                          amount=50.0, total_limit=1000.0)
            total_authorizations += r.workers
            all_latencies.extend(r.latencies)

            overspend = r.spent_plus_reserved > r.total_limit + 1e-9
            wrong_count = r.authorized != r.expected_authorized

            tag = f"run {run}/{args.runs}"
            if overspend:
                bad(f"{tag}: OVERSPEND — committed ${r.spent_plus_reserved:.2f} > limit ${r.total_limit:.2f}")
                failures += 1
            elif wrong_count:
                bad(f"{tag}: count off — {r.authorized} authorized, expected {r.expected_authorized}")
                failures += 1
            else:
                ok(f"{tag}: {r.authorized} authorized / {r.denied} denied · "
                   f"committed ${r.spent_plus_reserved:.2f} ≤ ${r.total_limit:.2f}")

        # ── Scenario 2: retry storm ─────────────────────────────────────────
        section(f"SCENARIO 2 — RETRY STORM ({args.keys} keys × {args.fires} parallel fires)")
        info(f"{args.keys} unique idempotency keys, each fired {args.fires} times concurrently.")
        info(f"Oracle: exactly {args.keys} events in the ledger, zero duplicates.\n")

        rs = await scenario_retry_storm(client, keys=args.keys,
                                        fires_per_key=args.fires, amount=1.0)
        total_retry_requests += rs.total_requests
        total_dupes += rs.duplicate_keys

        if rs.duplicate_keys > 0:
            bad(f"DOUBLE-SPEND — {rs.duplicate_keys} keys produced more than one event")
            failures += 1
        elif rs.events_in_ledger != rs.keys:
            bad(f"event count off — {rs.events_in_ledger} events, expected {rs.keys}")
            failures += 1
        else:
            ok(f"{rs.total_requests:,} requests → exactly {rs.events_in_ledger:,} events · 0 duplicates")

        # ── Scenario 3: uncontended latency ─────────────────────────────────
        section(f"SCENARIO 3 — LATENCY (typical: each authorize on its own budget)")
        info(f"{args.lat_count} authorize calls, each against a different budget — no lock contention.")
        info(f"This is the per-call cost a developer adds by wrapping a tool with authorize().\n")
        uncontended = await scenario_latency_uncontended(
            client, count=args.lat_count, concurrency=args.lat_concurrency)
        ok(f"p50: {pct(uncontended, 0.50):6.1f} ms")
        ok(f"p95: {pct(uncontended, 0.95):6.1f} ms")
        ok(f"p99: {pct(uncontended, 0.99):6.1f} ms")
        info(f"({len(uncontended):,} calls, {args.lat_concurrency} concurrent)")

    # ── Contended latency (worst case, for context) ──────────────────────────
    section("LATENCY (worst case: 100 agents fighting ONE budget's row lock)")
    info("By design, concurrent authorizations on the same budget serialize through")
    info("a pessimistic lock. This is the price of never overspending — requests queue,")
    info("they do not race. Distinct from the typical per-call latency above.")
    print()
    ok(f"p50: {pct(all_latencies, 0.50):6.1f} ms  (includes lock-queue wait)")
    ok(f"p95: {pct(all_latencies, 0.95):6.1f} ms")
    ok(f"p99: {pct(all_latencies, 0.99):6.1f} ms")
    info(f"({len(all_latencies):,} authorize calls under single-budget contention)")

    # ── Headline ────────────────────────────────────────────────────────────
    section("RESULT")
    if failures == 0:
        ok(f"0 overspends across {total_authorizations:,} concurrent authorizations")
        ok(f"0 double-charges across {total_retry_requests:,} retried requests")
        print()
        print(f"  All invariants held. The ledger never lost money.")
        return 0
    else:
        bad(f"{failures} invariant violation(s) — see above")
        return 1


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="FiGuard concurrency stress harness")
    p.add_argument("--workers", type=int, default=100,
                   help="concurrent agents per contention run (default 100)")
    p.add_argument("--runs", type=int, default=10,
                   help="contention runs (default 10 → 1,000 authorizations)")
    p.add_argument("--keys", type=int, default=1000,
                   help="unique idempotency keys in the retry storm (default 1000)")
    p.add_argument("--fires", type=int, default=50,
                   help="parallel fires per key (default 50 → 50,000 requests)")
    p.add_argument("--lat-count", type=int, default=500,
                   help="authorize calls for the typical-latency measurement (default 500)")
    p.add_argument("--lat-concurrency", type=int, default=20,
                   help="concurrency for the typical-latency measurement (default 20)")
    return p.parse_args()


if __name__ == "__main__":
    sys.exit(asyncio.run(main(parse_args())))
