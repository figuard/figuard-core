# Contributing to the FiGuard Dashboard

The dashboard is intentionally simple — each page is standalone and all styling
uses plain Tailwind utilities. You don't need to know any design system to contribute.

## Getting started

```bash
cd dashboard
npm install
npm run dev
# Open http://localhost:5173 — enter your FiGuard API URL + key in Settings
```

You need a running FiGuard server. The quickest way is Docker Compose from the repo root:

```bash
docker compose up
```

## Project layout

```
src/
  api/        HTTP layer — one file per resource (budgets, ledger). Swap here for TS SDK.
  components/ Reusable UI components. Each is a single file with no global state.
  hooks/      React Query wrappers (useBudget, useLedger, useSpendTree).
  lib/        Pure utilities: types.ts, format.ts, colors.ts.
  pages/      One file per route: BudgetOverview, Ledger, SpendTree, Settings.
```

## Rules for contributions

- **Each page is standalone.** You can change `pages/Ledger.tsx` without understanding
  `pages/SpendTree.tsx`. Don't introduce cross-page dependencies.
- **No global state.** All data flows through React Query (server state) or local
  `useState` (ephemeral UI state like which row is expanded). Don't add a global store.
- **Color changes go in `lib/colors.ts`.** All badge colors, ring colors, tree depth
  colors live there. One file, full retheme.
- **Format changes go in `lib/format.ts`.** Currency formatting, date formatting,
  denial reason labels — all centralized.
- **TypeScript strict mode is enforced.** `npm run typecheck` must pass clean.
- **New API fields?** Add them to `lib/types.ts` first, then consume them.

## Adding a new component

1. Create `src/components/YourComponent.tsx`
2. Export a named function component with fully typed props
3. Use Tailwind utility classes only — no inline styles except for dynamic SVG math
4. Import colors from `lib/colors.ts`, formatting from `lib/format.ts`

## Running typecheck

```bash
npm run typecheck
```

## Building for production

```bash
npm run build
# Output in dist/ — static files, host anywhere
```
