import { useQuery, useMutation } from "@tanstack/react-query";
import {
  getTimeline,
  getFullReplay,
  runCounterfactual,
  type CounterfactualParams,
} from "../api/replay";

export function useTimeline(budgetId: string | undefined) {
  return useQuery({
    queryKey: ["replay-timeline", budgetId],
    queryFn: () => getTimeline(budgetId!),
    enabled: !!budgetId,
    staleTime: 30_000,
  });
}

export function useFullReplay(budgetId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ["replay-full", budgetId],
    queryFn: () => getFullReplay(budgetId!, { includeStateSnapshots: true, pageSize: 500 }),
    enabled: !!budgetId && enabled,
    staleTime: 30_000,
  });
}

export function useCounterfactual(budgetId: string | undefined) {
  return useMutation({
    mutationFn: (params: CounterfactualParams) => runCounterfactual(budgetId!, params),
  });
}
