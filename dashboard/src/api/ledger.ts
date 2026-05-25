import { apiFetch } from "./client";
import type {
  Page,
  SpendEventResponse,
  SpendDecision,
  SpendTreeResponse,
  ChainDetailResponse,
} from "../lib/types";

export interface LedgerParams {
  page?: number;
  size?: number;
  decision?: SpendDecision | "";
  traceId?: string;
}

export async function getLedger(
  budgetId: string,
  params: LedgerParams = {},
): Promise<Page<SpendEventResponse>> {
  const qs = new URLSearchParams();
  qs.set("page", String(params.page ?? 0));
  qs.set("size", String(params.size ?? 50));
  if (params.decision) qs.set("decision", params.decision);
  if (params.traceId?.trim()) qs.set("traceId", params.traceId.trim());

  return apiFetch<Page<SpendEventResponse>>(
    `/api/v1/budgets/${budgetId}/ledger?${qs}`,
  );
}

export async function getSpendTree(budgetId: string): Promise<SpendTreeResponse> {
  return apiFetch<SpendTreeResponse>(`/api/v1/budgets/${budgetId}/tree`);
}

export async function getChainDetail(chainRootEventId: string): Promise<ChainDetailResponse> {
  return apiFetch<ChainDetailResponse>(`/api/v1/events/${chainRootEventId}/chain`);
}
