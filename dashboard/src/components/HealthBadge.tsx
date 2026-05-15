import type { BudgetResponse } from "../lib/types";

type HealthLevel = "HEALTHY" | "WARNING" | "CRITICAL";

function getBudgetHealth(budget: BudgetResponse): HealthLevel {
  if (budget.status !== "ACTIVE") return "CRITICAL";
  const usedFraction =
    budget.totalLimit > 0
      ? 1 - budget.availableQuantity / budget.totalLimit
      : 1;
  if (usedFraction >= 0.95) return "CRITICAL";
  if (usedFraction >= 0.75) return "WARNING";
  return "HEALTHY";
}

const HEALTH_CLASSES: Record<HealthLevel, string> = {
  HEALTHY: "bg-emerald-50 text-emerald-700 border border-emerald-200",
  WARNING: "bg-amber-50 text-amber-700 border border-amber-200",
  CRITICAL: "bg-red-50 text-red-700 border border-red-200",
};

const HEALTH_DOT: Record<HealthLevel, string> = {
  HEALTHY: "bg-emerald-500",
  WARNING: "bg-amber-500",
  CRITICAL: "bg-red-500",
};

interface Props {
  budget: BudgetResponse;
  className?: string;
}

export function HealthBadge({ budget, className = "" }: Props) {
  const health = getBudgetHealth(budget);
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded px-2 py-0.5 text-xs font-medium ${HEALTH_CLASSES[health]} ${className}`}
      title={`Health: ${health}`}
    >
      <span
        className={`inline-block w-1.5 h-1.5 rounded-full ${HEALTH_DOT[health]}`}
      />
      {health}
    </span>
  );
}
