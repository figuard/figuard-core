import { useState } from "react";
import { useParams, useLocation } from "react-router-dom";
import { useMutation, useQueryClient, useQuery } from "@tanstack/react-query";
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
import { AddFundsModal } from "../components/AddFundsModal";
import { BUDGET_STATUS_BADGE } from "../lib/colors";
import { formatDateTime, formatAmount, shortId } from "../lib/format";
import { resumeBudget, patchBudget, listDelegationTokens } from "../api/budgets";
import type { DelegationTokenResponse } from "../lib/types";

// Build 24-hour hourly spend buckets from ledger events.
function buildSparkline(
  events: { createdAt: string; requestedQuantity: number; decision: string }[],
) {
  const now = Date.now();
  const HOUR_MS = 3_600_000;
  const buckets: { hour: string; spend: number }[] = Array.from(
    { length: 24 },
    (_, i) => {
      const d = new Date(now - (23 - i) * HOUR_MS);
      return {
        hour: d.toLocaleTimeString(undefined, { hour: "numeric", hour12: true }),
        spend: 0,
      };
    },
  );

  for (const ev of events) {
    if (ev.decision !== "CONFIRMED" && ev.decision !== "AUTHORIZED") continue;
    const age = now - new Date(ev.createdAt).getTime();
    if (age > 24 * HOUR_MS) continue;
    const bucketIdx = 23 - Math.floor(age / HOUR_MS);
    if (bucketIdx >= 0 && bucketIdx < 24) {
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

  // Hooks must come before any early returns
  const [addFundsOpen, setAddFundsOpen] = useState(false);
  const [velocityEditing, setVelocityEditing] = useState(false);
  const [velocityDraft, setVelocityDraft] = useState<{
    velocityMaxPerMinute: string;
    velocityMaxAmountPerHour: string;
    velocityMaxPerDay: string;
  }>({ velocityMaxPerMinute: "", velocityMaxAmountPerHour: "", velocityMaxPerDay: "" });
  const queryClient = useQueryClient();
  const resumeMutation = useMutation({
    mutationFn: () => resumeBudget(id!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["budget", id] });
    },
  });
  const velocityMutation = useMutation({
    mutationFn: (payload: {
      velocityMaxPerMinute?: number | null;
      velocityMaxAmountPerHour?: number | null;
      velocityMaxPerDay?: number | null;
    }) => patchBudget(id!, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["budget", id] });
      setVelocityEditing(false);
    },
  });

  const { data: delegationTokens, isLoading: tokensLoading } = useQuery({
    queryKey: ["delegation-tokens", id],
    queryFn: () => listDelegationTokens(id!),
    enabled: !!id,
    staleTime: 30_000,
  });

  const location = useLocation();
  const createdState = location.state as {
    justCreated?: boolean;
    templateId?: string;
    templateName?: string;
    nextStep?: string;
    sessionToken?: string;
    workerSessionToken?: string;
    workerTokenId?: string;
  } | null;
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const showBanner = !!createdState?.justCreated && !bannerDismissed;

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
      {/* Just-created onboarding banner */}
      {showBanner && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 shadow-sm">
          <div className="flex items-start justify-between gap-3">
            <div className="flex-1 space-y-3">
              <div>
                <p className="text-sm font-semibold text-emerald-900">
                  Budget created
                  {createdState?.templateName ? ` · ${createdState.templateName}` : ""}
                </p>
                {createdState?.nextStep && (
                  <p className="text-xs text-emerald-700 mt-1 leading-snug">
                    {createdState.nextStep}
                  </p>
                )}
              </div>

              {/* Fleet: show two tokens with clear labels */}
              {createdState?.templateId === "agent-fleet" ? (
                <div className="space-y-2.5">
                  {createdState.sessionToken && (
                    <CreatedTokenRow
                      label="Fleet token — orchestrator only"
                      sublabel="Use this to create more delegation tokens via the API. Do NOT give it to worker agents."
                      token={createdState.sessionToken}
                      variant="muted"
                    />
                  )}
                  {createdState.workerSessionToken ? (
                    <CreatedTokenRow
                      label="Worker token (example) — give this to a worker agent"
                      sublabel="This is what your worker agents should receive. Create one per worker via POST /budgets/{id}/delegation-tokens."
                      token={createdState.workerSessionToken}
                      variant="highlight"
                    />
                  ) : (
                    <div className="rounded-lg border border-emerald-200 bg-white px-3 py-2.5 text-xs text-emerald-700">
                      No per-worker cap was set, so no example delegation token was created.{" "}
                      Create them via{" "}
                      <code className="font-mono bg-emerald-50 px-1 rounded">
                        POST /budgets/{"{id}"}/delegation-tokens
                      </code>
                    </div>
                  )}
                </div>
              ) : createdState?.sessionToken ? (
                <CreatedTokenRow
                  label="Session token — visible once, copy it now"
                  token={createdState.sessionToken}
                  variant="highlight"
                />
              ) : null}
            </div>
            <button
              onClick={() => setBannerDismissed(true)}
              className="shrink-0 text-emerald-400 hover:text-emerald-600 transition-colors text-lg leading-none mt-0.5"
              title="Dismiss"
            >
              ✕
            </button>
          </div>
        </div>
      )}

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
          {delegationTokens && delegationTokens.length > 0 && (
            <span className="inline-flex items-center rounded bg-indigo-50 px-2.5 py-0.5 text-xs font-medium text-indigo-700 border border-indigo-100">
              Fleet budget · {delegationTokens.filter(t => t.status !== "REVOKED").length} tokens
            </span>
          )}
          <ExpiryBadge expiresAt={budget.expiresAt} createdAt={budget.createdAt} budgetStatus={budget.status} />
          <button
            onClick={() => setAddFundsOpen(true)}
            className="rounded-lg border border-blue-200 bg-blue-50 px-3 py-1 text-xs font-medium text-blue-700 hover:bg-blue-100 transition-colors"
          >
            Add Funds
          </button>
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

      {/* PAUSED callout with resume action */}
      {budget.status === "PAUSED" && (
        <div className="rounded-xl border border-yellow-200 bg-yellow-50 p-4 flex items-start justify-between gap-4">
          <div className="space-y-1">
            <p className="text-sm font-semibold text-yellow-800">Budget paused — spend requests are blocked</p>
            <p className="text-xs text-yellow-700">
              This budget was automatically paused, likely by anomaly detection. Review the{" "}
              <a href={`/budgets/${id}/ledger`} className="underline font-medium hover:text-yellow-900">Ledger</a>{" "}
              for the triggering event, then resume when you're confident the activity is legitimate.
            </p>
            {resumeMutation.isError && (
              <p className="text-xs text-red-600 mt-1">Failed to resume. Try again.</p>
            )}
          </div>
          <button
            onClick={() => resumeMutation.mutate()}
            disabled={resumeMutation.isPending}
            className="shrink-0 rounded-lg bg-yellow-600 px-4 py-2 text-sm font-medium text-white hover:bg-yellow-700 disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-yellow-500 focus:ring-offset-2 transition-colors"
          >
            {resumeMutation.isPending ? "Resuming…" : "Resume budget"}
          </button>
        </div>
      )}

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
          Spend — last 24 hours
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
                dataKey="hour"
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
            No spend activity in the last 24 hours
          </div>
        )}
      </div>

      {/* Intent — structured scope enforcement */}
      {((budget.intentTags && budget.intentTags.length > 0) ||
        budget.intentContext) && (
        <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
          <div className="flex items-start justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-700">Intent</h2>
            {budget.intentTags && budget.intentTags.length > 0 && !budget.allocations?.length && (
              <span className="text-xs rounded-full bg-violet-50 text-violet-700 px-2.5 py-0.5 font-medium border border-violet-100">
                Scope enforced
              </span>
            )}
          </div>
          {budget.intentContext && (
            <p className="text-sm text-gray-600 mb-3 leading-snug">
              {budget.intentContext}
            </p>
          )}
          {budget.intentTags && budget.intentTags.length > 0 && (
            <>
              <p className="text-xs text-gray-400 mb-1.5">
                {!budget.allocations?.length
                  ? "Declared scope — spend outside these tags is blocked:"
                  : "Declared scope tags:"}
              </p>
              <div className="flex flex-wrap gap-1.5">
                {budget.intentTags.map((tag) => (
                  <span
                    key={tag}
                    className="rounded bg-violet-50 px-2 py-0.5 text-xs font-medium text-violet-700 border border-violet-100"
                  >
                    {tag}
                  </span>
                ))}
              </div>
            </>
          )}
        </div>
      )}

      {/* Velocity limits */}
      <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
        <div className="flex items-start justify-between mb-3">
          <h2 className="text-sm font-semibold text-gray-700">Velocity limits</h2>
          {!velocityEditing && (
            <button
              onClick={() => {
                setVelocityDraft({
                  velocityMaxPerMinute: budget.velocityMaxPerMinute != null ? String(budget.velocityMaxPerMinute) : "",
                  velocityMaxAmountPerHour: budget.velocityMaxAmountPerHour != null ? String(budget.velocityMaxAmountPerHour) : "",
                  velocityMaxPerDay: budget.velocityMaxPerDay != null ? String(budget.velocityMaxPerDay) : "",
                });
                setVelocityEditing(true);
              }}
              className="text-xs font-medium text-blue-600 hover:text-blue-800 transition-colors"
            >
              Edit
            </button>
          )}
        </div>

        {velocityEditing ? (
          <div className="space-y-3">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div>
                <label className="block text-xs text-gray-500 mb-1">Max attempts / minute</label>
                <input
                  type="number"
                  min={0}
                  placeholder="No limit"
                  value={velocityDraft.velocityMaxPerMinute}
                  onChange={(e) => setVelocityDraft((d) => ({ ...d, velocityMaxPerMinute: e.target.value }))}
                  className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-400 focus:outline-none focus:ring-1 focus:ring-blue-300"
                />
              </div>
              <div>
                <label className="block text-xs text-gray-500 mb-1">Max amount / hour</label>
                <input
                  type="number"
                  min={0}
                  placeholder="No limit"
                  value={velocityDraft.velocityMaxAmountPerHour}
                  onChange={(e) => setVelocityDraft((d) => ({ ...d, velocityMaxAmountPerHour: e.target.value }))}
                  className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-400 focus:outline-none focus:ring-1 focus:ring-blue-300"
                />
              </div>
              <div>
                <label className="block text-xs text-gray-500 mb-1">Max attempts / day</label>
                <input
                  type="number"
                  min={0}
                  placeholder="No limit"
                  value={velocityDraft.velocityMaxPerDay}
                  onChange={(e) => setVelocityDraft((d) => ({ ...d, velocityMaxPerDay: e.target.value }))}
                  className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-400 focus:outline-none focus:ring-1 focus:ring-blue-300"
                />
              </div>
            </div>
            {velocityMutation.isError && (
              <p className="text-xs text-red-600">Failed to save. Try again.</p>
            )}
            <div className="flex gap-2">
              <button
                onClick={() => {
                  const parseField = (v: string): number | null =>
                    v.trim() === "" ? null : Number(v);
                  velocityMutation.mutate({
                    velocityMaxPerMinute: parseField(velocityDraft.velocityMaxPerMinute),
                    velocityMaxAmountPerHour: parseField(velocityDraft.velocityMaxAmountPerHour),
                    velocityMaxPerDay: parseField(velocityDraft.velocityMaxPerDay),
                  });
                }}
                disabled={velocityMutation.isPending}
                className="rounded-lg bg-blue-600 px-4 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {velocityMutation.isPending ? "Saving…" : "Save"}
              </button>
              <button
                onClick={() => setVelocityEditing(false)}
                disabled={velocityMutation.isPending}
                className="rounded-lg border border-gray-300 px-4 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50 disabled:opacity-50 transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        ) : budget.velocityMaxPerMinute == null &&
          budget.velocityMaxAmountPerHour == null &&
          budget.velocityMaxPerDay == null ? (
          <p className="text-sm text-gray-400">Not configured</p>
        ) : (
          <dl className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            {budget.velocityMaxPerMinute != null && (
              <div>
                <dt className="text-xs text-gray-400">Max attempts / min</dt>
                <dd className="text-sm font-medium text-gray-700">{budget.velocityMaxPerMinute}</dd>
              </div>
            )}
            {budget.velocityMaxAmountPerHour != null && (
              <div>
                <dt className="text-xs text-gray-400">Max amount / hour</dt>
                <dd className="text-sm font-medium text-gray-700">{fmt(budget.velocityMaxAmountPerHour)}</dd>
              </div>
            )}
            {budget.velocityMaxPerDay != null && (
              <div>
                <dt className="text-xs text-gray-400">Max attempts / day</dt>
                <dd className="text-sm font-medium text-gray-700">{budget.velocityMaxPerDay}</dd>
              </div>
            )}
          </dl>
        )}
      </div>

      {/* Delegation tokens — only shown when tokens exist */}
      {!tokensLoading && delegationTokens && delegationTokens.length > 0 && (
        <DelegationTokensSection
          tokens={delegationTokens}
          currency={budget.currency}
          unit={budget.unit}
        />
      )}

      {addFundsOpen && (
        <AddFundsModal budget={budget} onClose={() => setAddFundsOpen(false)} />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Delegation tokens section
// ---------------------------------------------------------------------------

function DelegationTokensSection({
  tokens,
  currency,
  unit,
}: {
  tokens: DelegationTokenResponse[];
  currency: string;
  unit: string | null | undefined;
}) {
  const activeTokens = tokens.filter((t) => t.status !== "REVOKED");
  const revokedTokens = tokens.filter((t) => t.status === "REVOKED");
  const fmt = (n: number) => formatAmount(n, currency, unit);

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-sm font-semibold text-gray-700">Delegation Tokens</h2>
        <span className="text-xs text-gray-400">
          {activeTokens.length} active{revokedTokens.length > 0 ? ` · ${revokedTokens.length} revoked` : ""}
        </span>
      </div>

      <div className="space-y-2">
        {activeTokens.map((token) => (
          <TokenRow key={token.id} token={token} fmt={fmt} />
        ))}
      </div>

      {revokedTokens.length > 0 && (
        <details className="mt-3">
          <summary className="text-xs text-gray-400 cursor-pointer hover:text-gray-600 select-none">
            {revokedTokens.length} revoked token{revokedTokens.length !== 1 ? "s" : ""}
          </summary>
          <div className="mt-2 space-y-2">
            {revokedTokens.map((token) => (
              <TokenRow key={token.id} token={token} fmt={fmt} />
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// CreatedTokenRow — shows a session token in the just-created banner
// ---------------------------------------------------------------------------

function CreatedTokenRow({
  label,
  sublabel,
  token,
  variant,
}: {
  label: string;
  sublabel?: string;
  token: string;
  variant: "highlight" | "muted";
}) {
  const [visible, setVisible] = useState(false);
  const [copied, setCopied] = useState(false);

  function copy() {
    navigator.clipboard.writeText(token).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

  const isMuted = variant === "muted";

  return (
    <div>
      <p className={`text-xs font-medium mb-1 ${isMuted ? "text-emerald-600" : "text-emerald-800"}`}>
        {label}
      </p>
      {sublabel && (
        <p className="text-xs text-emerald-600 mb-1.5 leading-snug">{sublabel}</p>
      )}
      <div className="flex items-center gap-2">
        <div
          className={`flex-1 rounded-lg border px-3 py-1.5 font-mono text-xs overflow-x-auto ${
            isMuted
              ? "border-emerald-100 bg-emerald-50/50 text-gray-500"
              : "border-emerald-200 bg-white text-gray-700"
          }`}
        >
          {visible ? token : token.substring(0, 8) + "••••••••••••••••••••••••"}
        </div>
        <button
          onClick={() => setVisible((v) => !v)}
          className="shrink-0 rounded-lg border border-emerald-200 bg-white px-2.5 py-1.5 text-xs text-emerald-700 hover:bg-emerald-100 transition-colors"
        >
          {visible ? "Hide" : "Show"}
        </button>
        <button
          onClick={copy}
          className={`shrink-0 rounded-lg px-2.5 py-1.5 text-xs font-medium text-white transition-colors ${
            isMuted
              ? "bg-emerald-400 hover:bg-emerald-500"
              : "bg-emerald-600 hover:bg-emerald-700"
          }`}
        >
          {copied ? "Copied!" : "Copy"}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------

function TokenRow({
  token,
  fmt,
}: {
  token: DelegationTokenResponse;
  fmt: (n: number) => string;
}) {
  const isRevoked = token.status === "REVOKED";

  return (
    <div
      className={`rounded-lg border border-gray-100 bg-gray-50 p-3 ${
        isRevoked ? "opacity-50" : ""
      }`}
    >
      <div className="flex items-center gap-3 flex-wrap">
        <span className="font-mono text-xs text-gray-400" title={token.id}>
          {shortId(token.id)}
        </span>

        {token.label && (
          <span className="text-xs font-medium text-gray-700">{token.label}</span>
        )}

        <span
          className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${
            isRevoked
              ? "bg-gray-100 text-gray-500"
              : "bg-green-50 text-green-700"
          }`}
        >
          {token.status}
        </span>

        <span className="text-xs text-gray-400 ml-auto">
          Created {formatDateTime(token.createdAt)}
        </span>
      </div>

      {token.caps && token.caps.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-2">
          {token.caps.map((cap) => {
            const capPct =
              cap.totalLimit > 0
                ? Math.min(100, (cap.quantitySpent / cap.totalLimit) * 100)
                : 0;
            return (
              <div
                key={cap.id}
                className="rounded border border-violet-100 bg-violet-50 px-2.5 py-1.5 min-w-[140px]"
              >
                <div className="flex items-center justify-between gap-2 mb-1">
                  <span className="text-xs font-medium text-violet-700">
                    {cap.category}
                  </span>
                  <span className="text-xs text-violet-500 tabular-nums">
                    {fmt(cap.quantitySpent)} / {fmt(cap.totalLimit)}
                  </span>
                </div>
                <div className="h-1 rounded-full bg-violet-100 overflow-hidden">
                  <div
                    className={`h-full rounded-full ${
                      capPct >= 95
                        ? "bg-red-500"
                        : capPct >= 75
                        ? "bg-amber-400"
                        : "bg-violet-500"
                    }`}
                    style={{ width: `${capPct}%` }}
                  />
                </div>
                <p className="mt-1 text-xs text-violet-400">
                  {fmt(cap.availableQuantity)} remaining
                </p>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
