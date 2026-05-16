import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./App";
import { BudgetList } from "./pages/BudgetList";
import { BudgetOverview } from "./pages/BudgetOverview";
import { CustomerView } from "./pages/CustomerView";
import { Ledger } from "./pages/Ledger";
import { Replay } from "./pages/Replay";
import { SpendTree } from "./pages/SpendTree";
import { Settings } from "./pages/Settings";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: true,
    },
  },
});

const root = document.getElementById("root");
if (!root) throw new Error("Root element not found");

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename={import.meta.env.BASE_URL?.replace(/\/$/, "") || ""}>
        <Routes>
          <Route path="/" element={<App />}>
            <Route index element={<Navigate to="/budgets" replace />} />
            <Route path="budgets" element={<BudgetList />} />
            <Route path="budgets/:id" element={<BudgetOverview />} />
            <Route path="budgets/:id/ledger" element={<Ledger />} />
            <Route path="budgets/:id/tree" element={<SpendTree />} />
            <Route path="budgets/:id/replay" element={<Replay />} />
            <Route path="users" element={<CustomerView />} />
            <Route path="settings" element={<Settings />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
