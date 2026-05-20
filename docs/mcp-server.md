# MCP Server

The FiGuard MCP server exposes FiGuard's budget operations as tools callable by any MCP-compatible client — Claude Code, Cursor, Claude Desktop, and others.

```bash
npx figuard-mcp
```

No `pip install` or SDK setup required. The assistant calls the tools directly.

---

## Configuration

Add to your MCP config file (`.claude/settings.json`, `.cursor/mcp.json`, or `claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "sb_live_demo",
        "FIGUARD_BASE_URL": "https://sandbox.figuard.io"
      }
    }
  }
}
```

For self-hosted:

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "fg_live_demo",
        "FIGUARD_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

---

## Available tools

| Tool | What it does |
|---|---|
| `figuard_create_budget` | Create a budget and get a session token |
| `figuard_authorize` | Pre-flight authorize a spend |
| `figuard_confirm` | Confirm the actual quantity consumed |
| `figuard_fail` | Record a failed transaction, release reservation |
| `figuard_void` | Cancel a reservation before execution |
| `figuard_get_budget` | Fetch budget state and remaining balance |
| `figuard_get_ledger` | List all authorization events for a budget |
| `figuard_resume_budget` | Resume a paused budget with an override reason |
| `figuard_create_delegation_token` | Issue a scoped sub-budget token for a sub-agent |
| `figuard_revoke_delegation_token` | Revoke a delegation token immediately |
| `figuard_cancel_budget` | Cancel a budget |
| `figuard_fund_budget` | Credit, debit, or reset a budget |
| `figuard_list_budgets` | List all budgets for the authenticated tenant |

---

## Example prompt

Once configured, ask your assistant:

```
Create a $500 travel budget with $300 for flights and $200 for hotels,
expires in 24 hours. Then authorize a $267 flight purchase.
```

The assistant calls `figuard_create_budget` with the allocation config, receives the session token, and calls `figuard_authorize` with it — no code written by you.

---

## Sandbox key

`sb_live_demo` is a shared demo key on the public sandbox instance. It's rate-limited and resets periodically. For anything beyond quick exploration, self-host and use your own key — see [Self-Hosting](self-hosting.md).
