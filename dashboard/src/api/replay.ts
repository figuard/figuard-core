import { apiFetch } from "./client";
import type {
  BudgetReplayResponse,
  TimelineResponse,
  CounterfactualReplayResponse,
} from "../lib/types";

export interface ReplayParams {
  from?: string;
  until?: string;
  includeDenied?: boolean;
  includeStateSnapshots?: boolean;
  pageSize?: number;
  pageToken?: string;
}

export interface CounterfactualParams {
  totalLimit?: number;
  maxTransactionQuantity?: number;
  allocations?: Array<{ category: string; limit: number }>;
  from?: string;
  until?: string;
}

export async function getTimeline(
  budgetId: string,
  params: Pick<ReplayParams, "from" | "until"> = {},
): Promise<TimelineResponse> {
  const qs = new URLSearchParams();
  if (params.from)  qs.set("from",  params.from);
  if (params.until) qs.set("until", params.until);
  const q = qs.toString();
  return apiFetch<TimelineResponse>(
    `/api/v1/budgets/${budgetId}/replay/timeline${q ? `?${q}` : ""}`,
  );
}

export async function getFullReplay(
  budgetId: string,
  params: ReplayParams = {},
): Promise<BudgetReplayResponse> {
  const qs = new URLSearchParams();
  qs.set("includeDenied",        String(params.includeDenied        ?? true));
  qs.set("includeStateSnapshots", String(params.includeStateSnapshots ?? true));
  qs.set("pageSize",              String(Math.min(params.pageSize ?? 100, 500)));
  if (params.from)       qs.set("from",       params.from);
  if (params.until)      qs.set("until",      params.until);
  if (params.pageToken)  qs.set("pageToken",  params.pageToken);
  return apiFetch<BudgetReplayResponse>(
    `/api/v1/budgets/${budgetId}/replay?${qs}`,
  );
}

export async function runCounterfactual(
  budgetId: string,
  params: CounterfactualParams,
): Promise<CounterfactualReplayResponse> {
  const policy: Record<string, unknown> = {};
  if (params.totalLimit != null)             policy["totalLimit"]             = params.totalLimit;
  if (params.maxTransactionQuantity != null) policy["maxTransactionQuantity"] = params.maxTransactionQuantity;
  if (params.allocations?.length)            policy["allocations"]            = params.allocations;

  const body: Record<string, unknown> = { hypotheticalPolicy: policy };
  if (params.from)  body["from"]  = params.from;
  if (params.until) body["until"] = params.until;

  return apiFetch<CounterfactualReplayResponse>(
    `/api/v1/budgets/${budgetId}/replay/counterfactual`,
    { method: "POST", body: JSON.stringify(body) },
  );
}
