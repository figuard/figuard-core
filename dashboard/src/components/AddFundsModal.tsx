import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { fundBudget } from "../api/budgets";
import { formatAmount } from "../lib/format";
import type { BudgetResponse, FundingOperation } from "../lib/types";

interface Props {
  budget: BudgetResponse;
  onClose: () => void;
}

export function AddFundsModal({ budget, onClose }: Props) {
  const [amount, setAmount] = useState("");
  const [operation, setOperation] = useState<FundingOperation>("CREDIT");
  const [reason, setReason] = useState("");

  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () =>
      fundBudget(budget.id, {
        operation,
        amount: parseFloat(amount),
        reason: reason.trim() || undefined,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["budget", budget.id] });
      queryClient.invalidateQueries({ queryKey: ["budgets"] });
      onClose();
    },
  });

  const fmt = (n: number) => formatAmount(n, budget.currency, budget.unit);
  const parsed = parseFloat(amount);
  const isValid = !isNaN(parsed) && parsed > 0;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="w-full max-w-sm rounded-xl bg-white shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4">
          <h2 className="text-sm font-semibold text-gray-900">Add Funds</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
          >
            ✕
          </button>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-4">
          {/* Current balance info */}
          <div className="rounded-lg bg-gray-50 px-4 py-3 text-xs text-gray-500 space-y-1">
            <div className="flex justify-between">
              <span>Current limit</span>
              <span className="font-medium text-gray-700">{fmt(budget.totalLimit)}</span>
            </div>
            <div className="flex justify-between">
              <span>Available</span>
              <span className="font-medium text-gray-700">{fmt(budget.availableQuantity)}</span>
            </div>
            <div className="flex justify-between">
              <span>Spent</span>
              <span className="font-medium text-gray-700">{fmt(budget.quantitySpent)}</span>
            </div>
          </div>

          {/* Operation */}
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Operation
            </label>
            <select
              value={operation}
              onChange={(e) => setOperation(e.target.value as FundingOperation)}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="CREDIT">CREDIT — add to total limit</option>
              <option value="DEBIT">DEBIT — reduce total limit</option>
              <option value="RESET">RESET — set total limit to exact amount</option>
              <option value="RESET_SPENT">RESET_SPENT — new billing period</option>
            </select>
          </div>

          {/* Amount */}
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Amount ({budget.unit ?? budget.currency})
            </label>
            <input
              type="number"
              min="0.01"
              step="any"
              placeholder="e.g. 500"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Reason */}
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Reason <span className="text-gray-400">(optional)</span>
            </label>
            <input
              type="text"
              placeholder="e.g. Q3 budget top-up"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {mutation.isError && (
            <p className="text-xs text-red-600">
              {mutation.error instanceof Error
                ? mutation.error.message
                : "Operation failed. Check the amount and try again."}
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
            {mutation.isPending ? "Processing…" : "Apply"}
          </button>
        </div>
      </div>
    </div>
  );
}
