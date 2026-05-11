import { RING_COLORS, ALLOCATION_STATUS_BADGE } from "../lib/colors";
import { formatAmount, formatPct } from "../lib/format";
import type { AllocationResponse, BudgetResponse } from "../lib/types";

const RING_SIZE = 72;
const STROKE = 8;
const R = (RING_SIZE - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * R;

function AllocationRing({
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
  const ratio = alloc.limit > 0 ? Math.min(1, alloc.quantitySpent / alloc.limit) : 0;
  const dashOffset = CIRCUMFERENCE * (1 - ratio);
  const fmt = (n: number) => formatAmount(n, currency, unit);

  return (
    <div className="flex flex-col items-center gap-1 min-w-[88px]">
      <div className="relative" style={{ width: RING_SIZE, height: RING_SIZE }}>
        {/* Background track */}
        <svg width={RING_SIZE} height={RING_SIZE} className="-rotate-90">
          <circle
            cx={RING_SIZE / 2}
            cy={RING_SIZE / 2}
            r={R}
            fill="none"
            stroke="#e5e7eb"
            strokeWidth={STROKE}
          />
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
        </svg>
        {/* Center label */}
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="text-xs font-bold text-gray-700">
            {formatPct(alloc.quantitySpent, alloc.limit)}
          </span>
        </div>
      </div>

      {/* Category name */}
      <span className="text-xs font-medium text-gray-700 text-center leading-tight max-w-[80px] truncate" title={alloc.category}>
        {alloc.category}
      </span>

      {/* Spent / limit */}
      <span className="text-xs text-gray-500 text-center">
        {fmt(alloc.quantitySpent)} / {fmt(alloc.limit)}
      </span>

      {/* Status badge */}
      <span className={`text-xs rounded px-1.5 py-0.5 font-medium ${ALLOCATION_STATUS_BADGE[alloc.status]}`}>
        {alloc.enforcementMode === "STRICT" ? "STRICT" : alloc.status}
      </span>
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
    <div className="flex flex-wrap gap-6">
      {allocations.map((alloc, idx) => (
        <AllocationRing
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
