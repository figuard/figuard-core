import { useQuery } from "@tanstack/react-query";
import { getSpendTree } from "../api/ledger";

export function useSpendTree(budgetId: string | undefined) {
  return useQuery({
    queryKey: ["spend-tree", budgetId],
    queryFn: () => getSpendTree(budgetId!),
    enabled: !!budgetId,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  });
}
