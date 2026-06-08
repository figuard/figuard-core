"""
figuard-langchain — FiGuard pre-flight spend authorization for LangChain agents.

This package re-exports the LangChain integration from the main ``figuard`` package.
Install with ``pip install figuard-langchain`` for a LangChain-specific dependency,
or ``pip install figuard[langchain]`` if you already use the FiGuard SDK.

Quick start::

    from figuard_langchain import auto_guard_langchain

    executor = auto_guard_langchain(executor, budget=500)
    result = executor.invoke({"input": "Book a flight to NYC"})

With velocity control (catches runaway loops even when tools have no dollar amount)::

    executor = auto_guard_langchain(executor, budget=500, velocity_max_per_minute=10)
"""

from figuard.integrations.langchain import (
    FiGuardCallbackHandler,
    FiGuardToolGuard,
    auto_guard_langchain,
)

__all__ = [
    "FiGuardCallbackHandler",
    "FiGuardToolGuard",
    "auto_guard_langchain",
]
