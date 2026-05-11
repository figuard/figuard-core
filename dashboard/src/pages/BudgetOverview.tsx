import { useParams } from "react-router-dom";
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { useBudget } from "../hooks/useBudget";
import { useLedger } from "../hooks/useLedger";
import { BudgetStatusBar } from "../components/BudgetStatusBar";
import { AllocationRings } from "../components/AllocationRings";
import { ExpiryBadge } from "../components/ExpiryBadge";
import { BUDGET_STATUS_BADGE } from "../lib/colors";
import { formatDateTime, formatAmount, shortId } from "../lib/format";

// Build 7-day daily spend buckets from ledger events.
function buildSparkline(
  events: { createdAt: string; requestedQuantity: number; decision: string }[],
) {
  const now = Date.now();
  const DAY_MS = 86_400_000;
  const buckets: { day: string; spend: number }[] = Array.from(
    { length: 7 },
    (_, i) => {
      const d = new Date(now - (6 - i) * DAY_MS);
      return {
        day: d.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" }),
        spend: 0,
      };
    },
  );

  for (const ev of events) {
    if (ev.decision !== "CONFIRMED" && ev.decision !== "AUTHORIZED") continue;
    const age = now - new Date(ev.createdAt).getTime();
    if (age > 7 * DAY_MS) continue;
    const bucketIdx = 6 - Math.floor(age / DAY_MS);
    if (bucketIdx >= 0 && bucketIdx < 7) {
      buckets[bucketIdx].spend += ev.requestedQuantity;
    }
  }
  return buckets;
}

export function BudgetOverview() {
  const { id } = useParams<{ id: string }>();
  const { data: budget, isLoading, isError, error } = useBudget(id);
  // Fetch up to 500 recent events for the sparkline (covers 7 days of typical traffic)
  const { data: ledgerPage } = useLedger(id, { page: 0, size: 500 });

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-400">
        Loading budget…
      </div>
    );
  }

  if (isError || !budget) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        Failed to load budget.{" "}
        {error instanceof Error ? error.message : "Unknown error."}
      </div>
    );
  }

  const sparkData = buildSparkline(ledgerPage?.content ?? []);
  const hasSpend = sparkData.some((b) => b.spend > 0);
  const fmt = (n: number) => formatAmount(n, budget.currency, budget.unit);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">Budget Overview</h1>
          <p className="mt-0.5 font-mono text-sm text-gray-400" title={budget.id}>
            {shortId(budget.id)}
          </p>
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          <span
            className={`inline-flex items-center rounded px-2.5 py-0.5 text-xs font-medium ${BUDGET_STATUS_BADGE[budget.status]}`}
          >
            {budget.status}
          </span>
          <span className="inline-flex items-center rounded bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-600">
            {budget.allocations && budget.allocations.length > 0
              ? budget.allocations[0].enforcementMode
              : "OPEN"}
          </span>
          <ExpiryBadge expiresAt={budget.expiresAt} createdAt={budget.createdAt} budgetStatus={budget.status} />
        </div>
      </div>

      {/* Status bar card */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
        <BudgetStatusBar budget={budget} />
        <p className="mt-2 text-xs text-gray-400">
          Total limit: {fmt(budget.totalLimit)}
          {budget.softLimit != null && ` · Soft limit: ${fmt(budget.softLimit)}`}
          {budget.maxTransactionQuantity != null &&
            ` · Max transaction: ${fmt(budget.maxTransactionQuantity)}`}
          {budget.currency && ` · ${budget.currency}`}
          {budget.unit && ` · ${budget.unit}`}
          {` · Created ${formatDateTime(budget.createdAt)}`}
        </p>
      </div>

      {/* Allocation rings */}
      {budget.allocations && budget.allocations.length > 0 && (
        <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
          <h2 className="mb-4 text-sm font-semibold text-gray-700">
            Allocations
          </h2>
          <AllocationRings budget={budget} />
        </div>
      )}

      {/* 7-day sparkline — hidden when empty */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
        <h2 className="mb-4 text-sm font-semibold text-gray-700">
          Spend — last 7 days
        </h2>
        {hasSpend ? (
          <ResponsiveContainer width="100%" height={140}>
            <AreaChart data={sparkData} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <defs>
                <linearGradient id="spendGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.2} />
                  <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                </linearGradient>
              </defs>
              <XAxis
                dataKey="day"
                tick={{ fontSize: 10, fill: "#9ca3af" }}
                tickLine={false}
                axisLine={false}
                interval={0}
              />
              <YAxis
                tick={{ fontSize: 10, fill: "#9ca3af" }}
                tickLine={false}
                axisLine={false}
                width={40}
                tickFormatter={(v: number) =>
                  budget.unit ? `${v}` : `$${v}`
                }
              />
              <Tooltip
                formatter={(v: number) => [fmt(v), "Spend"]}
                contentStyle={{
                  fontSize: 12,
                  borderRadius: 6,
                  border: "1px solid #e5e7eb",
                }}
              />
              <Area
                type="monotone"
                dataKey="spend"
                stroke="#3b82f6"
                strokeWidth={2}
                fill="url(#spendGrad)"
                dot={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        ) : (
          <div className="flex items-center justify-center h-[140px] text-sm text-gray-400">
            No spend activity in the last 7 days
          </div>
        )}
      </div>

      {/* Intent tags / metadata */}
      {((budget.intentTags && budget.intentTags.length > 0) ||
        budget.intentContext) && (
        <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
          <h2 className="mb-3 text-sm font-semibold text-gray-700">
            Intent
          </h2>
          {budget.intentContext && (
            <p className="text-sm text-gray-600 mb-2">
              Context: <span className="font-mono">{budget.intentContext}</span>
            </p>
          )}
          {budget.intentTags && budget.intentTags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {budget.intentTags.map((tag) => (
                <span
                  key={tag}
                  className="rounded bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700"
                >
                  {tag}
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
