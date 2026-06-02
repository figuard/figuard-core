#!/usr/bin/env node
/**
 * E2E test: MCP server against live sandbox.
 *
 * Spawns the MCP server as a child process, sends JSON-RPC messages over
 * stdin, reads responses from stdout. Tests the full happy path:
 *   list_tools → create_budget → authorize → confirm → void (separate) → get_ledger
 */

import { spawn } from "child_process";
import { createInterface } from "readline";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const SANDBOX_URL = process.env.FIGUARD_BASE_URL ?? "https://figuard-sandbox-g1ha.onrender.com";
const API_KEY     = process.env.FIGUARD_API_KEY   ?? "sb_live_demo";

console.log(`\n▶  FiGuard MCP E2E test`);
console.log(`   Sandbox: ${SANDBOX_URL}`);
console.log(`   API key: ${API_KEY.slice(0, 12)}...\n`);

// ---------------------------------------------------------------------------
// Spawn MCP server
// ---------------------------------------------------------------------------

const server = spawn(
  "node",
  [path.join(__dirname, "dist/index.js")],
  {
    env: {
      ...process.env,
      FIGUARD_API_KEY: API_KEY,
      FIGUARD_BASE_URL: SANDBOX_URL,
    },
    stdio: ["pipe", "pipe", "pipe"],
  },
);

server.stderr.on("data", (d) => process.stderr.write(`[server] ${d}`));
server.on("exit", (code) => {
  if (code !== 0) console.error(`\n[server] exited with code ${code}`);
});

// ---------------------------------------------------------------------------
// JSON-RPC helpers
// ---------------------------------------------------------------------------

let msgId = 1;
const pending = new Map();

const rl = createInterface({ input: server.stdout });
rl.on("line", (line) => {
  if (!line.trim()) return;
  try {
    const msg = JSON.parse(line);
    const id = msg.id;
    if (id !== undefined && pending.has(id)) {
      const { resolve, reject } = pending.get(id);
      pending.delete(id);
      if (msg.error) reject(new Error(`RPC error: ${JSON.stringify(msg.error)}`));
      else resolve(msg.result);
    }
  } catch {
    // ignore non-JSON lines (e.g. MCP init messages)
  }
});

function rpc(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = msgId++;
    pending.set(id, { resolve, reject });
    const msg = JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n";
    server.stdin.write(msg);
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`Timeout waiting for response to: ${method}`));
      }
    }, 30_000);
  });
}

function callTool(name, args) {
  return rpc("tools/call", { name, arguments: args });
}

function parseToolResult(result, toolName) {
  const text = result?.content?.[0]?.text ?? "";
  if (result?.isError || text.startsWith("Error:")) {
    throw new Error(`Tool ${toolName} returned error: ${text}`);
  }
  return JSON.parse(text);
}

// ---------------------------------------------------------------------------
// Assertions
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function check(label, condition, detail = "") {
  if (condition) {
    console.log(`  ✓  ${label}`);
    passed++;
  } else {
    console.log(`  ✗  ${label}${detail ? " — " + detail : ""}`);
    failed++;
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

async function run() {
  // Initialize MCP session
  await rpc("initialize", {
    protocolVersion: "2024-11-05",
    capabilities: {},
    clientInfo: { name: "e2e-test", version: "1.0.0" },
  });

  // ── 1. List tools ──────────────────────────────────────────────────────────
  console.log("1. list_tools");
  const listResult = await rpc("tools/list");
  const tools = listResult.tools ?? [];
  check("returns 14 tools", tools.length === 14, `got ${tools.length}`);
  const toolNames = tools.map((t) => t.name);
  check("figuard_create_budget present",    toolNames.includes("figuard_create_budget"));
  check("figuard_authorize present",        toolNames.includes("figuard_authorize"));
  check("figuard_confirm present",          toolNames.includes("figuard_confirm"));
  check("figuard_get_delegation_token present", toolNames.includes("figuard_get_delegation_token"));

  // ── 2. Create budget ───────────────────────────────────────────────────────
  console.log("\n2. figuard_create_budget");
  const budgetResult = await callTool("figuard_create_budget", {
    user_id: "mcp-e2e-test",
    total_limit: 100,
    currency: "USD",
    expires_in: "1h",
    intent_context: "MCP E2E test budget",
  });
  const budget = parseToolResult(budgetResult, "figuard_create_budget");
  check("budget_id present",      typeof budget.budget_id === "string" && budget.budget_id.length > 0);
  check("session_token present",  typeof budget.session_token === "string" && budget.session_token.length > 0);
  check("status is ACTIVE",       budget.status === "ACTIVE");
  check("total_limit is 100",     budget.total_limit === 100);
  check("note about session_token", budget.note?.includes("never returned again"));

  const budgetId    = budget.budget_id;
  const sessionToken = budget.session_token;
  console.log(`   budget_id: ${budgetId}`);

  // ── 3. Authorize ───────────────────────────────────────────────────────────
  console.log("\n3. figuard_authorize (should be AUTHORIZED)");
  const authResult = await callTool("figuard_authorize", {
    session_token: sessionToken,
    agent_id: "mcp-e2e-agent",
    action_type: "PURCHASE",
    description: "E2E test spend",
    requested_quantity: 30.00,
    idempotency_key: `mcp-e2e-${Date.now()}`,
  });
  const auth = parseToolResult(authResult, "figuard_authorize");
  check("decision is AUTHORIZED",   auth.decision === "AUTHORIZED", JSON.stringify(auth));
  check("event_id present",         typeof auth.event_id === "string");
  check("approved_quantity is 30",  auth.approved_quantity === 30);
  check("next_step mentions confirm", auth.next_step?.includes("figuard_confirm"));

  const eventId = auth.event_id;
  console.log(`   event_id: ${eventId}`);

  // ── 4. Confirm ─────────────────────────────────────────────────────────────
  console.log("\n4. figuard_confirm");
  const confirmResult = await callTool("figuard_confirm", {
    event_id: eventId,
    confirmed_quantity: 28.50,
  });
  const confirmed = parseToolResult(confirmResult, "figuard_confirm");
  check("decision is CONFIRMED",       confirmed.decision === "CONFIRMED", JSON.stringify(confirmed));
  check("confirmed_quantity is 28.5",  confirmed.confirmed_quantity === 28.5);
  check("message present",             confirmed.message?.length > 0);

  // ── 5. Authorize + void ────────────────────────────────────────────────────
  console.log("\n5. figuard_authorize + figuard_void");
  const auth2Result = await callTool("figuard_authorize", {
    session_token: sessionToken,
    agent_id: "mcp-e2e-agent",
    action_type: "PURCHASE",
    description: "E2E void test",
    requested_quantity: 20.00,
    idempotency_key: `mcp-e2e-void-${Date.now()}`,
  });
  const auth2 = parseToolResult(auth2Result, "figuard_authorize (void test)");
  check("second authorize AUTHORIZED", auth2.decision === "AUTHORIZED", JSON.stringify(auth2));

  const voidResult = await callTool("figuard_void", {
    event_id: auth2.event_id,
    reason: "E2E_TEST_CLEANUP",
  });
  const voided = parseToolResult(voidResult, "figuard_void");
  check("decision is VOIDED",  voided.decision === "VOIDED", JSON.stringify(voided));
  check("is_voided is true",   voided.is_voided === true);

  // ── 6. Denial (exceed budget) ──────────────────────────────────────────────
  console.log("\n6. figuard_authorize (should be DENIED — exceeds limit)");
  const denyResult = await callTool("figuard_authorize", {
    session_token: sessionToken,
    agent_id: "mcp-e2e-agent",
    action_type: "PURCHASE",
    description: "E2E deny test — too large",
    requested_quantity: 999.00,
    idempotency_key: `mcp-e2e-deny-${Date.now()}`,
  });
  const denied = parseToolResult(denyResult, "figuard_authorize (deny test)");
  check("decision is DENIED",       denied.decision === "DENIED", JSON.stringify(denied));
  check("denial_reason present",    typeof denied.denial_reason === "string");
  check("retryable is false",       denied.retryable === false);
  check("next_step says not retry", denied.next_step?.includes("Do not retry"));

  // ── 7. Get budget ──────────────────────────────────────────────────────────
  console.log("\n7. figuard_get_budget");
  const getBudgetResult = await callTool("figuard_get_budget", { budget_id: budgetId });
  const budgetState = parseToolResult(getBudgetResult, "figuard_get_budget");
  check("budget_id matches",    budgetState.budget_id === budgetId);
  check("status is ACTIVE",     budgetState.status === "ACTIVE");
  check("spent is 28.5",        budgetState.spent === 28.5);
  check("available is 71.5",    budgetState.available === 71.5, `got ${budgetState.available}`);

  // ── 8. Get ledger ──────────────────────────────────────────────────────────
  console.log("\n8. figuard_get_ledger");
  const ledgerResult = await callTool("figuard_get_ledger", { budget_id: budgetId });
  const ledger = parseToolResult(ledgerResult, "figuard_get_ledger");
  check("events array present",    Array.isArray(ledger.events));
  check("has events",              ledger.events.length > 0, `got ${ledger.events.length}`);
  check("total_events ≥ 3",        ledger.total_events >= 3, `got ${ledger.total_events}`);
  const decisions = ledger.events.map((e) => e.decision);
  check("ledger has CONFIRMED",    decisions.includes("CONFIRMED"), JSON.stringify(decisions));
  check("ledger has VOIDED",       decisions.includes("VOIDED"),    JSON.stringify(decisions));
  check("ledger has DENIED",       decisions.includes("DENIED"),    JSON.stringify(decisions));

  // ── Summary ────────────────────────────────────────────────────────────────
  console.log(`\n${"─".repeat(50)}`);
  console.log(`Results: ✓ ${passed} passed   ✗ ${failed} failed`);
  console.log(`${"─".repeat(50)}\n`);

  server.stdin.end();
  process.exit(failed > 0 ? 1 : 0);
}

run().catch((err) => {
  console.error("\nFatal:", err.message);
  server.kill();
  process.exit(1);
});
