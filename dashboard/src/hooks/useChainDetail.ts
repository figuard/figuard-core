import { useQuery } from "@tanstack/react-query";
import { getChainDetail } from "../api/ledger";

export function useChainDetail(chainRootEventId: string | undefined) {
  return useQuery({
    queryKey: ["chain-detail", chainRootEventId],
    queryFn: () => getChainDetail(chainRootEventId!),
    enabled: !!chainRootEventId,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  });
}
