import { RING_COLORS, ALLOCATION_STATUS_BADGE } from "../lib/colors";
import { formatAmount, formatPct } from "../lib/format";
import type { AllocationResponse, BudgetResponse } from "../lib/types";

const RING_SIZE = 64;
const STROKE = 7;
const R = (RING_SIZE - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * R;

function Ring({ alloc, color }: { alloc: AllocationResponse; color: string }) {
  const ratio = alloc.limit > 0 ? Math.min(1, alloc.quantitySpent / alloc.limit) : 0;
  const dashOffset = CIRCUMFERENCE * (1 - ratio);
  const isEmpty = ratio === 0;

  return (
    <div className="relative shrink-0" style={{ width: RING_SIZE, height: RING_SIZE }}>
      <svg width={RING_SIZE} height={RING_SIZE} className="-rotate-90">
        <circle
          cx={RING_SIZE / 2}
          cy={RING_SIZE / 2}
          r={R}
          fill="none"
          stroke={isEmpty ? "#d1d5db" : "#e5e7eb"}
          strokeWidth={STROKE}
          strokeDasharray={isEmpty ? "4 4" : undefined}
        />
        {!isEmpty && (
          <circle
            cx={RING_SIZE / 2}
            cy={RING_SIZE / 2}
            r={R}
            fill="none"
            stroke={color}
            strokeWidth={STROKE}
            strokeDasharray={CIRCUMFERENCE}
            strokeDashoffset={dashOffset}
            strokeLinecap="round"
            className="transition-all duration-500"
          />
        )}
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className={`text-xs font-bold ${isEmpty ? "text-gray-400" : "text-gray-700"}`}>
          {isEmpty ? "—" : formatPct(alloc.quantitySpent, alloc.limit)}
        </span>
      </div>
    </div>
  );
}

function AllocationCard({
  alloc,
  color,
  currency,
  unit,
}: {
  alloc: AllocationResponse;
  color: string;
  currency: string;
  unit?: string | null;
}) {
  const fmt = (n: number) => formatAmount(n, currency, unit);

  // Allowed categories worth showing: more than just the category itself
  const extraAllowed = (alloc.allowedCategories ?? []).filter(
    (c) => c !== alloc.category,
  );
  const hasForbidden =
    alloc.forbiddenItemTypes && alloc.forbiddenItemTypes.length > 0;
  const hasAllowed = extraAllowed.length > 0;

  return (
    <div className="flex items-start gap-3 rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 min-w-[260px]">
      {/* Ring */}
      <Ring alloc={alloc} color={color} />

      {/* Details */}
      <div className="flex-1 min-w-0 space-y-1.5">
        {/* Category + enforcement mode */}
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-semibold text-gray-800 truncate" title={alloc.category}>
            {alloc.category}
          </span>
          <span
            className={`text-xs rounded px-1.5 py-0.5 font-medium ${ALLOCATION_STATUS_BADGE[alloc.status]}`}
          >
            {alloc.enforcementMode === "STRICT"
              ? "STRICT"
              : alloc.enforcementMode === "OPEN"
              ? "OPEN"
              : alloc.status}
          </span>
        </div>

        {/* Spent / limit */}
        <p className="text-xs text-gray-500">
          {fmt(alloc.quantitySpent)} spent &nbsp;·&nbsp; {fmt(alloc.limit)} limit
          {alloc.quantityReserved > 0 && (
            <span className="text-gray-400"> · {fmt(alloc.quantityReserved)} reserved</span>
          )}
        </p>

        {/* Allowed categories (extras beyond the primary category) */}
        {hasAllowed && (
          <div className="flex items-start gap-1.5 flex-wrap">
            <span className="text-xs text-gray-400 shrink-0 mt-0.5">Also allows:</span>
            {extraAllowed.map((c) => (
              <span
                key={c}
                className="text-xs rounded bg-blue-50 text-blue-700 border border-blue-100 px-1.5 py-0.5"
              >
                {c}
              </span>
            ))}
          </div>
        )}

        {/* Forbidden item types */}
        {hasForbidden && (
          <div className="flex items-start gap-1.5 flex-wrap">
            <span className="text-xs text-gray-400 shrink-0 mt-0.5">Blocked:</span>
            {alloc.forbiddenItemTypes!.map((item) => (
              <span
                key={item}
                className="text-xs rounded bg-red-50 text-red-700 border border-red-100 px-1.5 py-0.5"
              >
                {item}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

interface Props {
  budget: BudgetResponse;
}

export function AllocationRings({ budget }: Props) {
  const { allocations, currency, unit } = budget;

  if (!allocations || allocations.length === 0) {
    return (
      <div className="text-sm text-gray-400 italic">
        No allocations — flat budget (total limit only)
      </div>
    );
  }

  return (
    <div className="flex flex-wrap gap-3">
      {allocations.map((alloc, idx) => (
        <AllocationCard
          key={alloc.id}
          alloc={alloc}
          color={RING_COLORS[idx % RING_COLORS.length]}
          currency={currency}
          unit={unit}
        />
      ))}
    </div>
  );
}
