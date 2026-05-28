# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| latest (main) | ✅ |

FiGuard is pre-1.0. We patch the main branch. If you are self-hosting, stay current with main or pin a recent release tag.

---

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Email: **security@figuard.io**

Include in your report:
- Description of the vulnerability and affected component
- Steps to reproduce (minimal reproducer preferred)
- Potential impact and attack surface
- Any suggested fix (optional — we appreciate but don't require it)

**Response SLAs:**

| Severity | Acknowledgement | Fix target |
|---|---|---|
| Critical (auth bypass, data leak) | 24 hours | 7 days |
| High (tenant isolation, privilege escalation) | 48 hours | 14 days |
| Medium / Low | 72 hours | 30 days |

We follow [coordinated disclosure](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure). We will notify you before publishing a fix and credit you in the changelog if you wish.

---

## Threat Model

FiGuard sits between your application and the resources your agents consume. The relevant threat actors and attack surfaces are:

**Rogue agent (the primary threat)**
An agent with a valid session token attempts to authorize more than it's allowed — either by exceeding quantity limits, spending in unauthorized categories, or replaying a successful authorization.

Mitigation: session tokens are single-purpose (scoped to one budget), quantity is checked against a pessimistically-locked ledger on every call, idempotency keys prevent replay, category allocations enforce per-category ceilings.

**Compromised API key**
An attacker with a tenant API key can read and write budget and event data for that tenant. They cannot access another tenant's data.

Mitigation: API keys are stored as SHA-256 hashes — the raw key is never persisted or recoverable after initial issuance. Key rotation is a first-class operation. Rate limiting is applied per-key.

**Session token leakage**
Session tokens (`st_...`) appear in HTTP headers and must be kept secret. If leaked, an attacker can authorize spends against the associated budget.

Mitigation: tokens are short-lived (budget expiry), can be cancelled by voiding or cancelling the budget, and are hashed before storage. The token hash is checked on every authorize call; the raw token is never written to the database.

**Cross-tenant data access**
A request from tenant A should never read or affect tenant B's budgets or events.

Mitigation: every service-layer query includes a `tenantId` filter. The `ApiKeyAuthFilter` resolves the tenant from the API key and stores it in `SecurityContext`; the service layer reads the tenant from context, never from the request body. A tenant cannot forge a different tenant ID.

**Webhook endpoint attacks**
Webhook deliveries could be forged to inject false spend events into a downstream system.

Mitigation: every webhook payload is signed with HMAC-SHA256 using a per-endpoint secret. Secrets are encrypted at rest with AES-256-GCM. Verify the `X-FiGuard-Signature` header before processing webhooks.

---

## Security Design

### Authentication

- **API keys** — `fg_live_...` format. Stored as SHA-256(key). Raw key returned once at creation; never stored, never logged. Rate-limited per key. Revocation is immediate — invalidates all in-flight requests.
- **Session tokens** — `st_...` format. Passed as `X-Session-Token` header. Resolved to a budget on each request. Short-lived (tied to budget expiry). Hashed before storage.

### Authorization and isolation

- Every budget and event is scoped to a tenant. The tenant is resolved from the API key, not the request body — callers cannot forge a different tenant context.
- Session tokens are further scoped to a single budget. A token cannot authorize against a different budget even within the same tenant.

### Data integrity

- Budget enforcement uses **pessimistic row-level locks** (`SELECT ... FOR UPDATE`) to prevent concurrent overdraw. Two concurrent authorizations for the same budget cannot both succeed if only one fits within the remaining capacity.
- The spend ledger is **append-only**. Events are never deleted or updated in place — status transitions (AUTHORIZED → CONFIRMED / FAILED / VOIDED) create new state, not overwrites.
- Idempotency keys are enforced at the database level with a unique index. A second request with the same key within the dedup window returns the original response without creating a new event.

### Secrets and keys at rest

| Secret | Storage | Encryption |
|---|---|---|
| API key | SHA-256 hash | One-way — not reversible |
| Session token | SHA-256 hash | One-way — not reversible |
| Webhook signing secret | Encrypted column | AES-256-GCM, key in env |
| Database credentials | Environment variable | Host-managed (secrets manager) |

### Transport

- All endpoints require TLS. The shared sandbox enforces HTTPS. Self-hosted deployments are responsible for TLS termination.
- No credentials are sent in query parameters. API keys are in the `X-Agent-Budget-Key` header; session tokens are in `X-Session-Token`.

### Logging

- Raw API keys and session tokens are never written to logs. Only key prefixes (e.g. `fg_live_abc...`) and token prefixes appear in structured log output.
- `latencyMs` and decision outcomes are logged per authorization for observability. No PII from the request body (description, metadata) is logged at INFO level or above.

---

## Scope

**In scope for vulnerability reports:**
- Authentication bypass (API key or session token accepted without valid credentials)
- Tenant isolation failures (one tenant's data readable or writable by another)
- Authorization bypass (agent spending past its budget or allocation limit)
- Budget limit enforcement race conditions (concurrent overdraw)
- Idempotency failures (same key, different outcomes)
- Webhook signature bypass
- SQL injection or other injection vulnerabilities
- Sensitive data (raw keys, tokens, secrets) exposed in responses or logs

**Out of scope:**
- Denial of service against the shared public sandbox
- Rate limit bypass on the sandbox (the sandbox has relaxed limits by design)
- Vulnerabilities in transitive dependencies that do not affect FiGuard's attack surface
- Social engineering of FiGuard team members
- Security issues in self-hosted deployments caused by misconfiguration (e.g. no TLS)

---

## Self-Hosting Security Checklist

If you are running FiGuard in your own infrastructure:

- [ ] Set `WEBHOOK_SECRET_KEY` (32-byte AES key) in your environment — do not leave blank
- [ ] Use TLS termination in front of the FiGuard container
- [ ] Rotate your API keys regularly — `POST /api/v1/api-keys/{id}/rotate`
- [ ] Restrict inbound access to the FiGuard API to your application network
- [ ] Enable alerting on `figuard.authorize.denied` counter spikes — anomalous denial rates indicate either a misconfigured limit or an adversarial agent
- [ ] Use secrets manager (AWS Secrets Manager, GCP Secret Manager, Vault) for database credentials and webhook secret key — do not hardcode in environment files checked into version control

---

## Dependency Scanning

FiGuard's server dependencies are tracked in `pom.xml`. We use Dependabot for automated CVE alerts on the GitHub repository. The Python and TypeScript SDKs have no production dependencies beyond the standard library.

To audit dependencies in a self-hosted deployment:
```bash
# Server (Maven)
mvn dependency:tree
mvn org.owasp:dependency-check-maven:check

# Python SDK
pip install pip-audit
pip-audit -r sdk/python/requirements.txt

# TypeScript SDK
npm audit --prefix sdk/typescript
```
