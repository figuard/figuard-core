import { apiFetch } from "./client";
import type {
  BudgetResponse,
  BudgetFundingResponse,
  BudgetStatus,
  CreateBudgetRequest,
  DelegationTokenResponse,
  FundBudgetRequest,
  Page,
} from "../lib/types";

export async function getBudget(id: string): Promise<BudgetResponse> {
  return apiFetch<BudgetResponse>(`/api/v1/budgets/${id}`);
}

export interface ListBudgetsParams {
  page?: number;
  size?: number;
  status?: BudgetStatus | "";
  userId?: string;
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
  if (params.userId) qs.set("userId", params.userId);
  return apiFetch<Page<BudgetResponse>>(`/api/v1/budgets?${qs}`);
}

export async function listDelegationTokens(budgetId: string): Promise<DelegationTokenResponse[]> {
  return apiFetch<DelegationTokenResponse[]>(`/api/v1/budgets/${budgetId}/delegation-tokens`);
}

export interface CreateDelegationTokenRequest {
  label?: string;
  caps?: Array<{ category: string; limit: number }>;
}

export async function createDelegationToken(
  budgetId: string,
  payload: CreateDelegationTokenRequest,
): Promise<DelegationTokenResponse> {
  return apiFetch<DelegationTokenResponse>(
    `/api/v1/budgets/${budgetId}/delegation-tokens`,
    { method: "POST", body: JSON.stringify(payload) },
  );
}

export async function createBudget(
  payload: CreateBudgetRequest,
): Promise<BudgetResponse> {
  return apiFetch<BudgetResponse>("/api/v1/budgets", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export async function fundBudget(
  id: string,
  payload: FundBudgetRequest,
): Promise<BudgetFundingResponse> {
  return apiFetch<BudgetFundingResponse>(`/api/v1/budgets/${id}/fund`, {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export async function patchBudget(
  id: string,
  payload: {
    velocityMaxPerMinute?: number | null;
    velocityMaxAmountPerHour?: number | null;
    velocityMaxPerDay?: number | null;
  },
): Promise<BudgetResponse> {
  return apiFetch<BudgetResponse>(`/api/v1/budgets/${id}`, {
    method: "PATCH",
    body: JSON.stringify(payload),
  });
}
