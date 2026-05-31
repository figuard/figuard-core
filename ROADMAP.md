# FiGuard Roadmap

Items marked **v1** are planned for the next release. Everything else is on the backlog without a committed timeline.

## v1

- **Scoped tokens** (`sc_` prefix) — derived session tokens with hard restrictions on action types, categories, and max transaction amount; for untrusted sub-agent delegation
- **Overdraft policies** — per-budget `REJECT` / `ALLOW_IF_AVAILABLE` / `ALLOW_WITH_OVERDRAFT` modes

## Backlog

- **Row Level Security** — PostgreSQL RLS policies as a second enforcement layer for operators running FiGuard in shared multi-tenant mode
- **Velocity counter table** — replace live `COUNT`/`SUM` scans with an atomically-incremented counter table per budget per window, removing the `spend_events` scan from the authorize hot path at scale
- **API rate limiting** — per-API-key token bucket; returns 429 with `Retry-After`
- **Go SDK** — idiomatic Go client with context propagation
- **Helm chart** — production-grade Kubernetes deployment with configurable replicas, resource limits, and secret management

## Not planned

Features explicitly outside scope:

- Payment processing or fund movement
- General-purpose policy language (DSL)
- Adversarial-agent containment or prompt-injection defense
