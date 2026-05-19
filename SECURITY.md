# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| latest (main) | ✅ |

FiGuard is pre-1.0. We patch the main branch. If you are self-hosting, stay current with main or pin a recent release tag.

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Email: **security@figuard.io**

Include:
- A description of the vulnerability
- Steps to reproduce
- Potential impact
- Any suggested fix (optional)

We will acknowledge within 48 hours and aim to ship a fix within 14 days for critical issues.

## Scope

In scope:
- Authentication bypass (API key or session token)
- Tenant isolation failures (one tenant reading or affecting another tenant's data)
- Authorization bypass (agent spending beyond its budget or allocation limits)
- Webhook signature forgery
- SQL injection or other injection attacks
- Sensitive data exposure (secrets, tokens, keys)

Out of scope:
- Denial of service against the shared sandbox (use rate limits for that)
- Issues in dependencies not affecting FiGuard's security posture directly
- Social engineering

## Security Design Notes

- API keys are stored as SHA-256 hashes — raw keys are never persisted
- Session tokens (`st_`) are hashed before storage and never logged
- Webhook secrets are encrypted at rest (AES-256-GCM)
- All budget enforcement uses pessimistic locks to prevent concurrent overdraw
- Tenant isolation is enforced at the service layer on every query
