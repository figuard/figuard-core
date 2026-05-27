# figuard-mcp

FiGuard MCP server — use FiGuard directly from Claude Code, Cursor, or Claude Desktop without writing any SDK code.

## What this does

Adds 14 FiGuard tools to your AI assistant. Instead of writing SDK code, you just talk to Claude:

> "Create a $500 travel budget with $300 for flights and $200 for hotels"
> → Claude calls `figuard_create_budget`

> "Authorize a $149 economy flight from this budget"
> → Claude calls `figuard_authorize` → returns AUTHORIZED or DENIED

> "The flight ended up costing $147 — confirm the actual amount"
> → Claude calls `figuard_confirm`

## Requirements

- Node.js 18+
- A running FiGuard server (local or sandbox)

## Setup

### Claude Code

```bash
claude mcp add figuard-mcp \
  --env FIGUARD_API_KEY=fg_live_... \
  --env FIGUARD_BASE_URL=http://localhost:8080
```

Or add to `~/.claude/claude_desktop_config.json` manually:

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "fg_live_...",
        "FIGUARD_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

### Cursor

Add to `.cursor/mcp.json` in your project (or `~/.cursor/mcp.json` globally):

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "fg_live_...",
        "FIGUARD_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "fg_live_...",
        "FIGUARD_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

## Using the sandbox (no Docker required)

Point `FIGUARD_BASE_URL` at the hosted sandbox to try FiGuard without running a local server:

```json
{
  "FIGUARD_API_KEY": "sb_live_demo",
  "FIGUARD_BASE_URL": "https://figuard-sandbox-g1ha.onrender.com"
}
```

## Available tools

| Tool | What it does |
|---|---|
| `figuard_create_budget` | Create a spending envelope; returns `session_token` |
| `figuard_authorize` | Pre-flight check — AUTHORIZED or DENIED with reason |
| `figuard_confirm` | Close a reservation with the actual amount after success |
| `figuard_fail` | Release a reservation when the action fails |
| `figuard_void` | Cancel an unused reservation |
| `figuard_get_budget` | Check budget status, spent, reserved, available |
| `figuard_get_ledger` | List all spend events with filtering |
| `figuard_resume_budget` | Unfreeze a budget paused by anomaly detection |
| `figuard_extend_budget` | Extend a budget's expiry time |
| `figuard_cancel_batch` | Cancel up to 100 budgets in one call |
| `figuard_fund_budget` | Adjust a budget's total limit (CREDIT / DEBIT / RESET / RESET_SPENT) |
| `figuard_create_delegation_token` | Create a scoped per-agent token against a fleet budget |
| `figuard_revoke_delegation_token` | Revoke a delegation token (idempotent) |
| `figuard_get_delegation_token` | Check delegation token state and cap usage |

## Security note

`FIGUARD_API_KEY` is configured as an environment variable, not a tool argument. Tool arguments appear in LLM context windows — API keys must never be passed as tool parameters.

Session tokens returned by `figuard_create_budget` are short-lived (they expire with the budget) and are safe to use as tool arguments within a session.

## Running FiGuard locally

```bash
git clone https://github.com/figuard/figuard-core
cd figuard-core
docker compose up
```

FiGuard will be available at `http://localhost:8080`.
