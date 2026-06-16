"""CI gate: every conformance scenario must pass against BOTH the raw embedded engine and the
public FiGuardClient(mode='embedded') stack, and the capability boundary must hold.

The engine now lives in the SDK (figuard._embedded); this drives it directly and through the
client, so the whole embedded path — what users actually call — is conformance-gated.
"""

import os
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(__file__))                       # spentinel-core/lite
sys.path.insert(0, os.path.join(ROOT, "..", "sdk", "python"))          # the figuard SDK
sys.path.insert(0, os.path.join(ROOT, "conformance"))

from runner import ClientEmbeddedAdapter, PythonLiteAdapter, load_scenarios, run  # noqa: E402
from figuard import FiGuardCapabilityError  # noqa: E402
from figuard._embedded import LiteEngine  # noqa: E402

_SCENARIOS = load_scenarios(os.path.join(ROOT, "conformance", "scenarios"))
# The client/create API only makes ACTIVE budgets, so PAUSED setup is engine-direct only.
_ACTIVE = [s for s in _SCENARIOS if s.get("budget", {}).get("status") in (None, "ACTIVE")]


@pytest.mark.parametrize("scenario", _SCENARIOS, ids=[s["id"] for s in _SCENARIOS])
def test_engine_parity(scenario):
    [(sid, failures)] = run(PythonLiteAdapter(), [scenario])
    assert not failures, "\n".join(failures)


@pytest.mark.parametrize("scenario", _ACTIVE, ids=[s["id"] for s in _ACTIVE])
def test_client_embedded_parity(scenario):
    [(sid, failures)] = run(ClientEmbeddedAdapter(), [scenario])
    assert not failures, "\n".join(failures)


def test_capability_boundary_refuses_fleet_ops():
    fg = LiteEngine()
    for call in (fg.create_delegation_token, fg.create_subscription, fg.register_webhook):
        with pytest.raises(FiGuardCapabilityError) as exc:
            call()
        assert "requires the FiGuard server" in str(exc.value)


def test_engine_backend_label():
    assert LiteEngine().backend == "embedded"


def _embedded():
    from figuard import FiGuardClient
    # Clear the ambient causal-chain ContextVar first: the SDK auto-parents each authorize() to
    # the previous event in the process, which would leak a stale parent from a prior test's
    # (now-gone :memory:) budget into the first no-parent authorize here → spurious INVALID_PARENT.
    # The conformance runner does the same per-scenario; in-process callers use figuard_scope().
    try:
        from figuard import clear_current_event_id
    except ImportError:
        from figuard.context import clear_current_event_id
    clear_current_event_id()
    return FiGuardClient(mode="embedded", database=":memory:", log=False)


def test_spend_tree_hierarchy():
    """Embedded /tree builds the same forest from parent links as the TS embedded engine
    (identical fixed sequence asserted in both languages — see conformance.test.ts)."""
    fg = _embedded()
    b = fg.create_budget(user_id="tree", total_limit=100.0, currency="USD")
    a = fg.authorize(budget=b, amount=20.0)
    fg.confirm(a, 20.0)
    child1 = fg.authorize(budget=b, amount=5.0, parent_event_id=a.event_id)
    child2 = fg.authorize(budget=b, amount=3.0, parent_event_id=a.event_id)
    fg.confirm(child1, 5.0)
    fg.confirm(child2, 3.0)
    denied = fg.authorize(budget=b, amount=999.0)
    assert denied.decision == "DENIED"

    tree = fg.get_spend_tree(b.id)
    assert tree.total_events == 4
    assert len(tree.roots) == 2  # A (chain root) + the denial
    root = next(r for r in tree.roots if r.event.id == a.event_id)
    assert root.event.decision == "CONFIRMED"
    assert len(root.children) == 2
    assert sorted(ch.event.id for ch in root.children) == sorted([child1.event_id, child2.event_id])
    assert all(ch.event.parent_event_id == a.event_id for ch in root.children)


def test_spend_tree_empty_budget():
    """A budget with no events yields an empty forest (no crash, totalEvents 0)."""
    fg = _embedded()
    b = fg.create_budget(user_id="tree", total_limit=10.0, currency="USD")
    tree = fg.get_spend_tree(b.id)
    assert tree.roots == []
    assert tree.total_events == 0


def test_spend_tree_deep_nesting():
    """Chains nest to arbitrary depth: root → child → grandchild (3 levels)."""
    fg = _embedded()
    b = fg.create_budget(user_id="tree", total_limit=100.0, currency="USD")
    a = fg.authorize(budget=b, amount=10.0)
    fg.confirm(a, 10.0)
    child = fg.authorize(budget=b, amount=5.0, parent_event_id=a.event_id)
    fg.confirm(child, 5.0)
    grand = fg.authorize(budget=b, amount=2.0, parent_event_id=child.event_id)
    fg.confirm(grand, 2.0)

    tree = fg.get_spend_tree(b.id)
    assert tree.total_events == 3
    assert len(tree.roots) == 1
    lvl1 = tree.roots[0]
    assert lvl1.event.id == a.event_id and len(lvl1.children) == 1
    lvl2 = lvl1.children[0]
    assert lvl2.event.id == child.event_id and len(lvl2.children) == 1
    assert lvl2.children[0].event.id == grand.event_id


def test_spend_tree_multiple_independent_roots():
    """Two unrelated chains surface as two separate roots. Note: Python auto-parents each
    authorize() to the previous event via the ambient ContextVar, so a genuinely independent
    second root requires clearing it between operations (figuard_scope() does this per scope).
    The TS SDK has no such ambient, so the same sequence there is independent without clearing."""
    from figuard import clear_current_event_id
    fg = _embedded()
    b = fg.create_budget(user_id="tree", total_limit=100.0, currency="USD")
    r1 = fg.authorize(budget=b, amount=10.0); fg.confirm(r1, 10.0)
    clear_current_event_id()                       # start a fresh chain (independent root)
    r2 = fg.authorize(budget=b, amount=20.0); fg.confirm(r2, 20.0)
    fg.authorize(budget=b, amount=1.0, parent_event_id=r2.event_id)

    tree = fg.get_spend_tree(b.id)
    assert tree.total_events == 3
    assert sorted(r.event.id for r in tree.roots) == sorted([r1.event_id, r2.event_id])


def test_spend_tree_node_labels():
    """The agent_id / action_type / description threaded through the engine surface on nodes
    (so the tree is human-readable, not just amounts) — the metadata-threading flow."""
    fg = _embedded()
    b = fg.create_budget(user_id="tree", total_limit=100.0, currency="USD")
    a = fg.authorize(budget=b, amount=30.0, agent_id="booker",
                     action_type="PURCHASE", description="Hotel booking")
    fg.confirm(a, 30.0)
    node = fg.get_spend_tree(b.id).roots[0]
    assert node.event.description == "Hotel booking"
    assert node.event.agent_id == "booker"
    assert node.event.action_type == "PURCHASE"


def test_spend_tree_not_found():
    """get_spend_tree on an unknown budget raises, not a silent empty tree."""
    fg = _embedded()
    with pytest.raises(Exception):
        fg.get_spend_tree("does-not-exist")


def test_engine_thread_safe_shared_client():
    """One FiGuardClient shared across threads must be safe (no sqlite ProgrammingError) and
    must never overspend. Regression for check_same_thread=False + the engine lock."""
    import threading
    from figuard import FiGuardClient
    fg = FiGuardClient(mode="embedded", database=":memory:", log=False)
    b = fg.create_budget(user_id="u", total_limit=10.0, currency="USD")
    res = {"AUTH": 0, "DENY": 0, "ERR": 0}
    lk = threading.Lock()
    def worker():
        try:
            r = fg.authorize(budget=b, amount=1.0)
            with lk: res["AUTH" if r.decision == "AUTHORIZED" else "DENY"] += 1
        except Exception:
            with lk: res["ERR"] += 1
    ts = [threading.Thread(target=worker) for _ in range(30)]
    for t in ts: t.start()
    for t in ts: t.join()
    assert res["ERR"] == 0, res                       # thread-safe (no sqlite errors)
    assert res["AUTH"] <= 10                           # capacity respected under concurrency
    assert fg.get_budget(b.id).quantity_reserved <= 10.0  # no overspend


def test_ambient_parent_dropped_on_terminal():
    """authorize→fail→authorize (without clearing) must NOT raise INVALID_PARENT: failing an
    event drops it as the ambient causal-chain parent, so the next authorize becomes a root."""
    from figuard import FiGuardClient, clear_current_event_id
    fg = FiGuardClient(mode="embedded", database=":memory:", log=False)
    clear_current_event_id()
    b = fg.create_budget(user_id="u", total_limit=100.0, currency="USD")
    a = fg.authorize(budget=b, amount=10.0)
    fg.fail_event(event_id=a.event_id, reason="declined")
    r = fg.authorize(budget=b, amount=5.0)            # ambient was 'a' (now FAILED)
    assert r.decision == "AUTHORIZED"
    tree = fg.get_spend_tree(b.id)
    assert any(root.event.id == r.event_id for root in tree.roots)  # became a root, not orphaned


def test_create_rejects_server_only_options():
    """Embedded must REFUSE create-time options it can't enforce (category allocations, shadow/
    trust modes, anomaly) rather than silently accepting them — otherwise a budget looks
    configured while enforcing nothing of the sort. Found by the pressure harness."""
    fg = _embedded()
    with pytest.raises(FiGuardCapabilityError):
        fg.create_budget(user_id="u", total_limit=100.0, currency="USD",
                         allocations=[{"category": "ads", "total_limit": 10}])
    with pytest.raises(FiGuardCapabilityError):
        fg.create_budget(user_id="u", total_limit=100.0, currency="USD", trust_mode="SHADOW")
    with pytest.raises(FiGuardCapabilityError):
        fg.create_budget(user_id="u", total_limit=100.0, currency="USD", anomaly_detection_enabled=True)
    # explicit STRICT (the embedded default behavior) and advisory soft_limit are allowed
    assert fg.create_budget(user_id="u", total_limit=100.0, currency="USD", trust_mode="STRICT")
    assert fg.create_budget(user_id="u", total_limit=100.0, currency="USD", soft_limit=50.0)


def test_get_budget_preserves_metadata():
    """GET /budgets/{id} must return the same unit/currency/limits as create — not defaults.
    Regression: the GET path used to rebuild from an empty create-body and lost unit/currency."""
    fg = _embedded()
    b = fg.create_budget(user_id="u", total_limit=5000, unit="tokens",
                         max_transaction_quantity=1000, velocity_max_per_minute=10)
    assert b.unit == "tokens" and b.currency is None
    got = fg.get_budget(b.id)                       # re-read
    assert got.unit == "tokens"
    assert got.currency is None
    assert got.max_transaction_quantity == 1000
    assert got.velocity_max_per_minute == 10
    assert got.total_limit == 5000


def test_persistence_survives_disk_reload(tmp_path):
    """Full disk round-trip (the 'multi-day budget, authorize days later' path): a budget
    created in one process must, from a fresh process on the same DB file, (1) stay
    authorizable [token map], (2) report its create-time metadata incl. user_id [GET fidelity],
    and (3) still expose its spend tree [events]. Regressions for both dogfood-found bugs."""
    from figuard import FiGuardClient, clear_current_event_id
    db = str(tmp_path / "persist.db")

    first = FiGuardClient(mode="embedded", database=db, log=False)
    budget = first.create_budget(user_id="alice", total_limit=100.0, unit="tokens",
                                 max_transaction_quantity=50)
    parent = first.authorize(budget=budget, amount=10.0, description="parent")
    first.confirm(parent, 10.0)
    first.authorize(budget=budget, amount=4.0, parent_event_id=parent.event_id, description="child")

    clear_current_event_id()
    fresh = FiGuardClient(mode="embedded", database=db, log=False)   # simulates a restart
    # (1) authorizable
    result = fresh.authorize(budget=budget, amount=10.0, description="day-2 spend")
    fresh.confirm(result, 10.0)
    assert result.decision == "AUTHORIZED"
    # (2) metadata survives the reload (not reset to defaults)
    got = fresh.get_budget(budget.id)
    assert got.user_id == "alice"
    assert got.unit == "tokens"
    assert got.max_transaction_quantity == 50
    assert got.quantity_spent == 20.0
    # (3) spend tree survives (the parent→child chain plus the day-2 events)
    tree = fresh.get_spend_tree(budget.id)
    assert tree.total_events == 3
    root = next(r for r in tree.roots if r.event.id == parent.event_id)
    assert root.event.description == "parent"
    assert len(root.children) == 1 and root.children[0].event.description == "child"
