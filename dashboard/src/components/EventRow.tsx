import { useState } from "react";
import { DenialBadge } from "./DenialBadge";
import { formatAmount, formatDateTime, shortId } from "../lib/format";
import type { SpendEventResponse } from "../lib/types";

interface Props {
  event: SpendEventResponse;
  currency: string;
  unit?: string | null;
}

export function EventRow({ event, currency, unit }: Props) {
  const [expanded, setExpanded] = useState(false);
  const fmt = (n: number) => formatAmount(n, currency, unit);

  return (
    <>
      <tr
        className="hover:bg-gray-50 cursor-pointer select-none"
        onClick={() => setExpanded((x) => !x)}
      >
        <td className="px-4 py-2.5 text-xs text-gray-500 whitespace-nowrap font-mono">
          {formatDateTime(event.createdAt)}
        </td>
        <td className="px-4 py-2.5 whitespace-nowrap">
          <DenialBadge decision={event.decision} denialReason={event.denialReason} />
        </td>
        <td className="px-4 py-2.5 text-sm text-right text-gray-900 whitespace-nowrap tabular-nums">
          {fmt(event.requestedQuantity)}
        </td>
        <td className="px-4 py-2.5 text-sm text-gray-700 whitespace-nowrap">
          {event.claimedCategory ?? <span className="text-gray-400">—</span>}
        </td>
        <td className="px-4 py-2.5 text-sm text-gray-700 whitespace-nowrap font-mono text-xs">
          {event.agentId}
        </td>
        <td className="px-4 py-2.5 text-sm text-gray-500 max-w-xs truncate" title={event.description ?? undefined}>
          {event.description ?? <span className="text-gray-400">—</span>}
        </td>
        <td className="px-4 py-2.5 text-xs text-gray-400 font-mono whitespace-nowrap">
          {shortId(event.id)}
        </td>
        <td className="px-4 py-2.5 text-gray-400">
          <span className="text-xs">{expanded ? "▲" : "▼"}</span>
        </td>
      </tr>

      {expanded && (
        <tr className="bg-gray-50 border-b border-gray-200">
          <td colSpan={8} className="px-4 py-3">
            <div className="grid grid-cols-2 md:grid-cols-3 gap-x-8 gap-y-2 text-xs">
              <Detail label="Event ID" value={event.id} mono />
              <Detail label="Decision" value={event.decision} />
              <Detail label="Requested" value={fmt(event.requestedQuantity)} />
              {event.confirmedQuantity != null && (
                <Detail label="Confirmed" value={fmt(event.confirmedQuantity)} />
              )}
              <Detail label="Agent ID" value={event.agentId} mono />
              {event.agentType && <Detail label="Agent type" value={event.agentType} />}
              <Detail label="Action type" value={event.actionType} />
              {event.claimedCategory && (
                <Detail label="Category" value={event.claimedCategory} />
              )}
              {event.claimedItemType && (
                <Detail label="Item type" value={event.claimedItemType} />
              )}
              {event.entityId && <Detail label="Entity ID" value={event.entityId} mono />}
              {event.traceId && <Detail label="Trace ID" value={event.traceId} mono />}
              {event.parentEventId && (
                <Detail label="Parent event" value={event.parentEventId} mono />
              )}
              {event.idempotencyKey && (
                <Detail label="Idempotency key" value={event.idempotencyKey} mono />
              )}
              {event.denialReason && (
                <Detail label="Denial reason" value={event.denialReason} />
              )}
              {event.failureReason && (
                <Detail label="Failure reason" value={event.failureReason} />
              )}
              {event.intentContext && (
                <Detail label="Intent context" value={event.intentContext} />
              )}
              {event.description && (
                <div className="col-span-2 md:col-span-3">
                  <Detail label="Description" value={event.description} />
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

function Detail({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div>
      <span className="text-gray-400">{label}: </span>
      <span className={`text-gray-800 ${mono ? "font-mono" : ""} break-all`}>
        {value}
      </span>
    </div>
  );
}
