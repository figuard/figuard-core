import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "../api/client";

type DeliveryStatus = "PENDING" | "DELIVERED" | "FAILED";

interface WebhookDelivery {
  id: string;
  webhookConfigId: string | null;
  targetUrl: string | null;
  eventType: string;
  status: DeliveryStatus;
  responseStatus: number | null;
  responseBody: string | null;
  payload: Record<string, unknown> | null;
  attemptCount: number;
  deliveredAt: string | null;
  createdAt: string;
}

const STATUS_TABS: { label: string; value: DeliveryStatus | null }[] = [
  { label: "All", value: null },
  { label: "Failed", value: "FAILED" },
  { label: "Delivered", value: "DELIVERED" },
  { label: "Pending", value: "PENDING" },
];

function statusBadge(status: DeliveryStatus) {
  if (status === "DELIVERED")
    return (
      <span className="inline-flex items-center rounded-full bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700 ring-1 ring-green-600/20">
        Delivered
      </span>
    );
  if (status === "FAILED")
    return (
      <span className="inline-flex items-center rounded-full bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700 ring-1 ring-red-600/20">
        Failed
      </span>
    );
  return (
    <span className="inline-flex items-center rounded-full bg-yellow-50 px-2 py-0.5 text-xs font-medium text-yellow-700 ring-1 ring-yellow-600/20">
      Pending
    </span>
  );
}

function formatTime(iso: string) {
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function Webhooks() {
  const [activeTab, setActiveTab] = useState<DeliveryStatus | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const statusParam = activeTab ? `?status=${activeTab}` : "";

  const { data: deliveries = [], isLoading, isError } = useQuery<WebhookDelivery[]>({
    queryKey: ["webhook-deliveries", activeTab],
    queryFn: () => apiFetch<WebhookDelivery[]>(`/api/v1/webhooks/deliveries${statusParam}`),
    refetchInterval: 15_000,
  });

  const retryMutation = useMutation({
    mutationFn: (deliveryId: string) =>
      apiFetch<void>(`/api/v1/webhooks/deliveries/${deliveryId}/retry`, { method: "POST" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["webhook-deliveries"] });
      queryClient.invalidateQueries({ queryKey: ["webhook-failed-count"] });
    },
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-gray-900">Webhook Deliveries</h1>
        <p className="text-sm text-gray-500">Refreshes every 15 seconds</p>
      </div>

      {/* Status filter tabs */}
      <div className="flex gap-1 border-b border-gray-200">
        {STATUS_TABS.map((tab) => (
          <button
            key={String(tab.value)}
            onClick={() => setActiveTab(tab.value)}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              activeTab === tab.value
                ? "border-blue-600 text-blue-700"
                : "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Failed to load deliveries.
        </div>
      )}

      {isLoading && (
        <div className="flex items-center justify-center h-40 text-gray-400 text-sm">
          Loading…
        </div>
      )}

      {!isLoading && deliveries.length === 0 && (
        <div className="flex items-center justify-center h-40 text-gray-400 text-sm">
          No deliveries{activeTab ? ` with status ${activeTab}` : ""}.
        </div>
      )}

      {!isLoading && deliveries.length > 0 && (
        <div className="rounded-xl border border-gray-200 bg-white shadow-sm overflow-hidden">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-gray-100 bg-gray-50">
                <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">Time</th>
                <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">Event</th>
                <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">Destination</th>
                <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">Status</th>
                <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">HTTP</th>
                <th className="px-4 py-2.5 text-xs font-semibold text-gray-500 uppercase tracking-wide">Attempts</th>
                <th className="px-4 py-2.5"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {deliveries.map((d) => (
                <>
                  <tr
                    key={d.id}
                    className="hover:bg-gray-50 cursor-pointer"
                    onClick={() => setExpanded(expanded === d.id ? null : d.id)}
                  >
                    <td className="px-4 py-3 text-xs text-gray-500 whitespace-nowrap font-mono">
                      {formatTime(d.createdAt)}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-800 whitespace-nowrap">
                      {d.eventType}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500 max-w-xs truncate font-mono">
                      {d.targetUrl ?? "config"}
                      {d.targetUrl && (
                        <span className="ml-1 text-gray-400">(direct)</span>
                      )}
                    </td>
                    <td className="px-4 py-3">{statusBadge(d.status)}</td>
                    <td className="px-4 py-3 text-xs font-mono text-gray-500">
                      {d.responseStatus ?? "—"}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500">{d.attemptCount}</td>
                    <td className="px-4 py-3 text-right">
                      {d.status === "FAILED" && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            retryMutation.mutate(d.id);
                          }}
                          disabled={retryMutation.isPending}
                          className="rounded px-2.5 py-1 text-xs font-medium text-blue-700 bg-blue-50 hover:bg-blue-100 disabled:opacity-50 transition-colors"
                        >
                          Retry
                        </button>
                      )}
                    </td>
                  </tr>

                  {/* Expanded detail row */}
                  {expanded === d.id && (
                    <tr key={`${d.id}-detail`} className="bg-gray-50">
                      <td colSpan={7} className="px-4 py-4">
                        <div className="grid grid-cols-2 gap-4">
                          <div>
                            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">
                              Payload sent
                            </p>
                            <pre className="rounded-lg bg-gray-900 text-green-300 text-xs p-3 overflow-auto max-h-64 whitespace-pre-wrap break-all">
                              {d.payload
                                ? JSON.stringify(d.payload, null, 2)
                                : "—"}
                            </pre>
                          </div>
                          <div>
                            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">
                              Response body
                            </p>
                            <pre className="rounded-lg bg-gray-900 text-green-300 text-xs p-3 overflow-auto max-h-64 whitespace-pre-wrap break-all">
                              {d.responseBody ?? "—"}
                            </pre>
                          </div>
                        </div>
                        {d.deliveredAt && (
                          <p className="mt-2 text-xs text-gray-400">
                            Delivered at {formatTime(d.deliveredAt)}
                          </p>
                        )}
                      </td>
                    </tr>
                  )}
                </>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
