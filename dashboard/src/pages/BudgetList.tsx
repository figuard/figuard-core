import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useBudgets } from "../hooks/useBudgets";
import { BudgetStatusBar } from "../components/BudgetStatusBar";
import { ExpiryBadge } from "../components/ExpiryBadge";
import { BUDGET_STATUS_BADGE } from "../lib/colors";
import { formatAmount, formatDateTime, shortId } from "../lib/format";
import type { BudgetStatus } from "../lib/types";
import type { ListBudgetsParams } from "../api/budgets";

const STATUS_OPTIONS: Array<BudgetStatus | ""> = [
  "",
  "ACTIVE",
  "PAUSED",
  "EXHAUSTED",
  "EXPIRED",
  "CANCELLED",
];

export function BudgetList() {
  const navigate = useNavigate();
  const [params, setParams] = useState<ListBudgetsParams>({
    page: 0,
    size: 20,
    status: "",
  });
  const { data, isLoading, isFetching, isError, error } = useBudgets(params);

  const totalPages = data?.totalPages ?? 1;
  const currentPage = data?.number ?? 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">Budgets</h1>
          {data && (
            <p className="text-sm text-gray-500 mt-0.5">
              {data.totalElements.toLocaleString()} total
            </p>
          )}
        </div>

        <div className="flex items-center gap-3">
          {isFetching && (
            <span className="text-xs text-gray-400 animate-pulse">
              Refreshing…
            </span>
          )}
          {/* Status filter */}
          <select
            value={params.status ?? ""}
            onChange={(e) =>
              setParams((p) => ({
                ...p,
                page: 0,
                status: e.target.value as BudgetStatus | "",
              }))
            }
            className="rounded border border-gray-300 px-2 py-1.5 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {s === "" ? "All statuses" : s}
              </option>
            ))}
          </select>
        </div>
      </div>

      {isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Failed to load budgets.{" "}
          {error instanceof Error ? error.message : "Unknown error."}
        </div>
      )}

      {isLoading && (
        <div className="flex items-center justify-center h-40 text-gray-400">
          Loading budgets…
        </div>
      )}

      {!isLoading && data && (
        <>
          {data.content.length === 0 ? (
            <div className="rounded-xl border border-gray-200 bg-white p-12 text-center text-gray-400 shadow-sm">
              No budgets found.{" "}
              {params.status
                ? `Try removing the "${params.status}" filter.`
                : "Create your first budget using the FiGuard SDK."}
            </div>
          ) : (
            <div className="space-y-3">
              {data.content.map((budget) => (
                <div
                  key={budget.id}
                  onClick={() => navigate(`/budgets/${budget.id}`)}
                  className="cursor-pointer rounded-xl border border-gray-200 bg-white p-4 shadow-sm hover:border-blue-300 hover:shadow transition-all"
                >
                  {/* Top row */}
                  <div className="flex items-start justify-between gap-3 mb-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-mono text-xs text-gray-400" title={budget.id}>
                          {shortId(budget.id)}
                        </span>
                        {budget.userId && (
                          <span className="text-xs text-gray-500">
                            user: <span className="font-medium text-gray-700">{budget.userId}</span>
                          </span>
                        )}
                        {budget.externalReference && (
                          <span className="text-xs bg-gray-100 text-gray-600 rounded px-1.5 py-0.5 font-mono">
                            {budget.externalReference}
                          </span>
                        )}
                      </div>
                      <p className="mt-1 text-xs text-gray-400">
                        Created {formatDateTime(budget.createdAt)}
                      </p>
                    </div>

                    <div className="flex items-center gap-2 flex-wrap shrink-0">
                      <span
                        className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${BUDGET_STATUS_BADGE[budget.status]}`}
                      >
                        {budget.status}
                      </span>
                      <ExpiryBadge
                        expiresAt={budget.expiresAt}
                        createdAt={budget.createdAt}
                        budgetStatus={budget.status}
                      />
                      <span className="text-xs text-gray-400 font-medium">
                        {formatAmount(budget.totalLimit, budget.currency, budget.unit)}
                      </span>
                    </div>
                  </div>

                  {/* Status bar */}
                  <BudgetStatusBar budget={budget} />

                  {/* Allocation chips */}
                  {budget.allocations && budget.allocations.length > 0 && (
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {budget.allocations.map((alloc) => (
                        <span
                          key={alloc.id}
                          className="text-xs bg-blue-50 text-blue-700 rounded px-2 py-0.5"
                          title={`${alloc.enforcementMode} · ${alloc.status}`}
                        >
                          {alloc.category}:{" "}
                          {formatAmount(alloc.quantitySpent, budget.currency, budget.unit)} /{" "}
                          {formatAmount(alloc.limit, budget.currency, budget.unit)}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between pt-1">
              <button
                disabled={currentPage === 0}
                onClick={() => setParams((p) => ({ ...p, page: currentPage - 1 }))}
                className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
              >
                ← Previous
              </button>
              <span className="text-sm text-gray-500">
                Page {currentPage + 1} of {totalPages}
              </span>
              <button
                disabled={currentPage >= totalPages - 1}
                onClick={() => setParams((p) => ({ ...p, page: currentPage + 1 }))}
                className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
              >
                Next →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
