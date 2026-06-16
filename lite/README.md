# figuard-lite (embedded mode)

In-process FiGuard enforcement against a local SQLite file. **No Postgres, no JVM, no
server, no Docker.** Same `authorize`/`confirm`/denial semantics as the FiGuard server —
guaranteed by a conformance suite that runs the *same* scenarios against both.

> Status: early build (v0). Kernel slice working + conformance-gated. Not published.

```python
from figuard_lite import LiteEngine

fg = LiteEngine()                                  # embedded, local SQLite (:memory: or a path)
b  = fg.create_budget(total_limit="10.00")
r  = fg.authorize(budget_id=b, amount="3.00", idempotency_key="a1")
if r["decision"] == "AUTHORIZED":
    fg.confirm(event_id=r["event_id"], confirmed_quantity="2.50")
```

## What's in this slice
- **Kernel:** `create_budget`, `authorize` (capacity, `maxTransactionQuantity`, entity dedup,
  status gates, currency, `reserve=false`, idempotency replay), `confirm`, `fail`, `void`.
- **Money as `Decimal`, never float** — matches the server's `BigDecimal(scale=4)`.
- **Correctness:** `BEGIN IMMEDIATE` write transactions = the embedded equivalent of the
  server's pessimistic row lock (equivalent at single-process concurrency).
- **Capability boundary (Concern #2):** fleet ops (`create_delegation_token`,
  `create_subscription`, `register_webhook`) raise `FiGuardCapabilityError` with an upgrade
  pointer — the software draws the embedded/server line, users don't self-diagnose.

## The conformance gate (Concern #1 — drift is a failing build)
`conformance/scenarios/*.yaml` is the language-neutral contract. `conformance/runner.py`
drives it through an **implementation-agnostic adapter** — today Python-lite, soon the Java
core over HTTP — so the *same* scenarios assert embedded↔server parity in CI.

```bash
python conformance/runner.py          # human-readable pass/fail
PYTHONPATH=src python -m pytest -q     # CI gate (parametrized per scenario)
```

## Deferred (next slices)
velocity, causal-chain parent validation, intent scope; the **Java golden-vector adapter**
+ **differential/fuzz testing**; the backend-resolution layer (embedded ↔ server by config)
and SDK wiring. TS-lite is deferred until Python-lite proves adoption pull.
