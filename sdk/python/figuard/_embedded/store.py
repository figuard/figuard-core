"""SQLite persistence — the embedded ledger.

Schema is generated here (no Flyway in lite). Money is stored as TEXT and handled as
Decimal in the engine — never float — to match the Java BigDecimal(precision=19, scale=4)
semantics. Concurrency: a single embedded process serialized by SQLite's write lock; we
open each write transaction with BEGIN IMMEDIATE so the read-check-write critical section
is atomic, which is exactly the guarantee `SELECT ... FOR UPDATE` gives on the server (at
concurrency=1 it's equivalent — that's why the row-lock doesn't need porting).
"""

from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager

_SCHEMA = """
CREATE TABLE IF NOT EXISTS budgets (
    id                       TEXT PRIMARY KEY,
    user_id                  TEXT,
    total_limit              TEXT NOT NULL,
    unit                     TEXT,
    currency                 TEXT,
    max_transaction_quantity TEXT,
    intent_tags              TEXT,
    entity_dedup_enabled     INTEGER NOT NULL DEFAULT 0,
    velocity_max_per_minute      INTEGER,
    velocity_max_amount_per_hour TEXT,
    velocity_max_per_day         INTEGER,
    status                   TEXT NOT NULL DEFAULT 'ACTIVE',
    expires_at               TEXT,
    quantity_spent           TEXT NOT NULL DEFAULT '0',
    quantity_reserved        TEXT NOT NULL DEFAULT '0',
    created_at               TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS spend_events (
    id                      TEXT PRIMARY KEY,
    budget_id               TEXT NOT NULL,
    decision                TEXT NOT NULL,
    denial_reason           TEXT,
    requested_quantity      TEXT NOT NULL,
    confirmed_quantity      TEXT,
    currency                TEXT,
    claimed_category        TEXT,
    -- labels for the spend tree (set on approved events; see engine._approve)
    agent_id                TEXT,
    action_type             TEXT,
    description             TEXT,
    idempotency_key         TEXT,
    entity_id               TEXT,
    reserved                INTEGER NOT NULL DEFAULT 1,
    parent_event_id         TEXT,
    chain_root_event_id     TEXT,
    confirmation_timeout_at TEXT,
    created_at              TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_budget    ON spend_events(budget_id);
CREATE INDEX IF NOT EXISTS idx_events_idem       ON spend_events(budget_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_events_entity     ON spend_events(budget_id, entity_id);

-- Session token → budget map, PERSISTED so a restarted process (or a multi-day budget
-- authorized days later) can still resolve its token. Only the SHA-256 hash is stored,
-- never the raw token — mirroring the server, so a leaked DB file yields no live tokens.
CREATE TABLE IF NOT EXISTS session_tokens (
    token_hash  TEXT PRIMARY KEY,
    budget_id   TEXT NOT NULL
);
"""


def connect(path: str = ":memory:") -> sqlite3.Connection:
    # Create the parent dir for any file path so a custom database= ("~/work/budgets.db") just
    # works, like the default ~/.figuard path — sqlite won't create missing directories itself.
    if path != ":memory:":
        parent = os.path.dirname(os.path.expanduser(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
    # isolation_level=None → autocommit off only when we issue explicit BEGIN IMMEDIATE.
    # check_same_thread=False lets one client be shared across threads (many in-process agents);
    # the engine serializes all access with a lock, so concurrent use stays safe.
    conn = sqlite3.connect(os.path.expanduser(path) if path != ":memory:" else path,
                           isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(_SCHEMA)
    return conn


@contextmanager
def write_txn(conn: sqlite3.Connection):
    """Atomic critical section: BEGIN IMMEDIATE acquires the write lock up front, so the
    read-check-write inside cannot interleave with another writer (the embedded equivalent
    of the server's pessimistic row lock)."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise
