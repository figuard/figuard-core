# FiGuard Benchmarks

FiGuard's core claim is **financial correctness under concurrency** — the budget is
never overdrawn and the same operation is never charged twice, no matter how many agents
race on it. This document proves that with a reproducible harness, and reports latency
honestly.

The harness is `bench/stress.py`. Run it yourself:

```bash
docker compose up -d        # start the stack
make bench                  # run the harness
```

## What's verified, and how

The key design choice: **the oracle is a SQL query against Postgres, not the HTTP
responses.** The test audits the ledger itself — the database is the ground truth. A
counter that lives in process memory has no equivalent; there is nothing durable to audit.

### Invariant 1 — No overspend, ever

100 agents concurrently request $50 against a single shared $1,000 budget. Exactly 20 can
be authorized. The oracle, run after every batch:

```sql
SELECT quantity_spent + quantity_reserved, total_limit
FROM agent_budgets WHERE id = :budget_id;
-- assert: quantity_spent + quantity_reserved <= total_limit, always
```

**Result:** across every run, exactly 20 authorized / 80 denied, and the budget lands at
**exactly $1,000.00 — never a cent over.** The pessimistic row lock serializes the
contenders; requests queue, they do not race.

### Invariant 2 — No double-spend, ever

The retry storm from the README's opening story: N unique idempotency keys, each fired M
times in parallel. The oracle:

```sql
SELECT count(*) FROM spend_events WHERE budget_id = :budget_id;
-- assert: exactly N events — one per key, regardless of how many times it was fired
```

**Result:** 1,000 concurrent requests across 200 keys produced **exactly 200 events, zero
duplicates.** The `(budget_id, idempotency_key)` unique constraint plus the
authorize-time idempotency check guarantee one charge per operation.

## Results

Hardware: Apple M1, 8 cores, 16 GB RAM. Stack via `docker compose up` (Spring Boot +
PostgreSQL 15 in containers). These are **local-dev numbers** — Docker overhead and a
dev-tuned JVM, not a production deployment. The correctness invariants are
hardware-independent; the latency numbers will improve on dedicated hardware.

### Correctness

| Invariant | Test | Result |
|---|---|---|
| No overspend | 500 concurrent authorizations on shared budgets | **0 overspends** — budget never exceeded |
| No double-spend | 1,000 retried requests across 200 idempotency keys | **0 duplicates** — exactly one event per key |

### Latency — typical (each authorize on its own budget, no contention)

This is the per-call cost a developer adds by wrapping a tool with `authorize()`.

| Percentile | Latency |
|---|---|
| p50 | 17 ms |
| p95 | 39 ms |
| p99 | 74 ms |

### Latency — worst case (100 agents fighting one budget's row lock)

By design, concurrent authorizations on the **same** budget serialize through a
pessimistic lock. This is the price of never overspending: requests queue rather than
race. It is a different number from typical per-call latency, and it scales with how many
agents share one budget.

| Percentile | Latency (includes lock-queue wait) |
|---|---|
| p50 | 508 ms |
| p95 | 1,451 ms |
| p99 | 1,613 ms |

The fix for a hot budget is structural, not a tuning knob: distribute load across multiple
budgets (or delegation tokens), so no single row is the bottleneck. The typical-latency
table above reflects that distributed case.

## Reproduce it

```bash
docker compose up -d
make bench
```

Adjust scale:

```bash
python bench/stress.py \
  --workers 100 --runs 10 \      # contention: 100 concurrent agents × 10 runs
  --keys 1000 --fires 50 \       # retry storm: 1000 keys × 50 parallel fires
  --lat-count 500                # typical-latency sample size
```

The harness fails loudly if any invariant is violated — a single overspend or duplicate
event exits non-zero. The numbers above are not asserted thresholds; the *invariants* are.

## Why this is a different claim than policy benchmarks

Policy-check tools can publish adversarial-robustness numbers ("0% bypass rate"). That
proves a stateless rule holds against malicious prompts. FiGuard's benchmark proves
something a stateless check cannot: **money holds against physics** — concurrency, retries,
and lock contention. A per-call policy evaluator has no answer to ten agents racing on one
budget, because it has no shared state to protect. That state, and the guarantees over it,
are the point.
