import { apiFetch } from "./client";
import type { BudgetResponse, BudgetStatus, Page } from "../lib/types";

export async function getBudget(id: string): Promise<BudgetResponse> {
  return apiFetch<BudgetResponse>(`/api/v1/budgets/${id}`);
}

export interface ListBudgetsParams {
  page?: number;
  size?: number;
  status?: BudgetStatus | "";
}

export async function resumeBudget(id: string): Promise<BudgetResponse> {
  return apiFetch<BudgetResponse>(`/api/v1/budgets/${id}/resume`, { method: "POST" });
}

export async function listBudgets(
  params: ListBudgetsParams = {},
): Promise<Page<BudgetResponse>> {
  const qs = new URLSearchParams();
  qs.set("page", String(params.page ?? 0));
  qs.set("size", String(params.size ?? 20));
  if (params.status) qs.set("status", params.status);
  return apiFetch<Page<BudgetResponse>>(`/api/v1/budgets?${qs}`);
}
