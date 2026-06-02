import { useState } from "react";
import { useParams } from "react-router-dom";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ReferenceLine,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import { useBudget } from "../hooks/useBudget";
import { useTimeline, useFullReplay, useCounterfactual } from "../hooks/useReplay";
import { DECISION_BADGE } from "../lib/colors";
import { formatAmount, formatDateTime, shortId } from "../lib/format";
import type { SpendDecision, TimelineEventItem, CounterfactualDelta } from "../lib/types";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

type Tab = "timeline" | "balance" | "counterfactual";

function DecisionBadge({ decision }: { decision: SpendDecision | string }) {
  const cls = DECISION_BADGE[decision as SpendDecision] ?? "bg-gray-100 text-gray-600 border border-gray-200";
  return (
    <span className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${cls}`}>
      {decision}
    </span>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={`px-4 py-2 text-sm font-medium rounded-t border-b-2 transition-colors ${
        active
          ? "border-blue-500 text-blue-700 bg-blue-50"
          : "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300"
      }`}
    >
      {children}
    </button>
  );
}

function SummaryPill({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${color}`}>
      <span className="tabular-nums">{value}</span>
      <span>{label}</span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Timeline tab
// ---------------------------------------------------------------------------

function TimelineTab({ budgetId, fmt }: { budgetId: string; fmt: (n: number) => string }) {
  const { data, isLoading, isError, error } = useTimeline(budgetId);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-40 text-gray-400 text-sm">
        Loading timeline…
      </div>
    );
  }
  if (isError || !data) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        Failed to load timeline.{" "}
        {error instanceof Error ? error.message : "Unknown error."}
      </div>
    );
  }
  if (data.timeline.length === 0) {
    return (
      <div className="flex items-center justify-center h-40 text-gray-400 text-sm">
        No events recorded for this budget.
      </div>
    );
  }

  // Max gap across all events — bars are proportional to this, no artificial cap
  const maxMs = Math.max(...data.timeline.map((e) => e.millisSincePrevious), 1);

  // Summary counts
  const counts = data.timeline.reduce(
    (acc, e) => {
      acc[e.decision] = (acc[e.decision] ?? 0) + 1;
      return acc;
    },
    {} as Record<string, number>,
  );

  return (
    <div className="space-y-4">
      {/* Summary pills */}
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs text-gray-400">{data.totalEvents} events</span>
        {counts["AUTHORIZED"] != null && (
          <SummaryPill label="authorized" value={counts["AUTHORIZED"]} color="bg-blue-50 text-blue-700" />
        )}
        {counts["CONFIRMED"] != null && (
          <SummaryPill label="confirmed" value={counts["CONFIRMED"]} color="bg-green-50 text-green-700" />
        )}
        {counts["DENIED"] != null && (
          <SummaryPill label="denied" value={counts["DENIED"]} color="bg-red-50 text-red-700" />
        )}
        {counts["FAILED"] != null && (
          <SummaryPill label="failed" value={counts["FAILED"]} color="bg-orange-50 text-orange-700" />
        )}
        {counts["VOIDED"] != null && (
          <SummaryPill label="voided" value={counts["VOIDED"]} color="bg-gray-100 text-gray-600" />
        )}
      </div>

      {/* Table */}
      <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white shadow-sm">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-gray-100 bg-gray-50">
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide w-8">#</th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">
                Time
              </th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide w-40">
                Gap
              </th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                Decision
              </th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide text-right">
                Amount
              </th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                Category
              </th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                Agent
              </th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                Description
              </th>
              <th className="px-3 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">
                Event ID
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {data.timeline.map((ev: TimelineEventItem) => (
              <TimelineRow key={ev.eventId} ev={ev} maxMs={maxMs} fmt={fmt} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function TimelineRow({
  ev,
  maxMs,
  fmt,
}: {
  ev: TimelineEventItem;
  maxMs: number;
  fmt: (n: number) => string;
}) {
  const pct = Math.min((ev.millisSincePrevious / maxMs) * 100, 100);
  const ms = ev.millisSincePrevious;
  const gapLabel =
    ms === 0          ? "—"
    : ms < 1_000      ? `${ms}ms`
    : ms < 60_000     ? `${(ms / 1_000).toFixed(1)}s`
    : ms < 3_600_000  ? `${Math.round(ms / 60_000)}m`
    : ms < 86_400_000 ? `${(ms / 3_600_000).toFixed(1)}h`
    :                   `${(ms / 86_400_000).toFixed(1)}d`;

  return (
    <tr className="hover:bg-gray-50">
      <td className="px-3 py-2.5 text-xs text-gray-400 tabular-nums">{ev.eventIndex + 1}</td>
      <td className="px-3 py-2.5 text-xs text-gray-500 whitespace-nowrap font-mono">
        {formatDateTime(ev.createdAt)}
      </td>
      <td className="px-3 py-2.5">
        {ev.eventIndex > 0 && (
          <div className="flex items-center gap-1.5">
            <div className="w-24 h-1.5 rounded-full bg-gray-100 overflow-hidden">
              <div
                className="h-full rounded-full bg-blue-300"
                style={{ width: `${pct}%` }}
              />
            </div>
            <span className="text-xs text-gray-400 tabular-nums whitespace-nowrap">
              {gapLabel}
            </span>
          </div>
        )}
      </td>
      <td className="px-3 py-2.5">
        <DecisionBadge decision={ev.decision} />
      </td>
      <td className="px-3 py-2.5 text-sm text-right tabular-nums text-gray-900 whitespace-nowrap">
        {fmt(ev.requestedQuantity)}
      </td>
      <td className="px-3 py-2.5 text-sm text-gray-600 whitespace-nowrap">
        {ev.claimedCategory ?? <span className="text-gray-400">—</span>}
      </td>
      <td className="px-3 py-2.5 text-xs text-gray-600 font-mono whitespace-nowrap">
        {ev.agentId}
      </td>
      <td
        className="px-3 py-2.5 text-sm text-gray-500 max-w-xs truncate"
        title={ev.description}
      >
        {ev.description || <span className="text-gray-400">—</span>}
      </td>
      <td className="px-3 py-2.5 text-xs text-gray-400 font-mono whitespace-nowrap">
        {shortId(ev.eventId)}
      </td>
    </tr>
  );
}

// ---------------------------------------------------------------------------
// Balance tab
// ---------------------------------------------------------------------------

function BalanceTab({
  budgetId,
  fmt,
  totalLimit,
}: {
  budgetId: string;
  fmt: (n: number) => string;
  totalLimit: number;
}) {
  const { data, isLoading, isError, error } = useFullReplay(budgetId, true);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-40 text-gray-400 text-sm">
        Loading balance replay… (this may take a moment for large budgets)
      </div>
    );
  }
  if (isError || !data) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        Failed to load replay.{" "}
        {error instanceof Error ? error.message : "Unknown error."}
      </div>
    );
  }
  if (data.events.length === 0) {
    return (
      <div className="flex items-center justify-center h-40 text-gray-400 text-sm">
        No events recorded for this budget.
      </div>
    );
  }

  // Build chart data: one point per event where stateAfter exists
  const chartData = data.events
    .filter((f) => f.stateAfter != null)
    .map((f) => ({
      label: formatDateTime(f.stateAfter!.snapshotAt),
      available: Number(f.stateAfter!.available),
      reserved: Number(f.stateAfter!.quantityReserved),
      spent: Number(f.stateAfter!.quantitySpent),
      decision: f.event.decision,
    }));

  const deniedIndices = data.events
    .filter((f) => f.event.decision === "DENIED")
    .map((f) => f.eventIndex);

  const { summary } = data;

  return (
    <div className="space-y-6">
      {/* Summary stats */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <StatCard label="Total spent" value={fmt(Number(data.finalState.quantitySpent))} />
        <StatCard label="Still reserved" value={fmt(Number(data.finalState.quantityReserved))} />
        <StatCard label="Available now" value={fmt(Number(data.finalState.available))} />
        <StatCard
          label="Peak reserved"
          value={fmt(Number(summary.peakReservedQuantity))}
          sub={summary.peakReservedAt ? formatDateTime(summary.peakReservedAt) : undefined}
        />
      </div>

      {/* Available balance over time */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">Available balance over time</h3>
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={chartData} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
            <XAxis
              dataKey="label"
              tick={{ fontSize: 9, fill: "#9ca3af" }}
              tickLine={false}
              axisLine={false}
              interval="preserveStartEnd"
            />
            <YAxis
              tick={{ fontSize: 10, fill: "#9ca3af" }}
              tickLine={false}
              axisLine={false}
              width={55}
              tickFormatter={(v: number) => fmt(v)}
              domain={[0, totalLimit]}
            />
            <Tooltip
              formatter={(v: number, name: string) => [fmt(v), name]}
              contentStyle={{ fontSize: 12, borderRadius: 6, border: "1px solid #e5e7eb" }}
              labelStyle={{ fontSize: 11, color: "#6b7280" }}
            />
            {/* Mark the soft floor where budget becomes exhausted */}
            <ReferenceLine y={0} stroke="#ef4444" strokeDasharray="4 2" strokeWidth={1} />
            <Line
              type="stepAfter"
              dataKey="available"
              name="Available"
              stroke="#3b82f6"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 3 }}
            />
            <Line
              type="stepAfter"
              dataKey="reserved"
              name="Reserved"
              stroke="#f59e0b"
              strokeWidth={1.5}
              strokeDasharray="4 2"
              dot={false}
            />
          </LineChart>
        </ResponsiveContainer>
        <p className="mt-2 text-xs text-gray-400">
          <span className="inline-block w-3 h-0.5 bg-blue-500 mr-1 align-middle" /> Available &nbsp;
          <span className="inline-block w-3 h-0.5 bg-amber-400 mr-1 align-middle" style={{ borderTop: "2px dashed #f59e0b" }} /> Reserved
        </p>
      </div>

      {/* Denied events list */}
      {deniedIndices.length > 0 && (
        <div className="rounded-xl border border-red-100 bg-white p-5 shadow-sm">
          <h3 className="text-sm font-semibold text-gray-700 mb-3">
            Denied events ({deniedIndices.length})
          </h3>
          <div className="space-y-1.5">
            {data.events
              .filter((f) => f.event.decision === "DENIED")
              .map((f) => (
                <div
                  key={f.event.eventId}
                  className="flex items-center gap-3 rounded-lg bg-red-50 px-3 py-2 text-sm"
                >
                  <DecisionBadge decision="DENIED" />
                  <span className="text-gray-700 font-medium">{fmt(Number(f.event.requestedQuantity))}</span>
                  {f.event.claimedCategory && (
                    <span className="text-gray-500 text-xs">({f.event.claimedCategory})</span>
                  )}
                  <span className="text-gray-500 truncate max-w-xs">{f.event.description}</span>
                  <span className="ml-auto text-xs text-gray-400 whitespace-nowrap">
                    {f.event.denialReason ?? ""}
                  </span>
                </div>
              ))}
          </div>
        </div>
      )}
    </div>
  );
}

function StatCard({
  label,
  value,
  sub,
}: {
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm">
      <p className="text-xs text-gray-400 mb-1">{label}</p>
      <p className="text-lg font-semibold text-gray-900 tabular-nums">{value}</p>
      {sub && <p className="text-xs text-gray-400 mt-0.5">{sub}</p>}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Counterfactual tab
// ---------------------------------------------------------------------------

function CounterfactualTab({
  budgetId,
  budget,
  fmt,
}: {
  budgetId: string;
  budget: { totalLimit: number; maxTransactionQuantity: number | null; currency: string; unit: string | null };
  fmt: (n: number) => string;
}) {
  const [totalLimit, setTotalLimit] = useState<string>(String(budget.totalLimit));
  const [maxTx, setMaxTx] = useState<string>(
    budget.maxTransactionQuantity != null ? String(budget.maxTransactionQuantity) : "",
  );
  const mutation = useCounterfactual(budgetId);

  function handleRun() {
    const params: { totalLimit?: number; maxTransactionQuantity?: number } = {};
    const tl = parseFloat(totalLimit);
    if (!isNaN(tl) && tl > 0) params.totalLimit = tl;
    const mt = parseFloat(maxTx);
    if (!isNaN(mt) && mt > 0) params.maxTransactionQuantity = mt;
    mutation.mutate(params);
  }

  const result = mutation.data;
  const additionalDenials = result?.hypotheticalPolicySummary.additionalDenials ?? 0;

  return (
    <div className="space-y-6">
      {/* Policy form */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
        <h3 className="text-sm font-semibold text-gray-700 mb-1">Hypothetical policy</h3>
        <p className="text-xs text-gray-400 mb-4">
          Adjust limits to see which past transactions would have been denied. Runs against
          actual AUTHORIZED events — shows counterfactual denials only.
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">
              Total limit{" "}
              <span className="text-gray-400 font-normal">
                (current: {fmt(budget.totalLimit)})
              </span>
            </label>
            <input
              type="number"
              min="0"
              step="any"
              value={totalLimit}
              onChange={(e) => setTotalLimit(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              placeholder={String(budget.totalLimit)}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">
              Max transaction{" "}
              <span className="text-gray-400 font-normal">
                (current:{" "}
                {budget.maxTransactionQuantity != null
                  ? fmt(budget.maxTransactionQuantity)
                  : "none"}
                )
              </span>
            </label>
            <input
              type="number"
              min="0"
              step="any"
              value={maxTx}
              onChange={(e) => setMaxTx(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              placeholder="No limit"
            />
          </div>
        </div>
        <div className="mt-4 flex items-center gap-3">
          <button
            onClick={handleRun}
            disabled={mutation.isPending}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 transition-colors"
          >
            {mutation.isPending ? "Running…" : "Run what-if"}
          </button>
          {mutation.isError && (
            <span className="text-xs text-red-600">
              {mutation.error instanceof Error ? mutation.error.message : "Failed"}
            </span>
          )}
        </div>
      </div>

      {/* Results */}
      {result && (
        <div className="space-y-4">
          {/* Summary comparison */}
          <div className="grid grid-cols-2 gap-4">
            <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm">
              <p className="text-xs text-gray-400 mb-2">Actual policy</p>
              <p className="text-sm text-gray-700">
                <span className="font-semibold text-gray-900">
                  {result.actualPolicySummary.authorizedCount}
                </span>{" "}
                transactions evaluated
              </p>
              <p className="text-sm text-gray-700 mt-0.5">
                <span className="font-semibold text-gray-900">
                  {fmt(Number(result.actualPolicySummary.totalQuantitySpent))}
                </span>{" "}
                spent
              </p>
            </div>
            <div
              className={`rounded-xl border p-4 shadow-sm ${
                additionalDenials > 0
                  ? "border-red-200 bg-red-50"
                  : "border-green-200 bg-green-50"
              }`}
            >
              <p className="text-xs text-gray-400 mb-2">Hypothetical policy</p>
              <p className="text-sm text-gray-700">
                <span className="font-semibold text-gray-900">
                  {result.hypotheticalPolicySummary.authorizedCount}
                </span>{" "}
                would pass
              </p>
              {additionalDenials > 0 ? (
                <p className="text-sm font-semibold text-red-700 mt-0.5">
                  +{additionalDenials} additional denials
                </p>
              ) : (
                <p className="text-sm text-green-700 mt-0.5">No additional denials</p>
              )}
            </div>
          </div>

          {/* Delta events */}
          {result.deltaEvents.length > 0 ? (
            <div className="rounded-xl border border-gray-200 bg-white shadow-sm overflow-hidden">
              <div className="px-5 py-3 border-b border-gray-100 bg-gray-50">
                <h3 className="text-sm font-semibold text-gray-700">
                  Transactions that would have been denied ({result.deltaEvents.length})
                </h3>
              </div>
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-gray-100 bg-gray-50">
                    <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      Amount
                    </th>
                    <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      Category
                    </th>
                    <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      Denial reason
                    </th>
                    <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      Agent
                    </th>
                    <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      Description
                    </th>
                    <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                      Event ID
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {result.deltaEvents.map((delta: CounterfactualDelta) => (
                    <tr key={delta.eventId} className="hover:bg-red-50">
                      <td className="px-4 py-2.5 text-sm font-medium text-gray-900 tabular-nums whitespace-nowrap">
                        {fmt(Number(delta.requestedQuantity))}
                      </td>
                      <td className="px-4 py-2.5 text-sm text-gray-600">
                        {delta.claimedCategory ?? <span className="text-gray-400">—</span>}
                      </td>
                      <td className="px-4 py-2.5">
                        <span className="inline-flex items-center rounded px-2 py-0.5 text-xs font-medium bg-red-100 text-red-700 border border-red-200">
                          {delta.hypotheticalDenialReason ?? "DENIED"}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-xs text-gray-600 font-mono whitespace-nowrap">
                        {delta.agentId}
                      </td>
                      <td
                        className="px-4 py-2.5 text-sm text-gray-500 max-w-xs truncate"
                        title={delta.description}
                      >
                        {delta.description || <span className="text-gray-400">—</span>}
                      </td>
                      <td className="px-4 py-2.5 text-xs text-gray-400 font-mono whitespace-nowrap">
                        {shortId(delta.eventId)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="rounded-xl border border-green-200 bg-green-50 p-5 text-sm text-green-700">
              No transactions would have been denied under this policy.
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export function Replay() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("timeline");
  const { data: budget, isLoading: budgetLoading } = useBudget(id);

  if (budgetLoading || !budget) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-400 text-sm">
        Loading…
      </div>
    );
  }

  const fmt = (n: number) => formatAmount(n, budget.currency, budget.unit);

  return (
    <div className="space-y-4">
      {/* Page header */}
      <div>
        <h1 className="text-xl font-semibold text-gray-900">Replay</h1>
        <p className="mt-0.5 text-sm text-gray-400">
          Reconstruct budget state event-by-event from the append-only ledger.
        </p>
      </div>

      {/* Tabs */}
      <div className="border-b border-gray-200">
        <div className="flex gap-1">
          <TabButton active={tab === "timeline"} onClick={() => setTab("timeline")}>
            Timeline
          </TabButton>
          <TabButton active={tab === "balance"} onClick={() => setTab("balance")}>
            Balance Chart
          </TabButton>
          <TabButton active={tab === "counterfactual"} onClick={() => setTab("counterfactual")}>
            What-if
          </TabButton>
        </div>
      </div>

      {/* Tab content */}
      <div>
        {tab === "timeline" && <TimelineTab budgetId={id!} fmt={fmt} />}
        {tab === "balance"  && (
          <BalanceTab budgetId={id!} fmt={fmt} totalLimit={budget.totalLimit} />
        )}
        {tab === "counterfactual" && (
          <CounterfactualTab budgetId={id!} budget={budget} fmt={fmt} />
        )}
      </div>
    </div>
  );
}
