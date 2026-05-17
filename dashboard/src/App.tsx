import {
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { isConfigured, apiFetch } from "./api/client";

function navItem(isActive: boolean) {
  return isActive
    ? "flex items-center rounded px-3 py-1.5 text-sm bg-blue-50 text-blue-700 font-medium"
    : "flex items-center rounded px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors";
}

// Per-budget sub-nav, shown when a budget route is active
function BudgetSubNav({ budgetId }: { budgetId: string }) {
  const subItem = (isActive: boolean) =>
    isActive
      ? "block rounded px-3 py-1.5 text-sm bg-blue-50 text-blue-700 font-medium"
      : "block rounded px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors";

  return (
    <div className="mt-1 ml-3 border-l border-gray-200 pl-3 space-y-0.5">
      <NavLink to={`/budgets/${budgetId}`} end className={({ isActive }) => subItem(isActive)}>
        Overview
      </NavLink>
      <NavLink to={`/budgets/${budgetId}/ledger`} className={({ isActive }) => subItem(isActive)}>
        Ledger
      </NavLink>
      <NavLink to={`/budgets/${budgetId}/tree`} className={({ isActive }) => subItem(isActive)}>
        Spend Tree
      </NavLink>
      <NavLink to={`/budgets/${budgetId}/replay`} className={({ isActive }) => subItem(isActive)}>
        Replay
      </NavLink>
    </div>
  );
}

export function App() {
  const location = useLocation();
  const navigate = useNavigate();

  // Redirect to Settings on first visit if no API key is configured
  useEffect(() => {
    if (!isConfigured() && location.pathname !== "/settings") {
      navigate("/settings", { replace: true });
    }
  }, []);

  const { data: failedCountData } = useQuery<{ failedCount: number }>({
    queryKey: ["webhook-failed-count"],
    queryFn: () => apiFetch("/api/v1/webhooks/deliveries/failed-count"),
    enabled: isConfigured(),
    refetchInterval: 30_000,
  });
  const failedCount = failedCountData?.failedCount ?? 0;

  // Extract budget ID from URL when viewing a specific budget
  const budgetMatch = location.pathname.match(/^\/budgets\/([^/]+)/);
  const currentBudgetId = budgetMatch ? budgetMatch[1] : undefined;

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Sidebar */}
      <aside className="w-52 shrink-0 flex flex-col border-r border-gray-200 bg-white">
        {/* Logo */}
        <div className="flex items-center px-4 py-4 border-b border-gray-100">
          <img src="/wordmark-nav-light.svg" alt="FiGuard" className="h-6 w-auto" />
        </div>

        <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-0.5">
          <NavLink to="/budgets" end className={({ isActive }) => navItem(isActive || !!currentBudgetId)}>
            Budgets
          </NavLink>

          {/* Per-budget sub-nav when inside a budget */}
          {currentBudgetId && <BudgetSubNav budgetId={currentBudgetId} />}

          <NavLink to="/users" className={({ isActive }) => navItem(isActive)}>
            Users
          </NavLink>

          <NavLink to="/webhooks" className={({ isActive }) => navItem(isActive)}>
            Webhooks
            {failedCount > 0 && (
              <span className="ml-auto rounded-full bg-red-500 px-1.5 py-0.5 text-xs font-semibold text-white leading-none">
                {failedCount > 99 ? "99+" : failedCount}
              </span>
            )}
          </NavLink>
        </nav>

        <div className="border-t border-gray-100 px-3 py-3">
          <NavLink
            to="/settings"
            className={({ isActive }) => navItem(isActive)}
          >
            Settings
          </NavLink>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-y-auto">
        <div className="px-8 py-6">
          {!isConfigured() && location.pathname !== "/settings" && (
            <div className="mb-4 rounded-lg border border-yellow-200 bg-yellow-50 px-4 py-3 text-sm text-yellow-800">
              No API key configured.{" "}
              <button
                onClick={() => navigate("/settings")}
                className="font-medium underline hover:text-yellow-900"
              >
                Open Settings
              </button>{" "}
              to connect to your FiGuard server.
            </div>
          )}
          <Outlet />
        </div>
      </main>
    </div>
  );
}
