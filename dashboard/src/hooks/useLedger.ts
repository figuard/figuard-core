import { useQuery } from "@tanstack/react-query";
import { getLedger, type LedgerParams } from "../api/ledger";

export function useLedger(budgetId: string | undefined, params: LedgerParams) {
  return useQuery({
    queryKey: ["ledger", budgetId, params],
    queryFn: () => getLedger(budgetId!, params),
    enabled: !!budgetId,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
    placeholderData: (prev) => prev, // keep previous page while loading next
  });
}
