import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { createBudget } from "../api/budgets";

interface Props {
  onClose: () => void;
}

export function NewBudgetModal({ onClose }: Props) {
  const [userId, setUserId] = useState("");
  const [totalLimit, setTotalLimit] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [maxTx, setMaxTx] = useState("");
  const [intentContext, setIntentContext] = useState("");
  const [expiresHours, setExpiresHours] = useState("24");

  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: () => {
      const now = new Date();
      now.setHours(now.getHours() + parseInt(expiresHours || "24", 10));
      return createBudget({
        userId: userId.trim() || "dashboard-user",
        totalLimit,
        currency,
        ...(maxTx.trim() ? { maxTransactionQuantity: maxTx.trim() } : {}),
        ...(intentContext.trim() ? { intentContext: intentContext.trim() } : {}),
        expiresAt: now.toISOString(),
      });
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["budgets"] });
      onClose();
      navigate(`/budgets/${data.id}`);
    },
  });

  const limitNum = parseFloat(totalLimit);
  const isValid = !isNaN(limitNum) && limitNum > 0 && currency.trim().length > 0;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="w-full max-w-sm rounded-xl bg-white shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4">
          <h2 className="text-sm font-semibold text-gray-900">New Budget</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
          >
            ✕
          </button>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-4">
          {/* User ID */}
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              User ID
            </label>
            <input
              type="text"
              placeholder="e.g. user_123 or agent-fleet"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Total limit + currency */}
          <div className="flex gap-2">
            <div className="flex-1">
              <label className="block text-xs font-medium text-gray-700 mb-1">
                Total limit <span className="text-red-500">*</span>
              </label>
              <input
                type="number"
                min="0.01"
                step="any"
                placeholder="e.g. 1000"
                value={totalLimit}
                onChange={(e) => setTotalLimit(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div className="w-24">
              <label className="block text-xs font-medium text-gray-700 mb-1">
                Currency
              </label>
              <input
                type="text"
                maxLength={10}
                value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>

          {/* Max transaction */}
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Max per transaction <span className="text-gray-400">(optional)</span>
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

          {/* Intent context */}
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Intent context <span className="text-gray-400">(optional)</span>
            </label>
            <input
              type="text"
              placeholder="e.g. Q3 vendor invoice processing"
              value={intentContext}
              onChange={(e) => setIntentContext(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Expiry */}
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Expires in (hours)
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

          {mutation.isError && (
            <p className="text-xs text-red-600">
              {mutation.error instanceof Error
                ? mutation.error.message
                : "Failed to create budget."}
            </p>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-2 border-t border-gray-100 px-5 py-4">
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
            {mutation.isPending ? "Creating…" : "Create budget"}
          </button>
        </div>
      </div>
    </div>
  );
}
