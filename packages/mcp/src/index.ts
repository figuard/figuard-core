#!/usr/bin/env node
/**
 * FiGuard MCP Server
 *
 * Exposes 13 FiGuard tools to any MCP client (Claude Code, Cursor, Claude Desktop).
 * Runs as a local stdio process — no cloud hosting required.
 *
 * Configuration (set in your MCP client config):
 *   FIGUARD_API_KEY   — your FiGuard API key (required)
 *   FIGUARD_BASE_URL  — FiGuard server URL (default: http://localhost:8080)
 *
 * Usage:
 *   npx figuard-mcp
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { FiGuardClient } from "figuard";
import { TOOLS } from "./tools.js";
import {
  handleCreateBudget,
  handleAuthorize,
  handleConfirm,
  handleFail,
  handleVoid,
  handleGetBudget,
  handleGetLedger,
  handleResumeBudget,
  handleExtendBudget,
  handleCancelBatch,
  handleFundBudget,
  handleCreateDelegationToken,
  handleRevokeDelegationToken,
  handleGetDelegationToken,
} from "./handlers.js";

// ---------------------------------------------------------------------------
// Validate environment
// ---------------------------------------------------------------------------

const API_KEY = process.env["FIGUARD_API_KEY"];
const BASE_URL = process.env["FIGUARD_BASE_URL"] ?? "http://localhost:8080";

if (!API_KEY) {
  console.error(
    "[figuard-mcp] Error: FIGUARD_API_KEY environment variable is not set.\n" +
    "Add it to your MCP client configuration:\n\n" +
    '  "env": { "FIGUARD_API_KEY": "fg_live_..." }\n',
  );
  process.exit(1);
}

// ---------------------------------------------------------------------------
// FiGuard client (single instance, shared across all tool calls)
// ---------------------------------------------------------------------------

const client = new FiGuardClient({ apiKey: API_KEY, baseUrl: BASE_URL });

// ---------------------------------------------------------------------------
// MCP server
// ---------------------------------------------------------------------------

const server = new Server(
  { name: "figuard-mcp", version: "0.1.0" },
  { capabilities: { tools: {} } },
);

// List all available tools
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: TOOLS.map((t) => ({
    name: t.name,
    description: t.description,
    inputSchema: t.inputSchema,
  })),
}));

// Dispatch tool calls to handlers
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args = {} } = request.params;

  try {
    let result: unknown;

    switch (name) {
      case "figuard_create_budget":
        result = await handleCreateBudget(client, args as Record<string, unknown>);
        break;
      case "figuard_authorize":
        result = await handleAuthorize(client, args as Record<string, unknown>);
        break;
      case "figuard_confirm":
        result = await handleConfirm(client, args as Record<string, unknown>);
        break;
      case "figuard_fail":
        result = await handleFail(client, args as Record<string, unknown>);
        break;
      case "figuard_void":
        result = await handleVoid(client, args as Record<string, unknown>);
        break;
      case "figuard_get_budget":
        result = await handleGetBudget(client, args as Record<string, unknown>);
        break;
      case "figuard_get_ledger":
        result = await handleGetLedger(client, args as Record<string, unknown>);
        break;
      case "figuard_resume_budget":
        result = await handleResumeBudget(client, args as Record<string, unknown>);
        break;
      case "figuard_extend_budget":
        result = await handleExtendBudget(client, args as Record<string, unknown>);
        break;
      case "figuard_cancel_batch":
        result = await handleCancelBatch(client, args as Record<string, unknown>);
        break;
      case "figuard_fund_budget":
        result = await handleFundBudget(client, args as Record<string, unknown>);
        break;
      case "figuard_create_delegation_token":
        result = await handleCreateDelegationToken(client, args as Record<string, unknown>);
        break;
      case "figuard_revoke_delegation_token":
        result = await handleRevokeDelegationToken(client, args as Record<string, unknown>);
        break;
      case "figuard_get_delegation_token":
        result = await handleGetDelegationToken(client, args as Record<string, unknown>);
        break;
      default:
        return {
          content: [{ type: "text", text: `Unknown tool: ${name}` }],
          isError: true,
        };
    }

    return {
      content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
    };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return {
      content: [{ type: "text", text: `Error: ${message}` }],
      isError: true,
    };
  }
});

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // Note: do not log to stdout — it's used for MCP protocol messages.
  // Log to stderr only.
  console.error(`[figuard-mcp] Server running. Base URL: ${BASE_URL}`);
}

main().catch((err) => {
  console.error("[figuard-mcp] Fatal error:", err);
  process.exit(1);
});
