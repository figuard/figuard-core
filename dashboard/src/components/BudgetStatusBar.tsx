import { spendBarColor } from "../lib/colors";
import { formatAmount, formatPct } from "../lib/format";
import type { BudgetResponse } from "../lib/types";

interface Props {
  budget: BudgetResponse;
}

export function BudgetStatusBar({ budget }: Props) {
  const { totalLimit, quantitySpent, quantityReserved, availableQuantity, currency, unit } = budget;
  const fmt = (n: number) => formatAmount(n, currency, unit);

  const spentRatio = totalLimit > 0 ? quantitySpent / totalLimit : 0;
  const reservedRatio = totalLimit > 0 ? quantityReserved / totalLimit : 0;
  const combinedRatio = Math.min(1, spentRatio + reservedRatio);

  const barColor = spendBarColor(combinedRatio);

  return (
    <div className="space-y-2">
      {/* Numbers row */}
      <div className="flex items-center justify-between text-sm">
        <div className="flex gap-4">
          <span className="text-gray-500">
            Spent:{" "}
            <span className="font-semibold text-gray-900">{fmt(quantitySpent)}</span>
          </span>
          {quantityReserved > 0 && (
            <span className="text-gray-500">
              Reserved:{" "}
              <span className="font-semibold text-yellow-700">{fmt(quantityReserved)}</span>
            </span>
          )}
          <span className="text-gray-500">
            Remaining:{" "}
            <span className="font-semibold text-gray-900">{fmt(availableQuantity)}</span>
          </span>
        </div>
        <span className="text-gray-500">
          {formatPct(quantitySpent, totalLimit)} of {fmt(totalLimit)}
        </span>
      </div>

      {/* Progress bar */}
      <div className="relative h-3 w-full rounded-full bg-gray-100 overflow-hidden" title={`${formatPct(combinedRatio * 100, 100)} used`}>
        {/* Spent portion */}
        <div
          className={`absolute left-0 top-0 h-full transition-all duration-500 ${barColor}`}
          style={{ width: `${Math.min(100, spentRatio * 100)}%` }}
        />
        {/* Reserved portion (stacked on top of spent, lighter) */}
        {reservedRatio > 0 && (
          <div
            className="absolute top-0 h-full bg-yellow-300 opacity-70"
            style={{
              left: `${Math.min(100, spentRatio * 100)}%`,
              width: `${Math.min(100 - spentRatio * 100, reservedRatio * 100)}%`,
            }}
          />
        )}
      </div>
    </div>
  );
}
