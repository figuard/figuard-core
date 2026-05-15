# Self-Hosting

FiGuard ships as a Docker Compose stack: a Spring Boot API server and a PostgreSQL 15 database. No Kubernetes, no external services, no managed dependencies.

---

## Prerequisites

- Docker 24+ and Docker Compose v2
- Git
- Python 3.9+ (to run examples against your local instance)

---

## Start in one command

```bash
git clone https://github.com/figuard/figuard-core
cd figuard-core
make run
```

The first build takes around two minutes — the JVM layer is cached after that. You'll see:

```
=========================================
[FiGuard] Ready at http://localhost:8080
[FiGuard] Dashboard: http://localhost:5173
[FiGuard] Demo API key: ab_live_demo
[FiGuard] Header: X-Agent-Budget-Key: ab_live_demo
=========================================
```

Verify it's healthy:

```bash
curl -s -H "X-Agent-Budget-Key: ab_live_demo" \
  http://localhost:8080/api/v1/budgets
# {"content":[],"totalElements":0,...}
```

---

## What's running

| Service | Port | What it does |
|---|---|---|
| `figuard` | 8080 | REST API — authorize, budget CRUD, ledger |
| `postgres` | 5432 | PostgreSQL 15 — append-only ledger, budget state |

The dashboard at port 5173 is the React UI served by the `figuard` container at `/ui`.

---

## Other make targets

```bash
make stop          # docker compose down (data preserved)
make reset         # docker compose down -v (wipes database)
make logs          # tail -f container logs
make test          # Python SDK unit tests (no container needed)
make test-live     # Python SDK live tests (container must be running)
```

---

## Environment variables

The container reads these at startup. Override them by editing `docker-compose.yml` or passing `-e` flags.

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/figuard` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `figuard` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | `figuard_local` | Database password |
| `FIGUARD_SEED_DEMO_KEY` | `true` | Seeds `ab_live_demo` key on first boot |

For production, set `FIGUARD_SEED_DEMO_KEY=false` and issue your own API keys via `POST /api/v1/api-keys`.

---

## Issuing your own API key

The `ab_live_demo` key is seeded for convenience and is shared across everyone who self-hosts. For any real use, create a scoped key:

```bash
curl -s -X POST http://localhost:8080/api/v1/api-keys \
  -H "X-Agent-Budget-Key: ab_live_demo" \
  -H "Content-Type: application/json" \
  -d '{"description": "my agent key"}' | jq .
```

The response contains `rawKey` — store it immediately, it's shown once. Use it as `X-Agent-Budget-Key` on all subsequent requests.

---

## Sandbox vs self-hosted

| | Sandbox (`sandbox.figuard.io`) | Self-hosted |
|---|---|---|
| Setup | None | `make run` |
| Key | `sb_live_demo` (shared, rate-limited) | Your own key |
| Data persistence | Resets periodically | Permanent in `postgres_data` volume |
| Production-ready | No | Add TLS + auth behind a reverse proxy |

---

## Production notes

- Put the API behind a reverse proxy (nginx, Caddy) with TLS termination. The container speaks plain HTTP.
- Set `FIGUARD_SEED_DEMO_KEY=false` and rotate `SPRING_DATASOURCE_PASSWORD`.
- The `postgres_data` volume is your ledger — back it up.
- The health check endpoint is `GET /actuator/health` (no auth required).
