import { useParams } from "react-router-dom";
import { useBudget } from "../hooks/useBudget";
import { useSpendTree } from "../hooks/useSpendTree";
import { SpendTreeNode } from "../components/SpendTreeNode";
import { formatAmount } from "../lib/format";

export function SpendTree() {
  const { id } = useParams<{ id: string }>();
  const { data: budget } = useBudget(id);
  const {
    data: tree,
    isLoading,
    isError,
    error,
    isFetching,
  } = useSpendTree(id);

  const fmt = (n: number) =>
    formatAmount(n, budget?.currency ?? "USD", budget?.unit);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-gray-900">Spend Tree</h1>
        {isFetching && (
          <span className="text-xs text-gray-400 animate-pulse">Refreshing…</span>
        )}
      </div>
      <p className="text-sm text-gray-500">
        Causal chain of all events in this budget. Root nodes are events with no
        parent. Expand a node to see which sub-agents it spawned.
      </p>

      {isLoading && (
        <div className="flex items-center justify-center h-40 text-gray-400">
          Loading tree…
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Failed to load spend tree.{" "}
          {error instanceof Error ? error.message : "Unknown error."}
        </div>
      )}

      {!isLoading && tree && (
        <>
          {/* Summary row */}
          <div className="flex gap-6 rounded-xl border border-gray-200 bg-white p-4 shadow-sm text-sm">
            <Stat label="Total events" value={String(tree.totalEvents)} />
            <Stat label="Total authorized" value={fmt(tree.totalAuthorized)} />
            <Stat label="Total confirmed" value={fmt(tree.totalConfirmed)} />
            <Stat
              label="Root chains"
              value={String(tree.roots.length)}
            />
          </div>

          {/* Tree */}
          {tree.roots.length === 0 ? (
            <div className="text-sm text-gray-400 italic py-8 text-center">
              No events in this budget yet.
            </div>
          ) : (
            <>
              {/* Show a hint when all roots are leaf nodes — flat data, no causal chains */}
              {tree.roots.every((r) => !r.children || r.children.length === 0) && (
                <div className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-700">
                  <span className="font-medium">No sub-agent chains detected.</span>{" "}
                  All events are root nodes with no children. The tree becomes a
                  branching visualization when agents pass{" "}
                  <code className="font-mono text-xs bg-blue-100 px-1 py-0.5 rounded">
                    parent_event_id
                  </code>{" "}
                  on their authorize calls — each child event appears nested under its
                  parent, showing which orchestrator spawned which sub-agent spend.
                  Run{" "}
                  <code className="font-mono text-xs bg-blue-100 px-1 py-0.5 rounded">
                    python demo/demo.py
                  </code>{" "}
                  to seed a multi-agent example.
                </div>
              )}
              <div className="overflow-x-auto pb-4">
              <div className="space-y-4 min-w-max">
                {tree.roots.map((root) => (
                  <SpendTreeNode
                    key={root.id}
                    node={root}
                    currency={budget?.currency ?? "USD"}
                    unit={budget?.unit}
                    depth={0}
                    isRoot
                  />
                ))}
              </div>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-gray-400">{label}</p>
      <p className="font-semibold text-gray-900 tabular-nums">{value}</p>
    </div>
  );
}
