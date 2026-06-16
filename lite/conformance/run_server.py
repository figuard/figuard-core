"""Server-mode conformance: drive the SAME scenarios through FiGuardClient in SERVER mode
against a running FiGuard server, and assert identical behavior.

Proves the unified client's server path (its HTTP request/response mapping) is conformant —
the Java KernelConformanceIT already proves the server's REST semantics. Normally launched by
ServerConformanceIT, which boots a real server and passes its URL + a seeded API key.

    python lite/conformance/run_server.py --url http://localhost:8080 --api-key fg_live_...

Scenarios needing a non-ACTIVE initial budget (e.g. PAUSED) are skipped — the create API only
makes ACTIVE budgets, and that path is covered by the engine + Java gates.
"""

from __future__ import annotations

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "sdk", "python"))   # the figuard SDK
sys.path.insert(0, HERE)                                              # runner

from figuard import FiGuardClient  # noqa: E402
from runner import ClientEmbeddedAdapter, _optf, load_scenarios, run  # noqa: E402


class ServerAdapter(ClientEmbeddedAdapter):
    """Same client API as the embedded adapter, but constructed against a remote server.
    execute()/final_state() are inherited — the whole point is the calls are identical."""

    name = "FiGuardClient(server, HTTP)"

    def __init__(self, url: str, api_key: str):
        self._url = url
        self._api_key = api_key

    def new_budget(self, spec: dict):
        client = FiGuardClient(base_url=self._url, api_key=self._api_key, log=False)
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
            expires_in="23h",   # server requires an expiry; embedded defaults it
        )
        return client, budget


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--api-key", required=True)
    args = ap.parse_args()

    scenarios = [s for s in load_scenarios(os.path.join(HERE, "scenarios"))
                 if s.get("budget", {}).get("status") in (None, "ACTIVE")]
    adapter = ServerAdapter(args.url, args.api_key)
    results = run(adapter, scenarios)

    passed = sum(1 for _, f in results if not f)
    for sid, failures in results:
        print(f"[{'PASS' if not failures else 'FAIL'}] {sid}")
        for m in failures:
            print(f"        {m}")
    print(f"\n{passed}/{len(results)} scenarios passed against {adapter.name}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
