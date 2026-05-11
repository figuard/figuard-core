import { expiryColor } from "../lib/colors";
import { expiryFraction, formatDuration } from "../lib/format";
import type { BudgetStatus } from "../lib/types";

interface Props {
  expiresAt: string | null;
  createdAt: string;
  budgetStatus: BudgetStatus;
}

export function ExpiryBadge({ expiresAt, createdAt, budgetStatus }: Props) {
  // Only show on ACTIVE budgets with a future expiry date.
  // Non-ACTIVE statuses (EXPIRED, PAUSED, EXHAUSTED, CANCELLED) already
  // communicate terminal state via the status badge — no need to duplicate.
  // If the expiry is in the past but status is still ACTIVE, the server sweep
  // hasn't run yet; don't show a contradictory "Expired" badge here.
  if (budgetStatus !== "ACTIVE" || !expiresAt) return null;

  const fraction = expiryFraction(expiresAt, createdAt) ?? 0;
  if (fraction <= 0) return null; // past expiry but status not yet updated — hide

  const classes = expiryColor(fraction);

  return (
    <span
      className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${classes}`}
      title={new Date(expiresAt).toLocaleString()}
    >
      Expires in {formatDuration(expiresAt)}
    </span>
  );
}
