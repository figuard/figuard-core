import type { SpendDecision } from "../lib/types";
import type { LedgerParams } from "../api/ledger";

const DECISIONS: SpendDecision[] = [
  "AUTHORIZED",
  "CONFIRMED",
  "DENIED",
  "FAILED",
  "VOIDED",
];

interface Props {
  params: LedgerParams;
  onChange: (next: LedgerParams) => void;
}

export function FilterSidebar({ params, onChange }: Props) {
  function toggleDecision(d: SpendDecision) {
    onChange({
      ...params,
      page: 0,
      decision: params.decision === d ? "" : d,
    });
  }

  return (
    <aside className="w-48 shrink-0 space-y-5">
      <div>
        <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
          Decision
        </h3>
        <div className="space-y-1">
          {DECISIONS.map((d) => (
            <label key={d} className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={params.decision === d}
                onChange={() => toggleDecision(d)}
                className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="text-sm text-gray-700">{d}</span>
            </label>
          ))}
        </div>
      </div>

      <div>
        <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
          Trace ID
        </h3>
        <input
          type="text"
          value={params.traceId ?? ""}
          onChange={(e) =>
            onChange({ ...params, page: 0, traceId: e.target.value })
          }
          placeholder="run_abc123…"
          className="w-full rounded border border-gray-300 px-2 py-1.5 text-sm text-gray-700 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent font-mono"
        />
      </div>

      {(params.decision || params.traceId) && (
        <button
          onClick={() => onChange({ page: 0, size: params.size })}
          className="text-xs text-blue-600 hover:text-blue-800 underline"
        >
          Clear filters
        </button>
      )}
    </aside>
  );
}
