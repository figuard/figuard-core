#!/usr/bin/env node
/**
 * FiGuard MCP Server
 *
 * Exposes 16 FiGuard tools to any MCP client (Claude Code, Cursor, Claude Desktop).
 * Runs as a local stdio process — no cloud hosting required.
 *
 * Configuration (all optional — embedded-by-default, no API key needed to start):
 *   (nothing)         — embedded: in-process enforcement against a local SQLite file
 *   FIGUARD_DATABASE  — embedded, at a specific SQLite path
 *   FIGUARD_API_KEY   — your FiGuard API key (selects a remote server)
 *   FIGUARD_BASE_URL  — FiGuard server URL
 *   FIGUARD_MODE      — "embedded" | "server" | "sandbox"
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
  handleGetSpendTree,
  handleUpdateBudget,
} from "./handlers.js";

// ---------------------------------------------------------------------------
// Configuration — embedded-by-default, zero infra.
//
//   (nothing set)               → embedded: in-process enforcement against a local SQLite file
//   FIGUARD_DATABASE=<path>     → embedded, at that path
//   FIGUARD_API_KEY/BASE_URL    → remote FiGuard server
//   FIGUARD_MODE=sandbox        → shared public demo
//
// No API key required to get started — `npx figuard-mcp` just works.
// ---------------------------------------------------------------------------

const API_KEY = process.env["FIGUARD_API_KEY"];
const BASE_URL = process.env["FIGUARD_BASE_URL"];
const DATABASE = process.env["FIGUARD_DATABASE"];
const MODE = process.env["FIGUARD_MODE"] as "embedded" | "server" | "sandbox" | undefined;

// ---------------------------------------------------------------------------
// FiGuard client (single instance, shared across all tool calls)
// ---------------------------------------------------------------------------

const client = new FiGuardClient({
  ...(API_KEY ? { apiKey: API_KEY } : {}),
  ...(BASE_URL ? { baseUrl: BASE_URL } : {}),
  ...(DATABASE ? { database: DATABASE } : {}),
  ...(MODE ? { mode: MODE } : {}),
});

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
      case "figuard_get_spend_tree":
        result = await handleGetSpendTree(client, args as Record<string, unknown>);
        break;
      case "figuard_update_budget":
        result = await handleUpdateBudget(client, args as Record<string, unknown>);
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
  console.error(`[figuard-mcp] Server running. Backend: ${client.backend}`);
}

main().catch((err) => {
  console.error("[figuard-mcp] Fatal error:", err);
  process.exit(1);
});
