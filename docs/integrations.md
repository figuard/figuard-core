# Framework Integrations

FiGuard works with every major Python agent framework. Pick yours:

| Framework | What you need | Guide |
|-----------|--------------|-------|
| **LangChain** | Callback handler — attach to `AgentExecutor`, no tool changes | [LangChain guide](integrations/langchain.md) |
| **OpenAI Agents SDK** | Decorator on tool functions — `@guarded_function_tool` | [OpenAI Agents guide](integrations/openai-agents.md) |
| **CrewAI** | `FiGuardCrewGuard.wrap(crew)` — one call covers all agents | [CrewAI guide](integrations/crewai.md) |
| **Claude / Cursor / Claude Code** | MCP server — no Python needed | [MCP guide](integrations/mcp.md) |

---

## Which pattern is right for me?

**Use the callback handler (LangChain)** if you want authorization to intercept tool calls at the executor level. Works with LangGraph too — pass the handler in the agent config.

**Use the decorator (OpenAI Agents)** if you want hard per-tool enforcement. The tool function never runs if denied — regardless of how the agent handles errors.

**Use `wrap()` (CrewAI)** if you have many tools across multiple agents and want a single setup call.

**Use MCP** if you're not writing Python. Claude Code, Cursor, and Claude Desktop can call FiGuard tools directly from conversation.

---

## They all share the same budget model

Regardless of framework, budget creation is the same:

```python
from figuard import FiGuardClient

client = FiGuardClient(
    api_key="sb_live_demo",
    base_url="https://figuard-sandbox-1.onrender.com",
)
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
