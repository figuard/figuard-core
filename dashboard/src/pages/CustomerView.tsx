import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { listBudgets, listDelegationTokens } from "../api/budgets";
import { formatAmount, shortId, formatDateTime } from "../lib/format";
import { BUDGET_STATUS_BADGE } from "../lib/colors";
import { HealthBadge } from "../components/HealthBadge";
import type { BudgetResponse, DelegationTokenResponse } from "../lib/types";

// ---------------------------------------------------------------------------
// Delegation token row
// ---------------------------------------------------------------------------
function TokenRow({ token, currency, unit }: {
  token: DelegationTokenResponse;
  currency: string;
  unit?: string | null;
}) {
  const fmt = (n: number) => formatAmount(n, currency, unit);
  const isRevoked = token.status === "REVOKED";

  return (
    <div className={`ml-8 pl-4 border-l-2 border-gray-100 py-2 ${isRevoked ? "opacity-50" : ""}`}>
      <div className="flex items-center gap-3 flex-wrap">
        {/* Connector dot */}
        <div className="w-1.5 h-1.5 rounded-full bg-gray-300 -ml-[21px] shrink-0" />

        <span className="font-mono text-xs text-gray-400" title={token.id}>
          {shortId(token.id)}
        </span>

        {token.label && (
          <span className="text-xs text-gray-600 font-medium">{token.label}</span>
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

        <span className="text-xs text-gray-400">
          Created {formatDateTime(token.createdAt)}
        </span>
      </div>

      {/* Caps */}
      {token.caps && token.caps.length > 0 && (
        <div className="mt-1.5 ml-0 flex flex-wrap gap-2">
          {token.caps.map((cap) => (
            <div key={cap.id} className="text-xs bg-violet-50 border border-violet-100 rounded px-2 py-1">
              <span className="font-medium text-violet-700">{cap.category}</span>
              <span className="text-violet-500 mx-1">·</span>
              <span className="text-violet-600">
                {fmt(cap.quantitySpent)} / {fmt(cap.totalLimit)}
              </span>
              <span className="text-violet-400 ml-1">
                ({fmt(cap.availableQuantity)} left)
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Budget node (parent) with its delegation tokens
// ---------------------------------------------------------------------------
function BudgetNode({ budget }: { budget: BudgetResponse }) {
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(true);

  const { data: tokens, isLoading: tokensLoading } = useQuery({
    queryKey: ["delegation-tokens", budget.id],
    queryFn: () => listDelegationTokens(budget.id),
    staleTime: 30_000,
  });

  const fmt = (n: number) => formatAmount(n, budget.currency, budget.unit);
  const pct = budget.totalLimit > 0
    ? Math.min(100, (budget.quantitySpent / budget.totalLimit) * 100)
    : 0;

  const activeTokens = tokens?.filter((t) => t.status !== "REVOKED") ?? [];
  const revokedTokens = tokens?.filter((t) => t.status === "REVOKED") ?? [];

  return (
    <div className="rounded-xl border border-gray-200 bg-white shadow-sm overflow-hidden">
      {/* Budget header row */}
      <div className="p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-2 min-w-0">
            {/* Expand/collapse toggle */}
            <button
              onClick={() => setExpanded((v) => !v)}
              className="shrink-0 text-gray-400 hover:text-gray-600 transition-colors"
              title={expanded ? "Collapse" : "Expand"}
            >
              <svg
                className={`w-4 h-4 transition-transform ${expanded ? "rotate-90" : ""}`}
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <polyline points="6,4 10,8 6,12" />
              </svg>
            </button>

            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <button
                  onClick={() => navigate(`/budgets/${budget.id}`)}
                  className="font-mono text-xs text-blue-600 hover:underline"
                  title={budget.id}
                >
                  {shortId(budget.id)}
                </button>
                {budget.externalReference && (
                  <span className="text-xs bg-gray-100 text-gray-600 rounded px-1.5 py-0.5 font-mono">
                    {budget.externalReference}
                  </span>
                )}
              </div>
              <p className="text-xs text-gray-400 mt-0.5">
                Created {formatDateTime(budget.createdAt)}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 flex-wrap shrink-0">
            <span className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${BUDGET_STATUS_BADGE[budget.status]}`}>
              {budget.status}
            </span>
            <HealthBadge budget={budget} />
            {budget.unit ? (
              <span className="text-xs text-gray-500 font-medium tabular-nums">
                {budget.quantitySpent} / {budget.totalLimit} {budget.unit}
              </span>
            ) : (
              <span className="text-xs text-gray-500 font-medium tabular-nums">
                {fmt(budget.quantitySpent)} / {fmt(budget.totalLimit)}
              </span>
            )}
          </div>
        </div>

        {/* Progress bar */}
        <div className="mt-3 h-1.5 rounded-full bg-gray-100 overflow-hidden">
          <div
            className={`h-full rounded-full transition-all ${
              pct >= 95 ? "bg-red-500" : pct >= 75 ? "bg-amber-400" : "bg-blue-500"
            }`}
            style={{ width: `${pct}%` }}
          />
        </div>
        <div className="flex justify-between mt-1 text-xs text-gray-400 tabular-nums">
          <span>{fmt(budget.availableQuantity)} available</span>
          <span>{Math.round(pct)}% used</span>
        </div>
      </div>

      {/* Delegation tokens */}
      {expanded && (
        <div className="border-t border-gray-100 bg-gray-50 px-4 py-3 space-y-1">
          {tokensLoading && (
            <p className="text-xs text-gray-400 py-1">Loading tokens…</p>
          )}

          {!tokensLoading && tokens && tokens.length === 0 && (
            <p className="text-xs text-gray-400 italic py-1">No delegation tokens</p>
          )}

          {activeTokens.map((token) => (
            <TokenRow
              key={token.id}
              token={token}
              currency={budget.currency}
              unit={budget.unit}
            />
          ))}

          {revokedTokens.length > 0 && (
            <details className="mt-1">
              <summary className="text-xs text-gray-400 cursor-pointer hover:text-gray-600 select-none">
                {revokedTokens.length} revoked token{revokedTokens.length !== 1 ? "s" : ""}
              </summary>
              <div className="mt-1 space-y-1">
                {revokedTokens.map((token) => (
                  <TokenRow
                    key={token.id}
                    token={token}
                    currency={budget.currency}
                    unit={budget.unit}
                  />
                ))}
              </div>
            </details>
          )}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------
export function CustomerView() {
  const [inputValue, setInputValue] = useState("");
  const [userId, setUserId] = useState("");

  const { data, isLoading, isError, isFetching } = useQuery({
    queryKey: ["customer-budgets", userId],
    queryFn: () => listBudgets({ userId, size: 200, page: 0 }),
    enabled: userId.trim().length > 0,
    staleTime: 30_000,
  });

  const budgets = data?.content ?? [];

  const totalSpent = budgets.reduce((s, b) => s + b.quantitySpent, 0);
  const totalLimit = budgets.reduce((s, b) => s + b.totalLimit, 0);
  const activeBudgets = budgets.filter((b) => b.status === "ACTIVE").length;

  function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    setUserId(inputValue.trim());
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-gray-900">Customer View</h1>
        <p className="text-sm text-gray-500 mt-0.5">
          Budget hierarchy for a specific user — parent budgets and their delegation tokens.
        </p>
      </div>

      {/* Search */}
      <form onSubmit={handleSearch} className="flex gap-2">
        <input
          type="text"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder="Enter customer / user ID…"
          className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="submit"
          disabled={!inputValue.trim()}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          Search
        </button>
      </form>

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center h-40 text-gray-400">
          Loading budgets for {userId}…
        </div>
      )}

      {/* Error */}
      {isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Failed to load budgets for user "{userId}".
        </div>
      )}

      {/* Results */}
      {!isLoading && userId && data && (
        <>
          {/* Summary bar */}
          <div className="flex items-center justify-between flex-wrap gap-3">
            <div className="flex gap-6 text-sm">
              <div>
                <p className="text-xs text-gray-400">User</p>
                <p className="font-mono font-semibold text-gray-900">{userId}</p>
              </div>
              <div>
                <p className="text-xs text-gray-400">Budgets</p>
                <p className="font-semibold text-gray-900">{budgets.length} total · {activeBudgets} active</p>
              </div>
              {budgets.length > 0 && (
                <div>
                  <p className="text-xs text-gray-400">Total spend</p>
                  <p className="font-semibold text-gray-900 tabular-nums">
                    {budgets[0].unit
                      ? `${totalSpent} / ${totalLimit} ${budgets[0].unit}`
                      : `${formatAmount(totalSpent, budgets[0].currency)} / ${formatAmount(totalLimit, budgets[0].currency)}`}
                  </p>
                </div>
              )}
            </div>
            {isFetching && (
              <span className="text-xs text-gray-400 animate-pulse">Refreshing…</span>
            )}
          </div>

          {budgets.length === 0 ? (
            <div className="rounded-xl border border-gray-200 bg-white p-12 text-center text-gray-400 shadow-sm">
              No budgets found for user "{userId}".
            </div>
          ) : (
            <div className="space-y-3">
              {budgets.map((budget) => (
                <BudgetNode key={budget.id} budget={budget} />
              ))}
            </div>
          )}
        </>
      )}

      {/* Empty state before search */}
      {!userId && (
        <div className="rounded-xl border border-dashed border-gray-300 bg-white p-12 text-center text-gray-400">
          Enter a user ID above to see their budget hierarchy.
        </div>
      )}
    </div>
  );
}
