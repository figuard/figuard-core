import type { DenialCode } from "./types";

// Format a monetary or unit amount with currency/unit label.
export function formatAmount(amount: number, currency: string, unit?: string | null): string {
  if (unit) {
    return `${amount.toLocaleString(undefined, { maximumFractionDigits: 2 })} ${unit}`;
  }
  try {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 4,
    }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${currency}`;
  }
}

// Short date-time for table cells.
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

// Relative "X ago" for recent events.
export function formatRelative(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDays = Math.floor(diffHr / 24);
  return `${diffDays}d ago`;
}

// Human-readable duration from now until a future ISO date.
export function formatDuration(iso: string): string {
  const diffMs = new Date(iso).getTime() - Date.now();
  if (diffMs <= 0) return "Expired";
  const diffSec = Math.floor(diffMs / 1000);
  const d = Math.floor(diffSec / 86400);
  const h = Math.floor((diffSec % 86400) / 3600);
  const m = Math.floor((diffSec % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

// Fraction of budget lifetime remaining (0–1). Returns null if no expiry.
export function expiryFraction(expiresAt: string | null, createdAt: string): number | null {
  if (!expiresAt) return null;
  const total = new Date(expiresAt).getTime() - new Date(createdAt).getTime();
  const remaining = new Date(expiresAt).getTime() - Date.now();
  if (total <= 0) return 0;
  return Math.max(0, Math.min(1, remaining / total));
}

// Trim a UUID to show only the last 8 chars for compact display.
export function shortId(id: string): string {
  return `…${id.slice(-8)}`;
}

// Human-readable denial reason labels.
const DENIAL_LABELS: Record<DenialCode, string> = {
  MISSING_SESSION_TOKEN: "Missing session token",
  INVALID_SESSION_TOKEN: "Invalid session token",
  TENANT_MISMATCH: "Tenant mismatch",
  CURRENCY_MISMATCH: "Currency mismatch",
  MISSING_CLAIMED_CATEGORY: "Missing category",
  NO_MATCHING_ALLOCATION: "No matching allocation",
  FORBIDDEN_ITEM_TYPE: "Forbidden item type",
  INSUFFICIENT_FUNDS: "Insufficient funds",
  ALLOCATION_EXHAUSTED: "Allocation exhausted",
  BUDGET_EXHAUSTED: "Budget exhausted",
  BUDGET_PAUSED: "Budget paused",
  BUDGET_EXPIRED: "Budget expired",
  BUDGET_CANCELLED: "Budget cancelled",
  DUPLICATE_REQUEST: "Duplicate request",
  INVALID_PARENT_EVENT: "Invalid parent event",
  CAUSAL_CYCLE_DETECTED: "Causal cycle",
  CAUSAL_CHAIN_TOO_DEEP: "Chain too deep",
  EXCEEDS_QUANTITY_LIMIT: "Exceeds quantity limit",
  INTENT_SCOPE_VIOLATION: "Intent scope violation",
  ANOMALY_DETECTED: "Anomaly detected",
  ENTITY_ALREADY_AUTHORIZED: "Entity already authorized",
  VELOCITY_LIMIT_EXCEEDED: "Velocity limit exceeded",
};

export function formatDenialReason(code: DenialCode | null): string {
  if (!code) return "—";
  return DENIAL_LABELS[code] ?? code;
}

// Percentage string "73.4%"
export function formatPct(numerator: number, denominator: number): string {
  if (denominator === 0) return "0%";
  return `${((numerator / denominator) * 100).toFixed(1)}%`;
}
