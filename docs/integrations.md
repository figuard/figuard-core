# Framework Integrations

FiGuard works with every major Python agent framework. Pick yours:

| Framework | What you need | Guide |
|-----------|--------------|-------|
| **LangChain** | `auto_guard_langchain()` one-liner, or full callback handler | [LangChain guide](integrations/langchain.md) |
| **LangGraph** | Same callback handler — passed via graph `config` | [LangGraph guide](integrations/langgraph.md) |
| **OpenAI Agents SDK** | Decorator on tool functions — `@guarded_function_tool` | [OpenAI Agents guide](integrations/openai-agents.md) |
| **CrewAI** | `auto_guard_crewai()` one-liner, or `FiGuardCrewGuard` | [CrewAI guide](integrations/crewai.md) |
| **Claude / Cursor / Claude Code** | MCP server — no Python needed | [MCP guide](integrations/mcp.md) |

---

## Zero-config one-liners (v1.1.0+)

The fastest path — one import, one call, done:

```python
# LangChain — also installable as: pip install figuard-langchain
from figuard import auto_guard_langchain

executor = auto_guard_langchain(executor, budget=500, velocity_max_per_minute=10)
# budget=500   → $500 ceiling for the session
# velocity_max_per_minute=10 → blocks the 11th tool call in 60 seconds
#                               catches runaway loops even when tools have no dollar amount

# CrewAI
from figuard import auto_guard_crewai

auto_guard_crewai(book_flight_tool, budget=500, velocity_max_per_minute=10)
```

Both wrappers use zero-config: `FiGuardClient()` runs locally (embedded SQLite) — no API key or server needed to try it. Set `FIGUARD_API_KEY` + `FIGUARD_BASE_URL` to enforce against a shared server.

For advanced patterns — per-category allocations, delegation tokens, fleet budgets — use the full client directly:

---

## Which pattern is right for me?

**Use the callback handler (LangChain / LangGraph)** if you want authorization to intercept tool calls at the executor or graph level. For LangGraph, pass the handler via `config={"callbacks": [handler]}` — see the [LangGraph guide](integrations/langgraph.md) for async graphs, parallel branches, and atomic cancellation.

**Use the decorator (OpenAI Agents)** if you want hard per-tool enforcement. The tool function never runs if denied — regardless of how the agent handles errors.

**Use `wrap()` (CrewAI)** if you have many tools across multiple agents and want a single setup call.

**Use MCP** if you're not writing Python. Claude Code, Cursor, and Claude Desktop can call FiGuard tools directly from conversation.

---

## They all share the same budget model

Regardless of framework, budget creation is the same:

```python
from figuard import FiGuardClient

client = FiGuardClient()  # zero-config: runs locally (embedded SQLite). Set FIGUARD_API_KEY + FIGUARD_BASE_URL for a server.
budget = client.create_budget(
    user_id="agent_001",
    total_limit=500.00,
    currency="USD",
    expires_in="24h",
)
session_token = budget.primary_token.session_token
```

The `session_token` is what you pass to any integration. Budget configuration — allocations, velocity limits, anomaly detection — is set once here and enforced everywhere.

---

## Not using a framework?

Use the raw client directly — see the [60-second quickstart](../README.md#60-second-quickstart) in the README.
