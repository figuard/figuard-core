import { useState } from "react";
import { useParams } from "react-router-dom";
import { useBudget } from "../hooks/useBudget";
import { useLedger } from "../hooks/useLedger";
import { EventRow } from "../components/EventRow";
import { FilterSidebar } from "../components/FilterSidebar";
import type { LedgerParams } from "../api/ledger";

export function Ledger() {
  const { id } = useParams<{ id: string }>();
  const { data: budget } = useBudget(id);
  const [params, setParams] = useState<LedgerParams>({ page: 0, size: 50 });
  const { data, isLoading, isFetching, isError, error } = useLedger(id, params);

  const totalPages = data?.totalPages ?? 1;
  const currentPage = data?.number ?? 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-gray-900">Ledger</h1>
        {isFetching && (
          <span className="text-xs text-gray-400 animate-pulse">Refreshing…</span>
        )}
      </div>

      <div className="flex gap-6">
        {/* Filter sidebar */}
        <FilterSidebar params={params} onChange={setParams} />

        {/* Table */}
        <div className="flex-1 min-w-0 space-y-3">
          {isError && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
              Failed to load ledger.{" "}
              {error instanceof Error ? error.message : "Unknown error."}
            </div>
          )}

          {isLoading && (
            <div className="flex items-center justify-center h-40 text-gray-400">
              Loading events…
            </div>
          )}

          {!isLoading && data && (
            <>
              <p className="text-sm text-gray-500">
                {data.totalElements.toLocaleString()} events total
                {(params.decision || params.traceId) && " (filtered)"}
              </p>

              <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white shadow-sm">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-gray-100 bg-gray-50">
                      <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">
                        Time
                      </th>
                      <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                        Decision
                      </th>
                      <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide text-right whitespace-nowrap">
                        Amount
                      </th>
                      <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">
                        Category
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
                      <th className="px-4 py-2.5 w-6" />
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {data.content.length === 0 ? (
                      <tr>
                        <td
                          colSpan={8}
                          className="px-4 py-10 text-center text-sm text-gray-400"
                        >
                          No events match the current filters.
                        </td>
                      </tr>
                    ) : (
                      data.content.map((ev) => (
                        <EventRow
                          key={ev.id}
                          event={ev}
                          currency={budget?.currency ?? "USD"}
                          unit={budget?.unit}
                        />
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              {totalPages > 1 && (
                <div className="flex items-center justify-between pt-1">
                  <button
                    disabled={currentPage === 0}
                    onClick={() =>
                      setParams((p) => ({ ...p, page: currentPage - 1 }))
                    }
                    className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    ← Previous
                  </button>
                  <span className="text-sm text-gray-500">
                    Page {currentPage + 1} of {totalPages}
                  </span>
                  <button
                    disabled={currentPage >= totalPages - 1}
                    onClick={() =>
                      setParams((p) => ({ ...p, page: currentPage + 1 }))
                    }
                    className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    Next →
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
