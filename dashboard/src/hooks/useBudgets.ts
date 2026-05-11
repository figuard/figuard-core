import { useQuery } from "@tanstack/react-query";
import { listBudgets, type ListBudgetsParams } from "../api/budgets";

export function useBudgets(params: ListBudgetsParams = {}) {
  return useQuery({
    queryKey: ["budgets", params],
    queryFn: () => listBudgets(params),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
    placeholderData: (prev) => prev,
  });
}
