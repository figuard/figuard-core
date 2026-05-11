// Central color constants — change here to retheme the whole dashboard.
// All values are Tailwind utility class strings.

import type { SpendDecision, BudgetStatus, AllocationStatus } from "./types";

export const DECISION_BADGE: Record<SpendDecision, string> = {
  AUTHORIZED: "bg-blue-100 text-blue-800 border border-blue-200",
  CONFIRMED: "bg-green-100 text-green-800 border border-green-200",
  DENIED: "bg-red-100 text-red-800 border border-red-200",
  FAILED: "bg-orange-100 text-orange-800 border border-orange-200",
  VOIDED: "bg-gray-100 text-gray-600 border border-gray-200",
};

export const DECISION_DOT: Record<SpendDecision, string> = {
  AUTHORIZED: "bg-blue-500",
  CONFIRMED: "bg-green-500",
  DENIED: "bg-red-500",
  FAILED: "bg-orange-500",
  VOIDED: "bg-gray-400",
};

export const BUDGET_STATUS_BADGE: Record<BudgetStatus, string> = {
  ACTIVE: "bg-green-100 text-green-800 border border-green-200",
  PAUSED: "bg-yellow-100 text-yellow-800 border border-yellow-200",
  EXHAUSTED: "bg-red-100 text-red-800 border border-red-200",
  CANCELLED: "bg-gray-100 text-gray-600 border border-gray-200",
  EXPIRED: "bg-gray-100 text-gray-600 border border-gray-200",
};

export const ALLOCATION_STATUS_BADGE: Record<AllocationStatus, string> = {
  ACTIVE: "bg-green-100 text-green-700",
  EXHAUSTED: "bg-red-100 text-red-700",
  PAUSED: "bg-yellow-100 text-yellow-700",
};

// Budget bar fill color based on spend percentage (0–1)
export function spendBarColor(ratio: number): string {
  if (ratio >= 0.9) return "bg-red-500";
  if (ratio >= 0.75) return "bg-yellow-500";
  return "bg-green-500";
}

// Expiry badge color based on remaining time fraction
export function expiryColor(remainingFraction: number): string {
  if (remainingFraction <= 0) return "bg-red-100 text-red-800 border border-red-200";
  if (remainingFraction <= 0.2) return "bg-yellow-100 text-yellow-800 border border-yellow-200";
  return "bg-green-100 text-green-800 border border-green-200";
}

// Donut ring stroke colors by category index (for allocation rings)
export const RING_COLORS = [
  "#3b82f6", // blue-500
  "#10b981", // emerald-500
  "#f59e0b", // amber-500
  "#8b5cf6", // violet-500
  "#ef4444", // red-500
  "#06b6d4", // cyan-500
  "#f97316", // orange-500
  "#84cc16", // lime-500
];

// Spend tree node border colors by depth (visual hierarchy)
export const TREE_DEPTH_COLORS = [
  "border-blue-500",
  "border-emerald-500",
  "border-violet-500",
  "border-amber-500",
  "border-cyan-500",
  "border-rose-500",
];
