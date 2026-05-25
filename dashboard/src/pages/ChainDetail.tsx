import { useRef, useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import { useChainDetail } from "../hooks/useChainDetail";
import { SpendTreeNode } from "../components/SpendTreeNode";
import { formatAmount, shortId } from "../lib/format";
import type { ChainDetailResponse } from "../lib/types";

const CHAIN_EVENT_LIMIT = 100;
const ZOOM_STEP = 0.15;
const MIN_ZOOM = 0.25;
const MAX_ZOOM = 1.5;

// ---------------------------------------------------------------------------
// Chain cap progress bar
// ---------------------------------------------------------------------------
function ChainCapBar({
  totalChainSpend,
  maxSubtreeQuantity,
  chainCapRemaining,
  currency,
}: {
  totalChainSpend: number;
  maxSubtreeQuantity: number;
  chainCapRemaining: number;
  currency: string;
}) {
  const pct = Math.min(100, (totalChainSpend / maxSubtreeQuantity) * 100);
  const color =
    pct >= 90 ? "bg-red-500" : pct >= 70 ? "bg-amber-400" : "bg-emerald-500";

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm space-y-2">
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium text-gray-700">Chain cap usage</span>
        <span className="text-gray-500 tabular-nums">
          {formatAmount(totalChainSpend, currency)} /{" "}
          {formatAmount(maxSubtreeQuantity, currency)}
        </span>
      </div>
      <div className="h-2 w-full rounded-full bg-gray-100 overflow-hidden">
        <div
          className={`h-full rounded-full transition-all ${color}`}
          style={{ width: `${pct}%` }}
        />
      </div>
      <p className="text-xs text-gray-400">
        {formatAmount(chainCapRemaining, currency)} remaining ·{" "}
        {pct.toFixed(1)}% used
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Zoomable tree (same pattern as SpendTree page)
// ---------------------------------------------------------------------------
function ZoomableTree({
  chain,
}: {
  chain: ChainDetailResponse;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const treeRef = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState(1);
  const [naturalHeight, setNaturalHeight] = useState(0);
  const [exporting, setExporting] = useState(false);
  const [ready, setReady] = useState(false);

  const fitToContainer = useCallback(() => {
    if (!treeRef.current || !containerRef.current) return;
    treeRef.current.style.transform = "scale(1)";
    const treeW = treeRef.current.scrollWidth;
    const treeH = treeRef.current.scrollHeight;
    const containerW = containerRef.current.clientWidth;
    setNaturalHeight(treeH);
    const fitted =
      treeW > containerW ? Math.max(MIN_ZOOM, containerW / treeW) : 1;
    setZoom(fitted);
    setReady(true);
  }, []);

  useEffect(() => {
    setReady(false);
    const raf = requestAnimationFrame(fitToContainer);
    return () => cancelAnimationFrame(raf);
  }, [chain, fitToContainer]);

  const changeZoom = (delta: number) => {
    setZoom((z) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, +(z + delta).toFixed(2))));
  };

  const exportPng = useCallback(async () => {
    if (!treeRef.current || exporting) return;
    setExporting(true);
    try {
      const { default: html2canvas } = await import("html2canvas");
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
      link.download = `chain-${shortId(chain.chainRootEventId)}.png`;
      link.href = canvas.toDataURL("image/png");
      link.click();
    } finally {
      setExporting(false);
    }
  }, [exporting, chain.chainRootEventId]);

  const containerHeight =
    naturalHeight > 0 ? Math.ceil(naturalHeight * zoom) + 32 : undefined;

  return (
    <div className="rounded-xl border border-gray-200 bg-white shadow-sm overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 border-b border-gray-100 bg-gray-50">
        <span className="text-xs text-gray-400">
          {chain.totalEvents} events in chain
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

      <div ref={containerRef} className="overflow-auto" style={{ height: containerHeight }}>
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
            {chain.roots.map((root) => (
              <SpendTreeNode
                key={root.id}
                node={root}
                currency={chain.currency}
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
export function ChainDetail() {
  const { chainRootEventId } = useParams<{ chainRootEventId: string }>();
  const { data: chain, isLoading, isError, error, isFetching } = useChainDetail(chainRootEventId);

  const fmt = (n: number) => formatAmount(n, chain?.currency ?? "USD");

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">Chain Detail</h1>
          {chain && (
            <p className="text-xs text-gray-400 font-mono mt-0.5">
              root: {chain.chainRootEventId}
            </p>
          )}
        </div>
        <div className="flex items-center gap-3">
          {isFetching && (
            <span className="text-xs text-gray-400 animate-pulse">Refreshing…</span>
          )}
          {chain && (
            <Link
              to={`/budgets/${chain.budgetId}/tree`}
              className="text-xs text-blue-500 hover:text-blue-700 transition-colors"
            >
              ← Full spend tree
            </Link>
          )}
        </div>
      </div>

      <p className="text-sm text-gray-500">
        All events in this causal chain — spawned from a single root authorization and its
        descendants. Chain caps and spend totals are scoped to this chain only.
      </p>

      {isLoading && (
        <div className="flex items-center justify-center h-40 text-gray-400">
          Loading chain…
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Failed to load chain.{" "}
          {error instanceof Error ? error.message : "Unknown error."}
        </div>
      )}

      {!isLoading && chain && (
        <>
          {/* Chain cap progress bar — only when capped */}
          {chain.maxSubtreeQuantity != null && chain.chainCapRemaining != null && (
            <ChainCapBar
              totalChainSpend={chain.totalChainSpend}
              maxSubtreeQuantity={chain.maxSubtreeQuantity}
              chainCapRemaining={chain.chainCapRemaining}
              currency={chain.currency}
            />
          )}

          {/* Summary stats */}
          <div className="flex flex-wrap gap-6 rounded-xl border border-gray-200 bg-white p-4 shadow-sm text-sm">
            <Stat label="Total events" value={String(chain.totalEvents)} />
            <Stat label="Total authorized" value={fmt(chain.totalAuthorized)} />
            <Stat label="Total confirmed" value={fmt(chain.totalConfirmed)} />
            {chain.maxSubtreeQuantity != null && (
              <Stat label="Chain cap" value={fmt(chain.maxSubtreeQuantity)} />
            )}
            <Stat
              label="Started"
              value={new Date(chain.chainStartedAt).toLocaleString()}
            />
            <Stat
              label="Last activity"
              value={new Date(chain.lastActivityAt).toLocaleString()}
            />
          </div>

          {/* Tree */}
          {chain.roots.length === 0 ? (
            <div className="text-sm text-gray-400 italic py-8 text-center">
              No events in this chain.
            </div>
          ) : chain.totalEvents > CHAIN_EVENT_LIMIT ? (
            <div className="rounded-xl border border-amber-200 bg-amber-50 p-6 text-sm text-amber-800">
              <p className="font-medium">
                Chain has {chain.totalEvents} events — too large to render as a tree.
              </p>
              <Link
                to={`/budgets/${chain.budgetId}/ledger`}
                className="mt-2 inline-flex items-center gap-1 font-medium underline hover:text-amber-900"
              >
                View in Ledger →
              </Link>
            </div>
          ) : (
            <ZoomableTree chain={chain} />
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
