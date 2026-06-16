/**
 * Conformance gate (TypeScript): drives the SAME shared kernel.yaml scenarios through the
 * public FiGuardClient(mode="embedded") as the Python and Java implementations. This is the
 * cross-language consistency guarantee — TS embedded must produce identical decisions and
 * budget state to the server.
 *
 * Scenarios needing a non-ACTIVE initial budget (PAUSED) are skipped — the create API makes
 * ACTIVE budgets only (engine-direct + Java gates cover that path).
 */

import * as fs from "fs";
import * as path from "path";
import yaml from "js-yaml";
import { FiGuardClient } from "../src/client";

const SCENARIOS = path.join(
  __dirname, "..", "..", "..", "lite", "conformance", "scenarios", "kernel.yaml",
);

interface Scenario {
  id: string;
  budget: Record<string, any>;
  steps?: { op: string; request?: Record<string, any>; expect?: Record<string, any> }[];
  final_state?: Record<string, any>;
}

const all = (yaml.load(fs.readFileSync(SCENARIOS, "utf8")) as Scenario[]) || [];
const scenarios = all.filter((s) => !s.budget?.status || s.budget.status === "ACTIVE");

function equalish(expected: any, actual: any): boolean {
  if (typeof expected === "boolean" || typeof actual === "boolean") {
    return Boolean(expected) === Boolean(actual);
  }
  const ne = Number(expected);
  const na = Number(actual);
  if (!Number.isNaN(ne) && !Number.isNaN(na) && expected !== "" && actual !== "") return ne === na;
  return String(expected) === String(actual);
}

async function exec(fg: FiGuardClient, budget: any, op: string, req: Record<string, any>): Promise<Record<string, any>> {
  switch (op) {
    case "authorize": {
      const a = await fg.authorize({
        budget,
        amount: Number(req["amount"]),
        idempotencyKey: req["idempotency_key"],
        entityId: req["entity_id"],
        reserve: req["reserve"],
        currency: req["currency"],
        claimedCategory: req["claimed_category"],
        parentEventId: req["parent_event_id"],
        intentContext: req["intent_context"],
      });
      return { decision: a.decision, event_id: a.eventId,
               approved_quantity: a.approvedQuantity, denial_reason: a.denialReason };
    }
    case "confirm": {
      const e = await fg.confirmEvent({ eventId: req["event_ref"], confirmedQuantity: Number(req["confirmed_quantity"]) });
      return { decision: e.decision };
    }
    case "fail": {
      const e = await fg.failEvent({ eventId: req["event_ref"], reason: req["reason"] ?? "x" });
      return { decision: e.decision };
    }
    case "void": {
      const r = await fg.voidEvent({ eventId: req["event_ref"], reason: req["reason"] ?? "x" });
      return { decision: r.event.decision };
    }
    default:
      throw new Error(`unknown op: ${op}`);
  }
}

describe("kernel conformance — FiGuardClient(mode='embedded')", () => {
  for (const sc of scenarios) {
    test(sc.id, async () => {
      const fg = new FiGuardClient({ mode: "embedded", database: ":memory:", log: false });
      const b = sc.budget;
      const budget = await fg.createBudget({
        userId: "conformance",
        totalLimit: Number(b["total_limit"]),
        currency: b["currency"],
        unit: b["unit"],
        maxTransactionQuantity: b["max_transaction_quantity"] != null ? Number(b["max_transaction_quantity"]) : undefined,
        intentTags: b["intent_tags"],
        entityDedupEnabled: !!b["entity_dedup_enabled"],
        velocityMaxPerMinute: b["velocity_max_per_minute"],
        velocityMaxAmountPerHour: b["velocity_max_amount_per_hour"] != null ? Number(b["velocity_max_amount_per_hour"]) : undefined,
        velocityMaxPerDay: b["velocity_max_per_day"],
      });

      const results: Record<string, any>[] = [];
      for (let i = 0; i < (sc.steps || []).length; i++) {
        const step = sc.steps![i];
        const req: Record<string, any> = {};
        for (const [k, v] of Object.entries(step.request || {})) {
          const m = typeof v === "string" ? v.match(/^\$steps\[(\d+)\]\.(\w+)$/) : null;
          req[k] = m ? results[Number(m[1])][m[2]] : v;
        }

        if (step.expect && "error" in step.expect) {
          let threw = false;
          try {
            await exec(fg, budget, step.op, req);
          } catch (e: any) {
            threw = true;
            expect(String(e.message)).toContain(step.expect["error"]);
          }
          if (!threw) throw new Error(`${sc.id} step[${i}]: expected error ${step.expect["error"]}`);
          results.push({});
          continue;
        }

        const resp = await exec(fg, budget, step.op, req);
        results.push(resp);
        if (step.expect) {
          for (const [k, ev] of Object.entries(step.expect)) {
            expect(equalish(ev, resp[k])).toBe(true);
          }
        }
      }

      if (sc.final_state) {
        const snap = await fg.getBudget(budget.id);
        const state: Record<string, any> = {
          available: snap.availableQuantity,
          quantity_reserved: snap.quantityReserved,
          quantity_spent: snap.quantitySpent,
        };
        for (const [k, ev] of Object.entries(sc.final_state)) {
          expect(equalish(ev, state[k])).toBe(true);
        }
      }
    });
  }
});

// Spend-tree parity: the embedded /tree must build the same forest from parentEventId links
// as the Python embedded engine (identical fixed sequence asserted in both languages).
describe("spend-tree — FiGuardClient(mode='embedded')", () => {
  test("parent → children hierarchy, denials as roots", async () => {
    const fg = new FiGuardClient({ mode: "embedded", database: ":memory:", log: false });
    const budget = await fg.createBudget({ userId: "tree", totalLimit: 100, currency: "USD" });

    const a = await fg.authorize({ budget, amount: 20 });
    await fg.confirm(a, 20);
    const b = await fg.authorize({ budget, amount: 5, parentEventId: a.eventId });
    const c = await fg.authorize({ budget, amount: 3, parentEventId: a.eventId });
    await fg.confirm(b, 5);
    await fg.confirm(c, 3);
    const denied = await fg.authorize({ budget, amount: 999 });
    expect(denied.decision).toBe("DENIED");

    const tree = await fg.getSpendTree(budget.id);
    expect(tree.totalEvents).toBe(4);
    expect(tree.roots.length).toBe(2); // A (chain root) + the denial
    const root = tree.roots.find((r) => r.event.id === a.eventId)!;
    expect(root.event.decision).toBe("CONFIRMED");
    expect(root.children.length).toBe(2);
    const childIds = root.children.map((ch) => ch.event.id).sort();
    expect(childIds).toEqual([b.eventId, c.eventId].sort());
    for (const ch of root.children) expect(ch.event.parentEventId).toBe(a.eventId);
  });

  const emb = () => new FiGuardClient({ mode: "embedded", database: ":memory:", log: false });

  test("empty budget → empty forest", async () => {
    const fg = emb();
    const budget = await fg.createBudget({ userId: "tree", totalLimit: 10, currency: "USD" });
    const tree = await fg.getSpendTree(budget.id);
    expect(tree.roots).toEqual([]);
    expect(tree.totalEvents).toBe(0);
  });

  test("deep nesting: root → child → grandchild", async () => {
    const fg = emb();
    const budget = await fg.createBudget({ userId: "tree", totalLimit: 100, currency: "USD" });
    const a = await fg.authorize({ budget, amount: 10 }); await fg.confirm(a, 10);
    const child = await fg.authorize({ budget, amount: 5, parentEventId: a.eventId }); await fg.confirm(child, 5);
    const grand = await fg.authorize({ budget, amount: 2, parentEventId: child.eventId }); await fg.confirm(grand, 2);

    const tree = await fg.getSpendTree(budget.id);
    expect(tree.totalEvents).toBe(3);
    expect(tree.roots.length).toBe(1);
    const lvl1 = tree.roots[0];
    expect(lvl1.event.id).toBe(a.eventId);
    expect(lvl1.children.length).toBe(1);
    const lvl2 = lvl1.children[0];
    expect(lvl2.event.id).toBe(child.eventId);
    expect(lvl2.children[0].event.id).toBe(grand.eventId);
  });

  test("two independent chains → two roots", async () => {
    const fg = emb();
    const budget = await fg.createBudget({ userId: "tree", totalLimit: 100, currency: "USD" });
    const r1 = await fg.authorize({ budget, amount: 10 }); await fg.confirm(r1, 10);
    const r2 = await fg.authorize({ budget, amount: 20 }); await fg.confirm(r2, 20);
    await fg.authorize({ budget, amount: 1, parentEventId: r2.eventId });

    const tree = await fg.getSpendTree(budget.id);
    expect(tree.totalEvents).toBe(3);
    expect(tree.roots.map((r) => r.event.id).sort()).toEqual([r1.eventId, r2.eventId].sort());
  });

  test("node labels (agentId / actionType / description) surface", async () => {
    const fg = emb();
    const budget = await fg.createBudget({ userId: "tree", totalLimit: 100, currency: "USD" });
    const a = await fg.authorize({ budget, amount: 30, agentId: "booker", actionType: "PURCHASE", description: "Hotel booking" });
    await fg.confirm(a, 30);
    const node = (await fg.getSpendTree(budget.id)).roots[0];
    expect(node.event.description).toBe("Hotel booking");
    expect(node.event.agentId).toBe("booker");
    expect(node.event.actionType).toBe("PURCHASE");
  });

  test("unknown budget raises (not a silent empty tree)", async () => {
    const fg = emb();
    await expect(fg.getSpendTree("does-not-exist")).rejects.toThrow();
  });
});

// Persistence: a budget created in one client must stay authorizable from a fresh client on the
// same store (the 'multi-day budget, authorize days later' path). Regression for the token map
// that used to live only in memory — a restarted client could read but not authorize.
describe("embedded persistence — FiGuardClient(mode='embedded')", () => {
  test("create rejects server-only options (allocations/shadow/anomaly)", async () => {
    // Embedded must refuse create-time options it can't enforce, not silently accept them.
    const fg = new FiGuardClient({ mode: "embedded", database: ":memory:", log: false });
    await expect(fg.createBudget({ userId: "u", totalLimit: 100, currency: "USD",
      allocations: [{ category: "ads", limit: 10 }] })).rejects.toThrow(/server/i);
    await expect(fg.createBudget({ userId: "u", totalLimit: 100, currency: "USD",
      trustMode: "SHADOW" })).rejects.toThrow(/server/i);
    await expect(fg.createBudget({ userId: "u", totalLimit: 100, currency: "USD",
      anomalyDetectionEnabled: true })).rejects.toThrow(/server/i);
    // explicit STRICT + advisory softLimit are allowed
    expect(await fg.createBudget({ userId: "u", totalLimit: 100, currency: "USD", trustMode: "STRICT" })).toBeTruthy();
    expect(await fg.createBudget({ userId: "u", totalLimit: 100, currency: "USD", softLimit: 50 })).toBeTruthy();
  });

  test("get budget preserves unit/currency/limits (not defaults)", async () => {
    // Regression: the GET path used to rebuild from an empty create-body and lost unit/currency.
    const fg = new FiGuardClient({ mode: "embedded", database: ":memory:", log: false });
    const b = await fg.createBudget({ userId: "u", totalLimit: 5000, unit: "tokens", maxTransactionQuantity: 1000, velocityMaxPerMinute: 10 });
    expect(b.unit).toBe("tokens");
    const got = await fg.getBudget(b.id);
    expect(got.unit).toBe("tokens");
    expect(got.currency ?? null).toBeNull();
    expect(got.maxTransactionQuantity).toBe(1000);
    expect(got.velocityMaxPerMinute).toBe(10);
    expect(got.totalLimit).toBe(5000);
  });

  test("full disk round-trip: token + metadata + tree survive a reload", async () => {
    /* eslint-disable @typescript-eslint/no-var-requires */
    const os = require("os"), path = require("path"), fs = require("fs");
    const db = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "fg-")), "persist.json");

    const first = new FiGuardClient({ mode: "embedded", database: db, log: false });
    const budget = await first.createBudget({ userId: "alice", totalLimit: 100, unit: "tokens", maxTransactionQuantity: 50 });
    const parent = await first.authorize({ budget, amount: 10, description: "parent" });
    await first.confirm(parent, 10);
    await first.authorize({ budget, amount: 4, parentEventId: parent.eventId, description: "child" });

    const fresh = new FiGuardClient({ mode: "embedded", database: db, log: false }); // simulates a restart
    // (1) authorizable
    const result = await fresh.authorize({ budget, amount: 10 });
    await fresh.confirm(result, 10);
    expect(result.decision).toBe("AUTHORIZED");
    // (2) metadata survives the reload (not reset to defaults)
    const got = await fresh.getBudget(budget.id);
    expect(got.userId).toBe("alice");
    expect(got.unit).toBe("tokens");
    expect(got.maxTransactionQuantity).toBe(50);
    expect(got.quantitySpent).toBe(20);
    // (3) spend tree survives (the parent→child chain plus the day-2 events)
    const tree = await fresh.getSpendTree(budget.id);
    expect(tree.totalEvents).toBe(3);
    const root = tree.roots.find((r) => r.event.id === parent.eventId)!;
    expect(root.event.description).toBe("parent");
    expect(root.children.length).toBe(1);
    expect(root.children[0].event.description).toBe("child");
  });
});
