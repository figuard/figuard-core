"""Embedded FiGuard pressure / stress suite.

One place that exercises EVERY single-agent embedded capability plus boundary / precision /
volume / concurrency / persistence stress — so we don't re-derive it each time. Complements
test_conformance.py (which proves cross-implementation parity); this proves the embedded stack
holds up under load and at its edges.

    pytest lite/tests/test_pressure.py -v        # as part of the suite
    python  lite/tests/test_pressure.py          # human-readable dogfood report
"""

import os
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal

import pytest

ROOT = os.path.dirname(os.path.dirname(__file__))                  # spentinel-core/lite
sys.path.insert(0, os.path.join(ROOT, "..", "sdk", "python"))     # the figuard SDK

from figuard import FiGuardClient, clear_current_event_id          # noqa: E402
from figuard.exceptions import FiGuardError, FiGuardCapabilityError  # noqa: E402
from figuard._embedded import LiteEngine                           # noqa: E402


# --- helpers -----------------------------------------------------------------

def emb(db: str = ":memory:") -> FiGuardClient:
    """A fresh embedded client with the ambient causal-chain reset (avoids cross-test leakage)."""
    clear_current_event_id()
    return FiGuardClient(mode="embedded", database=db, log=False)


def invariant_holds(c: FiGuardClient, bid: str) -> bool:
    """total == spent + reserved + available, exactly (Decimal, no float drift)."""
    b = c.get_budget(bid)
    return Decimal(str(b.total_limit)) == (
        Decimal(str(b.quantity_spent)) + Decimal(str(b.quantity_reserved))
        + Decimal(str(b.available_quantity)))


# --- A. create-option coverage ----------------------------------------------

def test_create_option_coverage():
    """Every create option embedded supports round-trips through get_budget."""
    c = emb()
    assert c.get_budget(c.create_budget(user_id="u", total_limit=100.0, currency="USD").id).currency == "USD"
    assert c.get_budget(c.create_budget(user_id="u", total_limit=5000, unit="tokens").id).unit == "tokens"
    assert c.get_budget(c.create_budget(user_id="u", total_limit=100.0, currency="USD",
                                        max_transaction_quantity=10).id).max_transaction_quantity == 10
    assert c.get_budget(c.create_budget(user_id="u", total_limit=100.0, currency="USD",
                                        velocity_max_per_minute=3).id).velocity_max_per_minute == 3
    assert c.get_budget(c.create_budget(user_id="u", total_limit=100.0, currency="USD",
                                        intent_tags=["flights"]).id).intent_tags == ["flights"]
    assert c.get_budget(c.create_budget(user_id="u", total_limit=100.0, currency="USD",
                                        expires_in="1h").id).expires_at


def test_create_refuses_server_only_options():
    """Embedded must refuse create options it can't enforce, not silently accept them."""
    c = emb()
    for kw in ({"allocations": [{"category": "ads", "total_limit": 10}]},
               {"trust_mode": "SHADOW"},
               {"anomaly_detection_enabled": True}):
        with pytest.raises(FiGuardCapabilityError):
            c.create_budget(user_id="u", total_limit=100.0, currency="USD", **kw)
    # explicit STRICT + advisory soft_limit are allowed
    assert c.create_budget(user_id="u", total_limit=100.0, currency="USD", trust_mode="STRICT")
    assert c.create_budget(user_id="u", total_limit=100.0, currency="USD", soft_limit=50.0)


# --- B. lifecycle + every denial code ---------------------------------------

def test_lifecycle_paths_keep_invariant():
    c = emb()
    b = c.create_budget(user_id="u", total_limit=100.0, currency="USD")
    a = c.authorize(budget=b, amount=30.0); c.confirm(a, 30.0)
    assert c.get_budget(b.id).quantity_spent == 30.0 and invariant_holds(c, b.id)
    a2 = c.authorize(budget=b, amount=20.0); c.confirm(a2, 12.5)            # partial confirm
    assert c.get_budget(b.id).quantity_spent == 42.5 and invariant_holds(c, b.id)
    clear_current_event_id()
    a3 = c.authorize(budget=b, amount=10.0); c.fail_event(event_id=a3.event_id, reason="x")
    assert invariant_holds(c, b.id) and c.get_budget(b.id).quantity_reserved == 0
    clear_current_event_id()
    a4 = c.authorize(budget=b, amount=10.0); c.void_event(event_id=a4.event_id, reason="x")
    assert invariant_holds(c, b.id) and c.get_budget(b.id).quantity_reserved == 0


def test_reserve_false_holds_nothing():
    c = emb(); b = c.create_budget(user_id="u", total_limit=10.0, currency="USD")
    r = c.authorize(budget=b, amount=6.0, reserve=False)
    assert c.get_budget(b.id).available_quantity == 10.0       # nothing held
    c.confirm(r, 6.0)
    assert c.get_budget(b.id).quantity_spent == 6.0 and invariant_holds(c, b.id)


def test_capacity_boundary():
    c = emb(); b = c.create_budget(user_id="u", total_limit=10.0, currency="USD")
    assert c.authorize(budget=b, amount=10.0).decision == "AUTHORIZED"      # exact fit
    over = c.authorize(budget=b, amount=0.0001)
    assert over.decision == "DENIED" and over.denial_reason == "INSUFFICIENT_FUNDS"


def test_max_transaction_denial():
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD", max_transaction_quantity=10)
    d = c.authorize(budget=b, amount=11.0)
    assert d.decision == "DENIED" and d.denial_reason == "EXCEEDS_QUANTITY_LIMIT"


def test_entity_dedup_denial():
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD", entity_dedup_enabled=True)
    c.authorize(budget=b, amount=5.0, entity_id="order-1")
    d = c.authorize(budget=b, amount=5.0, entity_id="order-1")
    assert d.decision == "DENIED" and d.denial_reason == "ENTITY_ALREADY_AUTHORIZED"


@pytest.mark.parametrize("kw,fire", [
    ({"velocity_max_per_minute": 3}, 3),
    ({"velocity_max_per_day": 2}, 2),
])
def test_velocity_count_denials(kw, fire):
    c = emb(); b = c.create_budget(user_id="u", total_limit=1000.0, currency="USD", **kw)
    for _ in range(fire):
        c.authorize(budget=b, amount=1.0)
    d = c.authorize(budget=b, amount=1.0)
    assert d.decision == "DENIED" and d.denial_reason == "VELOCITY_LIMIT_EXCEEDED"


def test_velocity_amount_per_hour_denial():
    c = emb(); b = c.create_budget(user_id="u", total_limit=1000.0, currency="USD", velocity_max_amount_per_hour=10.0)
    c.authorize(budget=b, amount=6.0)
    d = c.authorize(budget=b, amount=6.0)
    assert d.decision == "DENIED" and d.denial_reason == "VELOCITY_LIMIT_EXCEEDED"


def test_intent_scope_denials():
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD", intent_tags=["flights"])
    assert c.authorize(budget=b, amount=5.0, intent_context="book flights").decision == "AUTHORIZED"
    miss = c.authorize(budget=b, amount=5.0, intent_context="")
    nomatch = c.authorize(budget=b, amount=5.0, intent_context="buy a hotel")
    assert miss.denial_reason == "INTENT_SCOPE_VIOLATION" and nomatch.denial_reason == "INTENT_SCOPE_VIOLATION"


def test_currency_mismatch_denial():
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD")
    d = c.authorize(budget=b, amount=5.0, currency="EUR")
    assert d.decision == "DENIED" and d.denial_reason == "CURRENCY_MISMATCH"


def test_budget_expired_denial():
    """Engine-direct past-expiry (no sleep): BUDGET_EXPIRED is enforced when expiresAt is set."""
    eng = LiteEngine()
    bid = eng.create_budget(total_limit=100, currency="USD", expires_at="2000-01-01T00:00:00+00:00")
    r = eng.authorize(budget_id=bid, amount=5)
    assert r["decision"] == "DENIED" and r["denial_reason"] == "BUDGET_EXPIRED"


def test_idempotency_replay():
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD")
    k1 = c.authorize(budget=b, amount=5.0, idempotency_key="key-1")
    k2 = c.authorize(budget=b, amount=5.0, idempotency_key="key-1")
    assert k1.event_id == k2.event_id and c.get_budget(b.id).quantity_reserved == 5.0


def test_causal_chain_validation():
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD")
    p = c.authorize(budget=b, amount=10.0); c.confirm(p, 10.0)
    assert c.authorize(budget=b, amount=5.0, parent_event_id=p.event_id).decision == "AUTHORIZED"
    clear_current_event_id()
    with pytest.raises(FiGuardError):
        c.authorize(budget=b, amount=1.0, parent_event_id="does-not-exist")


# --- C. money precision ------------------------------------------------------

def test_money_precision_no_float_drift():
    c = emb(); b = c.create_budget(user_id="u", total_limit=1.0, currency="USD")
    evs = [c.authorize(budget=b, amount=0.3333) for _ in range(3)]          # 0.9999 reserved
    assert all(e.decision == "AUTHORIZED" for e in evs)
    assert Decimal(str(c.get_budget(b.id).available_quantity)) == Decimal("0.0001")
    assert invariant_holds(c, b.id)
    c.confirm(evs[0], 0.3333)
    assert invariant_holds(c, b.id)


# --- D. volume / throughput --------------------------------------------------

def test_volume_throughput():
    c = emb(); b = c.create_budget(user_id="u", total_limit=1_000_000.0, currency="USD")
    N = 2000
    t0 = time.time()
    for _ in range(N):
        clear_current_event_id()
        e = c.authorize(budget=b, amount=1.0); c.confirm(e, 1.0)
    dt = time.time() - t0
    assert c.get_budget(b.id).quantity_spent == float(N) and invariant_holds(c, b.id)
    print(f"\n  throughput: {N/dt:,.0f} authorize+confirm/sec ({dt*1000/N:.2f} ms/cycle)")


# --- E. concurrency ----------------------------------------------------------

def test_shared_client_thread_safe():
    """One client shared across threads: no sqlite errors, capacity respected, no overspend."""
    c = emb(); b = c.create_budget(user_id="u", total_limit=10.0, currency="USD")
    res = {"AUTH": 0, "DENY": 0, "ERR": 0}; lk = threading.Lock()

    def worker(_):
        try:
            r = c.authorize(budget=b, amount=1.0)
            with lk: res["AUTH" if r.decision == "AUTHORIZED" else "DENY"] += 1
        except Exception:
            with lk: res["ERR"] += 1

    with ThreadPoolExecutor(max_workers=8) as ex:
        list(ex.map(worker, range(40)))
    assert res["ERR"] == 0, res
    assert res["AUTH"] <= 10
    assert Decimal(str(c.get_budget(b.id).quantity_reserved)) <= Decimal("10")


def test_concurrent_connections_no_overspend():
    """Separate clients (own connections) on the SAME db file serialize via SQLite — no overspend."""
    dbf = tempfile.mktemp(suffix=".db")
    setup = emb(dbf); b = setup.create_budget(user_id="u", total_limit=10.0, currency="USD")
    res = {"AUTH": 0, "DENY": 0, "ERR": 0}; lk = threading.Lock()

    def worker(_):
        try:
            cc = FiGuardClient(mode="embedded", database=dbf, log=False)
            r = cc.authorize(budget=b, amount=1.0)
            with lk: res["AUTH" if r.decision == "AUTHORIZED" else "DENY"] += 1
        except Exception:
            with lk: res["ERR"] += 1

    with ThreadPoolExecutor(max_workers=12) as ex:
        list(ex.map(worker, range(30)))
    final = FiGuardClient(mode="embedded", database=dbf, log=False).get_budget(b.id)
    assert Decimal(str(final.quantity_reserved)) <= Decimal("10")          # never overspent
    assert res["AUTH"] <= 10


# --- F. persistence under load ----------------------------------------------

def test_persistence_reload():
    dbf = tempfile.mktemp(suffix=".db")
    c = emb(dbf)
    b = c.create_budget(user_id="alice", total_limit=100.0, unit="tokens", max_transaction_quantity=20)
    p = c.authorize(budget=b, amount=10.0, description="parent"); c.confirm(p, 10.0)
    c.authorize(budget=b, amount=4.0, parent_event_id=p.event_id, description="child")

    fresh = FiGuardClient(mode="embedded", database=dbf, log=False)        # simulate a restart
    clear_current_event_id()
    assert fresh.authorize(budget=b, amount=5.0).decision == "AUTHORIZED"  # token persisted
    g = fresh.get_budget(b.id)
    assert g.user_id == "alice" and g.unit == "tokens" and g.max_transaction_quantity == 20
    assert fresh.get_spend_tree(b.id).total_events >= 3                    # events persisted


def test_database_path_autocreates_parent_dir():
    """A custom database= path in a not-yet-existing directory just works (parent dir created)."""
    nested = os.path.join(tempfile.mkdtemp(), "deep", "nested", "dir", "budgets.db")
    assert not os.path.exists(os.path.dirname(nested))
    clear_current_event_id()
    c = FiGuardClient(mode="embedded", database=nested, log=False)
    b = c.create_budget(user_id="u", total_limit=10.0, currency="USD")
    assert c.authorize(budget=b, amount=3.0).decision == "AUTHORIZED"
    assert os.path.exists(nested)


# --- G. capability boundary (server-only runtime ops refuse) -----------------

def test_capability_boundary_runtime_ops():
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD")
    ev = c.authorize(budget=b, amount=5.0)
    for call in (lambda: c.void_tree(ev.event_id, "x"),
                 lambda: c.get_ledger(b.id),
                 lambda: c.get_receipt_url(b.id),
                 lambda: c.record_external_event(b.id, "a", "SPEND", "x", 1.0, "k1")):
        with pytest.raises(FiGuardError):
            call()


def test_ambient_parent_dropped_on_terminal():
    """authorize→fail→authorize (no clearing) must not raise INVALID_PARENT — the failed event
    is dropped as the ambient parent, so the next authorize is a root."""
    c = emb(); b = c.create_budget(user_id="u", total_limit=100.0, currency="USD")
    a = c.authorize(budget=b, amount=10.0)
    c.fail_event(event_id=a.event_id, reason="declined")
    r = c.authorize(budget=b, amount=5.0)
    assert r.decision == "AUTHORIZED"
    assert any(root.event.id == r.event_id for root in c.get_spend_tree(b.id).roots)


# --- direct-run dogfood report ----------------------------------------------

if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items())
             if n.startswith("test_") and callable(f) and not getattr(f, "pytestmark", None)]
    npass = nfail = 0
    print("=" * 64 + "\nEMBEDDED PRESSURE / STRESS — dogfood report\n" + "=" * 64)
    for name, fn in tests:
        try:
            fn(); npass += 1; print(f"  [PASS] {name}")
        except Exception as e:  # noqa: BLE001
            nfail += 1; print(f"  [FAIL] {name} — {e!r}")
    # parametrized velocity test run explicitly (skipped above)
    for kw, fire in [({"velocity_max_per_minute": 3}, 3), ({"velocity_max_per_day": 2}, 2)]:
        try:
            test_velocity_count_denials(kw, fire); npass += 1
            print(f"  [PASS] test_velocity_count_denials{tuple(kw)}")
        except Exception as e:  # noqa: BLE001
            nfail += 1; print(f"  [FAIL] test_velocity_count_denials — {e!r}")
    print("=" * 64 + f"\nSUMMARY: {npass} PASS, {nfail} FAIL\n" + "=" * 64)
    sys.exit(1 if nfail else 0)
