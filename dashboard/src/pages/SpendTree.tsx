import { useRef, useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import { useBudget } from "../hooks/useBudget";
import { useSpendTree } from "../hooks/useSpendTree";
import { SpendTreeNode } from "../components/SpendTreeNode";
import { formatAmount, shortId } from "../lib/format";
import type { SpendTreeResponse } from "../lib/types";

const TREE_EVENT_LIMIT = 25;
const ZOOM_STEP = 0.15;
const MIN_ZOOM = 0.25;
const MAX_ZOOM = 1.5;

// ---------------------------------------------------------------------------
// Zoomable tree canvas
// ---------------------------------------------------------------------------
function ZoomableTree({
  tree,
  currency,
  unit,
  budgetId,
}: {
  tree: SpendTreeResponse;
  currency: string;
  unit?: string | null;
  budgetId?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const treeRef = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState(1);
  const [naturalHeight, setNaturalHeight] = useState(0);
  const [exporting, setExporting] = useState(false);
  // null = auto-fit was never computed yet; used to suppress the initial scale flash
  const [ready, setReady] = useState(false);

  const fitToContainer = useCallback(() => {
    if (!treeRef.current || !containerRef.current) return;
    // Measure at zoom=1 by temporarily resetting scale
    treeRef.current.style.transform = "scale(1)";
    const treeW = treeRef.current.scrollWidth;
    const treeH = treeRef.current.scrollHeight;
    const containerW = containerRef.current.clientWidth;
    setNaturalHeight(treeH);
    const fitted =
      treeW > containerW
        ? Math.max(MIN_ZOOM, containerW / treeW)
        : 1;
    setZoom(fitted);
    setReady(true);
  }, []);

  // Auto-fit whenever tree data changes
  useEffect(() => {
    setReady(false);
    // Let the DOM paint the tree at natural size, then measure
    const raf = requestAnimationFrame(fitToContainer);
    return () => cancelAnimationFrame(raf);
  }, [tree, fitToContainer]);

  const changeZoom = (delta: number) => {
    setZoom((z) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, +(z + delta).toFixed(2))));
  };

  const exportPng = useCallback(async () => {
    if (!treeRef.current || exporting) return;
    setExporting(true);
    try {
      const { default: html2canvas } = await import("html2canvas");
      // Temporarily reset zoom so we capture at native resolution
      const prevTransform = treeRef.current.style.transform;
      treeRef.current.style.transform = "scale(1)";
      const canvas = await html2canvas(treeRef.current, {
        backgroundColor: "#ffffff",
        scale: 2,
        useCORS: true,
        logging: false,
      });
      treeRef.current.style.transform = prevTransform;
      const link = document.createElement("a");
      link.download = `spend-tree-${budgetId ? shortId(budgetId) : "export"}.png`;
      link.href = canvas.toDataURL("image/png");
      link.click();
    } finally {
      setExporting(false);
    }
  }, [exporting, budgetId]);

  // Container height tracks scaled content to avoid blank space below tree
  const containerHeight =
    naturalHeight > 0 ? Math.ceil(naturalHeight * zoom) + 32 : undefined;

  return (
    <div className="rounded-xl border border-gray-200 bg-white shadow-sm overflow-hidden">
      {/* Toolbar */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-gray-100 bg-gray-50">
        <span className="text-xs text-gray-400">
          {tree.roots.length} root chain{tree.roots.length !== 1 ? "s" : ""} · {tree.totalEvents} events
        </span>
        <div className="flex items-center gap-1">
          <button
            onClick={() => changeZoom(-ZOOM_STEP)}
            disabled={zoom <= MIN_ZOOM}
            title="Zoom out"
            className="rounded p-1.5 text-gray-500 hover:bg-gray-200 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="7" cy="7" r="5" />
              <line x1="11" y1="11" x2="15" y2="15" />
              <line x1="4" y1="7" x2="10" y2="7" />
            </svg>
          </button>

          <span className="text-xs tabular-nums text-gray-600 w-10 text-center select-none">
            {Math.round(zoom * 100)}%
          </span>

          <button
            onClick={() => changeZoom(ZOOM_STEP)}
            disabled={zoom >= MAX_ZOOM}
            title="Zoom in"
            className="rounded p-1.5 text-gray-500 hover:bg-gray-200 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="7" cy="7" r="5" />
              <line x1="11" y1="11" x2="15" y2="15" />
              <line x1="4" y1="7" x2="10" y2="7" />
              <line x1="7" y1="4" x2="7" y2="10" />
            </svg>
          </button>

          <div className="w-px h-4 bg-gray-200 mx-1" />

          <button
            onClick={fitToContainer}
            title="Fit to window"
            className="rounded px-2 py-1 text-xs text-gray-500 hover:bg-gray-200 transition-colors"
          >
            Fit
          </button>

          <div className="w-px h-4 bg-gray-200 mx-1" />

          <button
            onClick={exportPng}
            disabled={exporting}
            title="Export as PNG"
            className="rounded px-2 py-1 text-xs text-gray-500 hover:bg-gray-200 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {exporting ? "Exporting…" : "Export PNG"}
          </button>
        </div>
      </div>

      {/* Scrollable canvas */}
      <div
        ref={containerRef}
        className="overflow-auto"
        style={{ height: containerHeight }}
      >
        <div
          ref={treeRef}
          className="px-8 py-6 w-max origin-top-left"
          style={{
            transform: `scale(${zoom})`,
            opacity: ready ? 1 : 0,
            transition: ready ? "transform 0.15s ease" : "none",
          }}
        >
          <div className="space-y-6">
            {tree.roots.map((root) => (
              <SpendTreeNode
                key={root.id}
                node={root}
                currency={currency}
                unit={unit}
                depth={0}
                isRoot
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------
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
          {/* Summary stats */}
          <div className="flex gap-6 rounded-xl border border-gray-200 bg-white p-4 shadow-sm text-sm">
            <Stat label="Total events" value={String(tree.totalEvents)} />
            <Stat label="Total authorized" value={fmt(tree.totalAuthorized)} />
            <Stat label="Total confirmed" value={fmt(tree.totalConfirmed)} />
            <Stat label="Root chains" value={String(tree.roots.length)} />
          </div>

          {/* Tree */}
          {tree.roots.length === 0 ? (
            <div className="text-sm text-gray-400 italic py-8 text-center">
              No events in this budget yet.
            </div>
          ) : tree.totalEvents > TREE_EVENT_LIMIT ? (
            <div className="rounded-xl border border-amber-200 bg-amber-50 p-6 text-sm text-amber-800 space-y-2">
              <p className="font-medium">
                Tree view is available for sessions under {TREE_EVENT_LIMIT} events.
              </p>
              <p>
                This budget has{" "}
                <span className="font-semibold">{tree.totalEvents} events</span> —
                rendering them as a tree would produce an unreadable layout. Use the
                Ledger for full event details with filtering and sorting.
              </p>
              <Link
                to={`/budgets/${id}/ledger`}
                className="inline-flex items-center gap-1 font-medium underline hover:text-amber-900"
              >
                View in Ledger →
              </Link>
            </div>
          ) : (
            <>
              {/* Hint: flat data with no causal chains */}
              {tree.roots.every((r) => !r.children || r.children.length === 0) && (
                <div className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-700">
                  <span className="font-medium">No sub-agent chains detected.</span>{" "}
                  All events are root nodes with no children. The tree becomes a
                  branching visualization when agents pass{" "}
                  <code className="font-mono text-xs bg-blue-100 px-1 py-0.5 rounded">
                    parent_event_id
                  </code>{" "}
                  on their authorize calls.
                </div>
              )}
              <ZoomableTree
                tree={tree}
                currency={budget?.currency ?? "USD"}
                unit={budget?.unit}
                budgetId={id}
              />
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
