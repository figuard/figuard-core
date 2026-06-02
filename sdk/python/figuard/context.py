"""
FiGuard ambient context propagation.

Stores the current FiGuard event ID in a Python ContextVar so nested
``authorize()`` calls automatically receive the correct ``parent_event_id``
without any parameter threading.

**How it works**

When ``authorize()`` succeeds it calls ``_set_current_event_id(event_id)``.
Any subsequent ``authorize()`` call in the same execution context — whether in
the same coroutine, a child coroutine, or a manually context-carried thread —
reads the ambient value and uses it as ``parent_event_id`` unless the caller
already passed one explicitly.

**Precedence (highest → lowest)**

1. Explicit ``parent_event_id`` keyword argument on the ``authorize()`` call
2. Framework callback inference (LangChain ``parent_run_id`` → event_id mapping)
3. Ambient ContextVar value set by the previous ``authorize()``

**Thread pool caveat**

Python's ``ContextVar`` propagates through ``async/await`` automatically but
NOT through ``ThreadPoolExecutor`` or ``loop.run_in_executor()``. Use
``figuard_run_in_executor()`` instead of calling the executor directly — it
snapshots the current context and restores it inside the worker thread.

    # Instead of:
    await loop.run_in_executor(None, my_fn, arg)

    # Use:
    from figuard.context import figuard_run_in_executor
    await figuard_run_in_executor(my_fn, arg)
"""

from __future__ import annotations

import asyncio
from contextvars import ContextVar, copy_context
from contextlib import contextmanager
from typing import Any, Callable, Generator, Optional

# ---------------------------------------------------------------------------
# Core ContextVar
# ---------------------------------------------------------------------------

_figuard_event_ctx: ContextVar[Optional[str]] = ContextVar(
    "figuard_event_id", default=None
)


def get_current_event_id() -> Optional[str]:
    """
    Return the ambient FiGuard event ID for the current execution context.

    Returns ``None`` if no ``authorize()`` has run in this context yet.
    """
    return _figuard_event_ctx.get()


def _set_current_event_id(event_id: Optional[str]) -> None:
    """
    Set the ambient event ID. Called automatically by ``authorize()`` after a
    successful authorization. Not normally called directly by application code.
    """
    _figuard_event_ctx.set(event_id)


def clear_current_event_id() -> None:
    """
    Clear the ambient FiGuard event ID for the current execution context.

    Call this at the start of an independent agent or crew member to prevent
    a stale event ID from a previous agent (or a previous notebook run) from
    being injected as ``parent_event_id`` into unrelated authorize() calls.

    Typical use in multi-agent notebooks and parallel crews::

        from figuard import clear_current_event_id

        # Each crew member runs in isolation — no shared event chain
        clear_current_event_id()
        result = client.authorize(session_token=agent_token, ...)
    """
    _figuard_event_ctx.set(None)


# ---------------------------------------------------------------------------
# Scope context manager
# ---------------------------------------------------------------------------

@contextmanager
def figuard_scope(event_id: str) -> Generator[None, None, None]:
    """
    Context manager that pins the ambient event ID for a block of code.

    Use when you want every ``authorize()`` inside the block to treat a specific
    event as the parent — without that event having been set by a prior ``authorize()``::

        with figuard_scope(root_event_id):
            # all authorize() calls here get parent_event_id=root_event_id
            sub_result = client.authorize(...)

    The original context is restored on exit.
    """
    token = _figuard_event_ctx.set(event_id)
    try:
        yield
    finally:
        _figuard_event_ctx.reset(token)


# ---------------------------------------------------------------------------
# Thread-safe executor wrapper
# ---------------------------------------------------------------------------

async def figuard_run_in_executor(
    fn: Callable[..., Any],
    *args: Any,
    executor: Any = None,
) -> Any:
    """
    Run a synchronous function in a thread pool, carrying the current FiGuard
    context into the worker thread.

    Python's ``ContextVar`` does NOT propagate into ``loop.run_in_executor()``
    by default — the worker thread starts with an empty context, silently losing
    the ambient ``parent_event_id``. This wrapper snapshots the current context
    with ``copy_context()`` and uses ``ctx.run()`` so the worker thread sees the
    same FiGuard event ID as the caller.

    LangGraph and LangChain both use thread pools for parallel node execution.
    Any graph that fans out to parallel branches MUST use this wrapper (or an
    equivalent ``ctx.run`` pattern) or the causal chain breaks silently in
    every parallel branch::

        # ❌ breaks causal chain in parallel branches:
        await loop.run_in_executor(None, process_refund, order_id)

        # ✅ carries parent_event_id into the thread:
        from figuard.context import figuard_run_in_executor
        await figuard_run_in_executor(process_refund, order_id)

    TypeScript equivalent: when using ``worker_threads``, serialize the event ID
    into the worker message and call ``figuard_scope()`` on the other side.
    """
    ctx = copy_context()
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(executor, ctx.run, fn, *args)
