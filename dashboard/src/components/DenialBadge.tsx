import { DECISION_BADGE } from "../lib/colors";
import { formatDenialReason } from "../lib/format";
import type { DenialCode, SpendDecision } from "../lib/types";

interface Props {
  decision: SpendDecision;
  denialReason?: DenialCode | null;
  // When true, shows denial reason as secondary text inline (for table rows).
  // When false (default), shows only the decision badge (for tree nodes etc.)
  showReason?: boolean;
}

export function DenialBadge({ decision, denialReason, showReason = true }: Props) {
  const classes = DECISION_BADGE[decision];

  return (
    <span className="inline-flex flex-col items-start gap-0.5">
      <span
        className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${classes}`}
      >
        {decision}
      </span>
      {showReason && decision === "DENIED" && denialReason && (
        <span className="text-xs text-gray-400 pl-0.5">
          {formatDenialReason(denialReason)}
        </span>
      )}
    </span>
  );
}
