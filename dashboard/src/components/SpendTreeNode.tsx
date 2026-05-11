import { useState } from "react";
import { DECISION_BADGE, DECISION_DOT } from "../lib/colors";
import { formatAmount, shortId } from "../lib/format";
import type { SpendTreeNodeResponse } from "../lib/types";

// ---------------------------------------------------------------------------
// Compact node card
// ---------------------------------------------------------------------------
function NodeCard({
  node,
  currency,
  unit,
  hasChildren,
  open,
  onToggle,
  isRoot,
}: {
  node: SpendTreeNodeResponse;
  currency: string;
  unit?: string | null;
  hasChildren: boolean;
  open: boolean;
  onToggle: () => void;
  isRoot: boolean;
}) {
  const fmt = (n: number) => formatAmount(n, currency, unit);
  const badgeClasses = DECISION_BADGE[node.decision];
  const dotClass = DECISION_DOT[node.decision];

  return (
    <div
      className={`w-56 rounded-xl border bg-white shadow-sm select-none
        ${isRoot ? "border-gray-400 shadow-md" : "border-gray-200"}
      `}
    >
      {/* Top bar: decision + amount */}
      <div
        className={`flex items-center justify-between px-3 py-2 rounded-t-xl border-b border-gray-100
          ${isRoot ? "bg-gray-50" : "bg-white"}
        `}
      >
        <span className={`inline-flex items-center gap-1.5 rounded px-2 py-0.5 text-xs font-medium ${badgeClasses}`}>
          <span className={`w-1.5 h-1.5 rounded-full ${dotClass}`} />
          {node.decision}
        </span>
        <span className="text-sm font-semibold tabular-nums text-gray-900">
          {fmt(node.requestedQuantity)}
        </span>
      </div>

      {/* Body */}
      <div className="px-3 py-2 space-y-1">
        <p className="text-xs font-mono text-gray-600 truncate" title={node.agentId}>
          {node.agentId}
        </p>
        {node.description && (
          <p className="text-xs text-gray-500 truncate leading-tight" title={node.description}>
            {node.description}
          </p>
        )}
        <div className="flex items-center justify-between pt-0.5">
          {node.claimedCategory ? (
            <span className="text-xs bg-blue-50 text-blue-600 rounded px-1.5 py-0.5 truncate max-w-[100px]">
              {node.claimedCategory}
            </span>
          ) : (
            <span />
          )}
          <span className="text-xs text-gray-400 font-mono" title={node.id}>
            {shortId(node.id)}
          </span>
        </div>
      </div>

      {/* Expand/collapse footer — only when node has children */}
      {hasChildren && (
        <button
          onClick={onToggle}
          className="w-full flex items-center justify-center gap-1 py-1.5 border-t border-gray-100
            text-xs text-gray-400 hover:text-gray-600 hover:bg-gray-50 rounded-b-xl transition-colors"
        >
          {open ? (
            <>
              <span>▲</span> Collapse
            </>
          ) : (
            <>
              <span>▼</span> {node.children!.length} sub-agent{node.children!.length !== 1 ? "s" : ""}
            </>
          )}
        </button>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Connector drawn above each child column.
// Horizontal line spans from center of first sibling to center of last sibling.
// Vertical line drops from the horizontal bar to the child card.
// ---------------------------------------------------------------------------
function ChildConnector({
  isOnly,
  isFirst,
  isLast,
}: {
  isOnly: boolean;
  isFirst: boolean;
  isLast: boolean;
}) {
  return (
    <div className="relative flex h-8 w-full justify-center">
      {/* Left half of horizontal bar (hidden for first child) */}
      {!isOnly && !isFirst && (
        <div className="absolute top-0 left-0 right-1/2 h-px bg-gray-300" />
      )}
      {/* Right half of horizontal bar (hidden for last child) */}
      {!isOnly && !isLast && (
        <div className="absolute top-0 left-1/2 right-0 h-px bg-gray-300" />
      )}
      {/* Vertical drop from bar to child */}
      <div className="w-px h-full bg-gray-300" />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tree node — renders card + recursive children below in a top-down layout
// ---------------------------------------------------------------------------
interface Props {
  node: SpendTreeNodeResponse;
  currency: string;
  unit?: string | null;
  depth?: number;
  isRoot?: boolean;
}

export function SpendTreeNode({
  node,
  currency,
  unit,
  depth = 0,
  isRoot = false,
}: Props) {
  const [open, setOpen] = useState(true);
  const children = node.children ?? [];
  const hasChildren = children.length > 0;

  return (
    <div className="flex flex-col items-center">
      {/* Card */}
      <NodeCard
        node={node}
        currency={currency}
        unit={unit}
        hasChildren={hasChildren}
        open={open}
        onToggle={() => setOpen((o) => !o)}
        isRoot={isRoot}
      />

      {/* Children subtree */}
      {hasChildren && open && (
        <>
          {/* Trunk: vertical line from card to horizontal bar */}
          <div className="w-px bg-gray-300" style={{ height: 28 }} />

          {/* Children row */}
          <div className="flex items-start">
            {children.map((child, idx) => {
              const isOnly = children.length === 1;
              const isFirst = idx === 0;
              const isLast = idx === children.length - 1;

              return (
                <div key={child.id} className="flex flex-col items-center px-3">
                  <ChildConnector isOnly={isOnly} isFirst={isFirst} isLast={isLast} />
                  <SpendTreeNode
                    node={child}
                    currency={currency}
                    unit={unit}
                    depth={depth + 1}
                  />
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
