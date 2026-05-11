import {
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { isConfigured } from "./api/client";

function navItem(isActive: boolean) {
  return isActive
    ? "flex items-center gap-2 rounded px-3 py-1.5 text-sm bg-blue-50 text-blue-700 font-medium"
    : "flex items-center gap-2 rounded px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors";
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
    </div>
  );
}

export function App() {
  const location = useLocation();
  const navigate = useNavigate();

  // Extract budget ID from URL when viewing a specific budget
  const budgetMatch = location.pathname.match(/^\/budgets\/([^/]+)/);
  const currentBudgetId = budgetMatch ? budgetMatch[1] : undefined;

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Sidebar */}
      <aside className="w-52 shrink-0 flex flex-col border-r border-gray-200 bg-white">
        {/* Logo */}
        <div className="flex items-center px-4 py-4 border-b border-gray-100">
          <span className="font-semibold text-gray-800 tracking-tight">FiGuard</span>
        </div>

        <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-0.5">
          <NavLink to="/budgets" end className={({ isActive }) => navItem(isActive || !!currentBudgetId)}>
            <span>📋</span> Budgets
          </NavLink>

          {/* Per-budget sub-nav when inside a budget */}
          {currentBudgetId && <BudgetSubNav budgetId={currentBudgetId} />}
        </nav>

        <div className="border-t border-gray-100 px-3 py-3">
          <NavLink
            to="/settings"
            className={({ isActive }) => navItem(isActive)}
          >
            <span>⚙</span> Settings
          </NavLink>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-5xl mx-auto px-6 py-6">
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
