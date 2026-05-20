# MCP + FiGuard

FiGuard has an MCP server. Add it to Claude Code, Cursor, or Claude Desktop and your AI assistant can create budgets, authorize spend, and pull audit trails — without you writing any code.

---

## Step 1: Add to your MCP client (2 minutes)

Pick your client and add the config block. No install needed — `npx` pulls the server on first run.

**Claude Code** (`.claude/settings.json`):
```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "sb_live_demo",
        "FIGUARD_BASE_URL": "https://figuard-sandbox-1.onrender.com"
      }
    }
  }
}
```

**Cursor** (`.cursor/mcp.json`) and **Claude Desktop** (`claude_desktop_config.json`) use the same format.

Restart your client. You should see `figuard` listed under available MCP servers.

---

## Step 2: Try it immediately

Ask your assistant:

```
Create a $500 travel budget with $300 for flights and $200 for hotels,
expires in 24 hours. Then authorize a $267 flight.
```

**What happens:**

The assistant calls `figuard_create_budget` with the allocation config, receives a session token, then calls `figuard_authorize` with it. You see something like:

```
I created a travel budget:
  Total: $500 | Flights: $300 | Hotels: $200 | Expires: 24h

Authorization result: ✓ AUTHORIZED
  Event ID: evt_abc123
  Amount: $267.00 reserved from flights allocation
  Remaining flights budget: $33.00
```

---

## Step 3: See what happens when spend is denied

```
Now authorize a $150 flight purchase.
```

```
Authorization result: ✗ DENIED
  Reason: ALLOCATION_EXHAUSTED
  Flights allocation: $300 limit, $267 already spent, $33 remaining
  You requested: $150

Only $33 remains in the flights allocation. You could:
  • Authorize $33 (the remaining amount)
  • Move funds from hotels to flights using figuard_fund_budget
  • Approve an override with figuard_resume_budget if the budget is paused
```

The denial reason, remaining balance, and recovery options all come from FiGuard — the assistant didn't make those up.

---

## Step 4: Confirm or void a spend

After a real action completes, confirm the actual amount:

```
Confirm event evt_abc123 with actual amount $251 (flight was cheaper than quoted).
```

```
Event evt_abc123 confirmed.
  Authorized: $267.00 → Confirmed: $251.00
  $16.00 reservation released back to flights allocation
  Flights remaining: $49.00
```

Or void it if the action didn't happen:

```
Void event evt_abc123 — the booking failed.
```

```
Event evt_abc123 voided. $267.00 released back to flights allocation.
```

---

## Step 5: Audit trail

```
Show me the full ledger for the budget you just created.
```

```
Budget: bdg_xyz | $500 total | $251 spent | $249 remaining

Events:
  CONFIRMED  book_flight   $251.00   evt_abc123   2 min ago
  DENIED     book_flight   $150.00   evt_def456   1 min ago
```

---

## Available tools

| Tool | What it does |
|------|-------------|
| `figuard_create_budget` | Create a budget (with optional per-category allocations) |
| `figuard_authorize` | Pre-flight authorize a spend — returns AUTHORIZED or DENIED |
| `figuard_confirm` | Confirm the actual amount after the action succeeds |
| `figuard_fail` | Record a failed action, release reservation |
| `figuard_void` | Cancel a reservation before the action happens |
| `figuard_get_budget` | Current balance, spent, reserved |
| `figuard_get_ledger` | Full event history for a budget |
| `figuard_fund_budget` | Add/remove funds or reset a budget |
| `figuard_create_delegation_token` | Issue a scoped sub-budget token for a sub-agent |
| `figuard_revoke_delegation_token` | Revoke a delegation token immediately |
| `figuard_cancel_budget` | Cancel a budget |
| `figuard_list_budgets` | List all budgets for your account |
| `figuard_resume_budget` | Resume a paused budget with an override reason |

---

## Use your own instance

The sandbox key (`sb_live_demo`) is shared and resets periodically. For persistent data, [self-host FiGuard](../self-hosting.md) and point the MCP server at your instance:

```json
{
  "mcpServers": {
    "figuard": {
      "command": "npx",
      "args": ["figuard-mcp"],
      "env": {
        "FIGUARD_API_KEY": "your_api_key_here",
        "FIGUARD_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

Generate an API key at `POST /api/v1/api-keys` after starting your instance.

---

## Using FiGuard MCP in agent pipelines

You can use the MCP tools programmatically from any agent that supports MCP. The pattern is:

1. Agent calls `figuard_create_budget` at startup — stores the session token
2. Before each expensive action, calls `figuard_authorize` with the token and amount
3. If AUTHORIZED, performs the action, then calls `figuard_confirm` with the actual amount
4. If DENIED, reports back to the orchestrator with the reason

This gives you a complete spend ledger for every agent run, without modifying the agent's core logic.
