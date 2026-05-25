import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { createBudget, createDelegationToken } from "../api/budgets";
import type { BudgetTemplate } from "./BudgetTemplateModal";

interface Props {
  template: BudgetTemplate | null;
  onClose: () => void;
  onBack?: () => void;
}

const TEMPLATE_COLORS: Record<string, string> = {
  "single-agent":   "bg-blue-50 border-blue-200 text-blue-800",
  "llm-tracking":   "bg-violet-50 border-violet-200 text-violet-800",
  "multi-category": "bg-amber-50 border-amber-200 text-amber-800",
  "agent-fleet":    "bg-emerald-50 border-emerald-200 text-emerald-800",
  "rate-limiter":   "bg-red-50 border-red-200 text-red-800",
};

const isFleet = (t: BudgetTemplate | null) => t?.id === "agent-fleet";

export function NewBudgetModal({ template, onClose, onBack }: Props) {
  const fleet = isFleet(template);
  const d = template?.defaults ?? {};
  const startUnit = !!d.unit && !d.currency;

  const [userId, setUserId] = useState("");
  const [totalLimit, setTotalLimit] = useState(d.totalLimit ?? "");
  const [currency, setCurrency] = useState(d.currency ?? "USD");
  const [unit, setUnit] = useState(d.unit ?? "");
  const [useUnit, setUseUnit] = useState(startUnit);
  const [maxTx, setMaxTx] = useState("");
  const [intentContext, setIntentContext] = useState(d.intentContext ?? "");
  const [expiresHours, setExpiresHours] = useState(d.expiresHours ?? "24");

  // Fleet-only: per-worker cap fields
  const [workerLimit, setWorkerLimit] = useState("");
  const [workerCategory, setWorkerCategory] = useState("default");

  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: async () => {
      const now = new Date();
      now.setHours(now.getHours() + parseInt(expiresHours || "24", 10));

      const budget = await createBudget({
        userId: userId.trim() || "dashboard-user",
        totalLimit,
        ...(useUnit
          ? { unit: unit.trim() || "units" }
          : { currency: currency.trim() || "USD" }),
        ...(maxTx.trim() ? { maxTransactionQuantity: maxTx.trim() } : {}),
        ...(intentContext.trim() ? { intentContext: intentContext.trim() } : {}),
        expiresAt: now.toISOString(),
      });

      // For fleet budgets: optionally create a first delegation token so the
      // post-creation banner can show what workers should receive.
      let delegationToken = null;
      if (fleet && workerLimit.trim()) {
        const cap = parseFloat(workerLimit);
        if (!isNaN(cap) && cap > 0) {
          delegationToken = await createDelegationToken(budget.id, {
            label: "example-worker",
            caps: [{ category: workerCategory.trim() || "default", limit: cap }],
          });
        }
      }

      return { budget, delegationToken };
    },
    onSuccess: ({ budget, delegationToken }) => {
      queryClient.invalidateQueries({ queryKey: ["budgets"] });
      queryClient.invalidateQueries({ queryKey: ["budgets-count"] });
      queryClient.invalidateQueries({ queryKey: ["delegation-tokens", budget.id] });
      onClose();
      navigate(`/budgets/${budget.id}`, {
        state: {
          justCreated: true,
          templateId: template?.id ?? null,
          templateName: template?.name ?? null,
          nextStep: template?.nextStep ?? null,
          // Fleet parent token — for orchestrator use only
          sessionToken: budget.tokens?.[0]?.sessionToken ?? null,
          // First example worker token — hand to worker agents
          workerSessionToken: delegationToken?.sessionToken ?? null,
          workerTokenId: delegationToken?.id ?? null,
        },
      });
    },
  });

  const limitNum = parseFloat(totalLimit);
  const isValid =
    !isNaN(limitNum) &&
    limitNum > 0 &&
    (useUnit ? unit.trim().length > 0 : currency.trim().length > 0);

  const bannerColor = template
    ? (TEMPLATE_COLORS[template.id] ?? "bg-gray-50 border-gray-200 text-gray-800")
    : "";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="w-full max-w-sm rounded-xl bg-white shadow-xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4 shrink-0">
          <div className="flex items-center gap-2">
            {onBack && (
              <button
                onClick={onBack}
                className="text-gray-400 hover:text-gray-600 transition-colors"
                title="Back to templates"
              >
                <svg className="w-4 h-4" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2">
                  <polyline points="10,4 6,8 10,12" />
                </svg>
              </button>
            )}
            <h2 className="text-sm font-semibold text-gray-900">New Budget</h2>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
          >
            ✕
          </button>
        </div>

        <div className="overflow-y-auto">
          {/* Template context banner */}
          {template && (
            <div className={`mx-5 mt-4 rounded-lg border px-3 py-2.5 ${bannerColor}`}>
              <p className="text-xs font-semibold mb-0.5">{template.name}</p>
              <p className="text-xs opacity-80 leading-snug">{template.tagline}</p>
            </div>
          )}

          {/* Fleet hierarchy diagram */}
          {fleet && (
            <div className="mx-5 mt-3 rounded-lg border border-emerald-100 bg-emerald-50 px-3 py-2.5">
              <div className="flex items-center gap-0 text-xs text-emerald-800">
                <span className="rounded bg-emerald-200 px-2 py-0.5 font-semibold">Fleet budget</span>
                <span className="mx-1.5 text-emerald-400">→ creates →</span>
                <span className="rounded bg-emerald-200 px-2 py-0.5 font-semibold">Delegation tokens</span>
                <span className="mx-1.5 text-emerald-400">→ given to →</span>
                <span className="rounded bg-emerald-200 px-2 py-0.5 font-semibold">Workers</span>
              </div>
              <p className="text-xs text-emerald-700 mt-1.5 leading-snug">
                The fleet budget holds the total pool. Each worker gets its own delegation token with an individual cap. Both limits are enforced on every authorize call.
              </p>
            </div>
          )}

          {/* ---- Section: Budget / Fleet Pool ---- */}
          <div className="px-5 pt-4 pb-2 space-y-4">
            {fleet && (
              <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
                Fleet pool
              </p>
            )}

            {/* User ID */}
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">
                {fleet ? "Orchestrator / fleet ID" : "User ID"}
                <InfoTip text="An identifier for the owner of this budget — your user ID, agent name, or any label. Not validated by FiGuard." />
              </label>
              <input
                type="text"
                placeholder={fleet ? "e.g. refund-fleet or orchestrator-1" : "e.g. user_123 or agent-fleet"}
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>

            {/* Total limit */}
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">
                {fleet ? "Total fleet limit" : "Total limit"}{" "}
                <span className="text-red-500">*</span>
                <InfoTip text={
                  fleet
                    ? "The total across all workers combined. Once this is consumed, no worker can authorize further — even if individual caps have room."
                    : "The maximum quantity this budget can authorize in total. Once consumed, all further authorize calls are denied."
                } />
              </label>
              <input
                type="number"
                min="0.01"
                step="any"
                placeholder={fleet ? "e.g. 15000 (for a $15k fleet)" : "e.g. 500"}
                value={totalLimit}
                onChange={(e) => setTotalLimit(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>

            {/* Currency / Unit toggle */}
            <div>
              <div className="flex items-center justify-between mb-1">
                <label className="text-xs font-medium text-gray-700">
                  {useUnit ? "Unit" : "Currency"}
                  {useUnit ? (
                    <InfoTip text="A label for the resource being tracked — 'tokens', 'api_calls', 'requests', etc. No currency matching is applied." />
                  ) : (
                    <InfoTip text="3-letter currency code (USD, EUR, GBP). Authorize calls must match this currency or they are denied." />
                  )}
                </label>
                <button
                  type="button"
                  onClick={() => setUseUnit((v) => !v)}
                  className="text-xs text-blue-600 hover:text-blue-800 transition-colors"
                >
                  Switch to {useUnit ? "currency" : "unit (tokens/calls)"}
                </button>
              </div>
              {useUnit ? (
                <input
                  type="text"
                  placeholder="e.g. tokens, api_calls, requests"
                  value={unit}
                  onChange={(e) => setUnit(e.target.value)}
                  className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              ) : (
                <input
                  type="text"
                  maxLength={10}
                  value={currency}
                  onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                  className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              )}
            </div>

            {/* Max transaction (not shown for fleet — per-worker cap serves this role) */}
            {!fleet && (
              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">
                  Max per transaction{" "}
                  <span className="text-gray-400">(optional)</span>
                  <InfoTip text="A hard cap on any single authorize call. Denied with EXCEEDS_QUANTITY_LIMIT." />
                </label>
                <input
                  type="number"
                  min="0.01"
                  step="any"
                  placeholder="e.g. 500"
                  value={maxTx}
                  onChange={(e) => setMaxTx(e.target.value)}
                  className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            )}

            {/* Intent context */}
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">
                Intent context{" "}
                <span className="text-gray-400">(optional)</span>
                <InfoTip text="Plain-text description of what this budget is for. Recorded on every spend event for audit purposes." />
              </label>
              <input
                type="text"
                placeholder={fleet ? "e.g. Daily refund fleet — 2026-05-20" : "e.g. Q3 vendor invoice processing"}
                value={intentContext}
                onChange={(e) => setIntentContext(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>

            {/* Expiry */}
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">
                Expires in (hours)
                <InfoTip text="After this window the budget becomes EXPIRED and no further authorizations are accepted." />
              </label>
              <input
                type="number"
                min="1"
                step="1"
                value={expiresHours}
                onChange={(e) => setExpiresHours(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>

          {/* ---- Section: Per-worker cap (fleet only) ---- */}
          {fleet && (
            <div className="mx-5 mt-1 mb-4 rounded-lg border border-dashed border-emerald-300 bg-emerald-50/50 px-4 py-3 space-y-3">
              <div>
                <p className="text-xs font-semibold text-emerald-800 mb-0.5">
                  Per-worker cap{" "}
                  <span className="text-emerald-500 font-normal">(optional)</span>
                </p>
                <p className="text-xs text-emerald-700 leading-snug">
                  Sets how much each individual worker can spend. If provided, an example delegation token is created automatically so you can see the pattern.
                </p>
              </div>

              <div className="flex gap-2">
                <div className="flex-1">
                  <label className="block text-xs text-emerald-700 mb-1">
                    Limit per worker
                  </label>
                  <input
                    type="number"
                    min="0.01"
                    step="any"
                    placeholder="e.g. 10"
                    value={workerLimit}
                    onChange={(e) => setWorkerLimit(e.target.value)}
                    className="w-full rounded border border-emerald-200 bg-white px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-emerald-400"
                  />
                </div>
                <div className="w-28">
                  <label className="block text-xs text-emerald-700 mb-1">
                    Category
                    <InfoTip text="The spend category this cap applies to. Use 'default' for uncategorized budgets. Must match what your agent sends in claimedCategory." />
                  </label>
                  <input
                    type="text"
                    placeholder="default"
                    value={workerCategory}
                    onChange={(e) => setWorkerCategory(e.target.value)}
                    className="w-full rounded border border-emerald-200 bg-white px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-emerald-400"
                  />
                </div>
              </div>

              {workerLimit.trim() && (
                <p className="text-xs text-emerald-600">
                  Each worker can spend up to{" "}
                  <strong>{workerLimit} {useUnit ? (unit || "units") : currency}</strong> on{" "}
                  <strong>{workerCategory || "default"}</strong> before being denied.
                </p>
              )}
            </div>
          )}

          {mutation.isError && (
            <p className="text-xs text-red-600 px-5 pb-3">
              {mutation.error instanceof Error
                ? mutation.error.message
                : "Failed to create budget."}
            </p>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-2 border-t border-gray-100 px-5 py-4 shrink-0">
          <button
            onClick={onClose}
            className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={!isValid || mutation.isPending}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {mutation.isPending
              ? "Creating…"
              : fleet && workerLimit.trim()
              ? "Create fleet + worker token"
              : "Create budget"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Inline info tooltip
// ---------------------------------------------------------------------------

function InfoTip({ text }: { text: string }) {
  return (
    <span className="group relative inline-block ml-1 align-middle">
      <span className="cursor-default text-gray-400 hover:text-gray-600 text-[10px] font-bold border border-gray-300 rounded-full px-1 select-none">
        ?
      </span>
      <span className="pointer-events-none absolute left-1/2 -translate-x-1/2 bottom-full mb-1.5 w-56 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs text-gray-600 shadow-lg opacity-0 group-hover:opacity-100 transition-opacity z-10 leading-snug">
        {text}
      </span>
    </span>
  );
}
