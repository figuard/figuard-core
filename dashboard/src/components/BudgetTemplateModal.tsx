// Template definitions are exported so NewBudgetModal can read them for
// the "what to do next" banner shown after creation.

export interface BudgetTemplate {
  id: string;
  name: string;
  tagline: string;
  description: string;
  bullets: string[];
  nextStep: string;
  defaults: {
    currency?: string;
    unit?: string;
    totalLimit?: string;
    expiresHours?: string;
    intentContext?: string;
  };
}

export const BUDGET_TEMPLATES: BudgetTemplate[] = [
  {
    id: "single-agent",
    name: "Single agent task",
    tagline: "One agent, one goal, one spending limit",
    description:
      "Best for a standalone agent doing a discrete job — booking travel, processing a return, running a one-off analysis.",
    bullets: [
      "One budget → one session token",
      "Agent calls authorize → takes action → calls confirm",
      "Budget expires when the task window closes",
    ],
    nextStep:
      "Give the session token to your agent. Before each action: authorize(). After it succeeds: confirm(). If it fails: fail().",
    defaults: {
      currency: "USD",
      totalLimit: "500",
      expiresHours: "24",
    },
  },
  {
    id: "llm-tracking",
    name: "LLM / token quota",
    tagline: "Cap token consumption or API calls — no currency involved",
    description:
      "Use this when tracking a resource that isn't money — LLM tokens, external API calls, vector DB reads, or any countable unit.",
    bullets: [
      "Set unit to 'tokens', 'api_calls', or any label you choose",
      "No currency matching — purely quantity-based enforcement",
      "Authorize with requestedQuantity = expected token count",
    ],
    nextStep:
      "Pass the session token to your agent. Call authorize with requestedQuantity equal to the expected token count (e.g. 4096 tokens per call).",
    defaults: {
      unit: "tokens",
      totalLimit: "500000",
      expiresHours: "24",
    },
  },
  {
    id: "multi-category",
    name: "Multi-category agent",
    tagline: "One budget split into categories with independent caps",
    description:
      "Use this when one agent handles multiple spend types — flights vs hotels, or different API service tiers — and you need caps on each.",
    bullets: [
      "Total limit caps overall spend across all categories",
      "Allocations cap each category independently",
      "Denied if category limit OR total budget limit is exceeded",
    ],
    nextStep:
      "After creation, add allocations via the SDK: client.create_allocation(budget_id, category='flights', limit=300). Then give the session token to your agent.",
    defaults: {
      currency: "USD",
      totalLimit: "1000",
      expiresHours: "72",
      intentContext: "Multi-category agent budget",
    },
  },
  {
    id: "agent-fleet",
    name: "Agent fleet",
    tagline: "One shared budget for many parallel agents, each with its own cap",
    description:
      "Use this when an orchestrator spawns many workers drawing from the same pool — refund processing, batch LLM jobs, parallel scrapers. One fleet budget guards the total; delegation tokens guard each worker.",
    bullets: [
      "Fleet budget = the total pool (e.g. $15M for all refunds today)",
      "One delegation token per worker agent, with per-worker caps",
      "Both fleet-level and per-worker caps enforced on every authorize",
    ],
    nextStep:
      "Create a delegation token per worker: POST /budgets/{id}/delegation-tokens with {label, caps: [{category, limit}]}. Give each worker its token. Workers use it exactly like a regular session token.",
    defaults: {
      currency: "USD",
      totalLimit: "15000",
      expiresHours: "168",
      intentContext: "Agent fleet budget",
    },
  },
  {
    id: "rate-limiter",
    name: "API rate limiter",
    tagline: "Prevent a looping agent from hammering a downstream service",
    description:
      "Use this to cap how many requests an agent can make per minute, per hour, or per day — protecting an external API from a runaway loop.",
    bullets: [
      "Set velocityMaxPerMinute, /hour amount, or /day count after creation",
      "First violation fires a webhook alert automatically",
      "Works with unit-based budgets — no currency needed",
    ],
    nextStep:
      "After creation, set velocity limits: PATCH /budgets/{id} with velocityMaxPerMinute or velocityMaxPerDay. The budget will deny once the rate is exceeded and alert you via webhook.",
    defaults: {
      unit: "requests",
      totalLimit: "10000",
      expiresHours: "24",
    },
  },
];

// ---------------------------------------------------------------------------
// Template card icons — simple SVG paths, no external deps
// ---------------------------------------------------------------------------

const ICONS: Record<string, React.ReactNode> = {
  "single-agent": (
    <svg className="w-5 h-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75">
      <circle cx="10" cy="7" r="3" />
      <path d="M3 17c0-3.314 3.134-6 7-6s7 2.686 7 6" strokeLinecap="round" />
    </svg>
  ),
  "llm-tracking": (
    <svg className="w-5 h-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75">
      <rect x="3" y="5" width="14" height="10" rx="2" />
      <path d="M7 9h6M7 12h4" strokeLinecap="round" />
    </svg>
  ),
  "multi-category": (
    <svg className="w-5 h-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75">
      <rect x="3" y="3" width="6" height="6" rx="1" />
      <rect x="11" y="3" width="6" height="6" rx="1" />
      <rect x="3" y="11" width="6" height="6" rx="1" />
      <rect x="11" y="11" width="6" height="6" rx="1" />
    </svg>
  ),
  "agent-fleet": (
    <svg className="w-5 h-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75">
      <circle cx="10" cy="4" r="2" />
      <circle cx="4" cy="15" r="2" />
      <circle cx="16" cy="15" r="2" />
      <path d="M10 6v3M10 9l-4.5 4M10 9l4.5 4" strokeLinecap="round" />
    </svg>
  ),
  "rate-limiter": (
    <svg className="w-5 h-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75">
      <circle cx="10" cy="10" r="7" />
      <path d="M10 6v4l3 2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
};

const COLORS: Record<string, { bg: string; border: string; icon: string; bullet: string }> = {
  "single-agent":   { bg: "bg-blue-50",   border: "border-blue-200",   icon: "text-blue-600",   bullet: "bg-blue-400" },
  "llm-tracking":   { bg: "bg-violet-50", border: "border-violet-200", icon: "text-violet-600", bullet: "bg-violet-400" },
  "multi-category": { bg: "bg-amber-50",  border: "border-amber-200",  icon: "text-amber-600",  bullet: "bg-amber-400" },
  "agent-fleet":    { bg: "bg-emerald-50",border: "border-emerald-200",icon: "text-emerald-600",bullet: "bg-emerald-400" },
  "rate-limiter":   { bg: "bg-red-50",    border: "border-red-200",    icon: "text-red-600",    bullet: "bg-red-400" },
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

interface Props {
  onSelect: (template: BudgetTemplate) => void;
  onBlank: () => void;
  onClose: () => void;
}

export function BudgetTemplateModal({ onSelect, onBlank, onClose }: Props) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="w-full max-w-2xl rounded-xl bg-white shadow-xl flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4 shrink-0">
          <div>
            <h2 className="text-sm font-semibold text-gray-900">New Budget</h2>
            <p className="text-xs text-gray-400 mt-0.5">
              Choose a starting point — you can change any field before creating.
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none ml-4"
          >
            ✕
          </button>
        </div>

        {/* Template grid */}
        <div className="overflow-y-auto px-6 py-5 grid grid-cols-1 sm:grid-cols-2 gap-3">
          {BUDGET_TEMPLATES.map((tpl) => {
            const c = COLORS[tpl.id];
            return (
              <button
                key={tpl.id}
                onClick={() => onSelect(tpl)}
                className={`text-left rounded-xl border p-4 transition-all hover:shadow-md hover:-translate-y-0.5 focus:outline-none focus:ring-2 focus:ring-blue-400 ${c.bg} ${c.border}`}
              >
                {/* Icon + name */}
                <div className="flex items-center gap-2.5 mb-2">
                  <span className={c.icon}>{ICONS[tpl.id]}</span>
                  <span className="text-sm font-semibold text-gray-900">{tpl.name}</span>
                </div>

                {/* Tagline */}
                <p className="text-xs text-gray-500 mb-3 leading-snug">{tpl.tagline}</p>

                {/* Bullets */}
                <ul className="space-y-1.5">
                  {tpl.bullets.map((b) => (
                    <li key={b} className="flex items-start gap-2">
                      <span className={`mt-1.5 w-1.5 h-1.5 rounded-full shrink-0 ${c.bullet}`} />
                      <span className="text-xs text-gray-600 leading-snug">{b}</span>
                    </li>
                  ))}
                </ul>
              </button>
            );
          })}
        </div>

        {/* Footer */}
        <div className="border-t border-gray-100 px-6 py-3 shrink-0 flex items-center justify-between">
          <p className="text-xs text-gray-400">
            Not sure? Start with{" "}
            <button
              onClick={() => onSelect(BUDGET_TEMPLATES[0])}
              className="text-blue-600 hover:underline font-medium"
            >
              Single agent task
            </button>
            .
          </p>
          <button
            onClick={onBlank}
            className="text-xs text-gray-400 hover:text-gray-700 transition-colors"
          >
            Skip — blank form →
          </button>
        </div>
      </div>
    </div>
  );
}
