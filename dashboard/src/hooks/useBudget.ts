import { useQuery } from "@tanstack/react-query";
import { getBudget } from "../api/budgets";

export function useBudget(id: string | undefined) {
  return useQuery({
    queryKey: ["budget", id],
    queryFn: () => getBudget(id!),
    enabled: !!id,
    refetchInterval: 30_000,      // poll every 30s in background
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  });
}
