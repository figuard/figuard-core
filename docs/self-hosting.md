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
[FiGuard] Demo API key: fg_live_demo
[FiGuard] Header: X-Agent-Budget-Key: fg_live_demo
=========================================
```

Verify it's healthy:

```bash
curl -s -H "X-Agent-Budget-Key: fg_live_demo" \
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
| `FIGUARD_SEED_DEMO_KEY` | `true` | Seeds `fg_live_demo` key on first boot |
| `WEBHOOK_SECRET_KEY` | insecure dev default | AES-256-GCM key for encrypting webhook secrets at rest. **Replace in production.** Generate with: `openssl rand -base64 32` |

For production, set `FIGUARD_SEED_DEMO_KEY=false`, rotate `SPRING_DATASOURCE_PASSWORD`, and set a real `WEBHOOK_SECRET_KEY`.

---

## Issuing your own API key

The `fg_live_demo` key is seeded for convenience and is shared across everyone who self-hosts. For any real use, create a scoped key:

```bash
curl -s -X POST http://localhost:8080/api/v1/api-keys \
  -H "X-Agent-Budget-Key: fg_live_demo" \
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
- Set `WEBHOOK_SECRET_KEY` to a 32-byte random key (`openssl rand -base64 32`). The default in `application.yml` is insecure and for local development only.
- The `postgres_data` volume is your ledger — back it up.
- The health check endpoint is `GET /actuator/health` (no auth required).

### Rate limiting

FiGuard does not include API-level rate limiting. For production deployments, configure rate limiting in your reverse proxy. Example nginx configuration:

```nginx
limit_req_zone $binary_remote_addr zone=figuard_authorize:10m rate=100r/s;

location /api/v1/authorize {
    limit_req zone=figuard_authorize burst=50 nodelay;
    proxy_pass http://figuard:8080;
}
```

Caddy and Traefik have equivalent rate limiting middleware. Applying stricter limits on `/api/v1/authorize` than on read endpoints is recommended — authorization is the hot path and the most likely target for runaway agents.

### Connection pool and Postgres limits

FiGuard defaults to a Hikari pool of **20 connections per instance** (`maximum-pool-size: 20` in `application.yml`). If you run multiple replicas, the total connection count is `replicas × 20`.

> **Warning:** If `total connections > postgres max_connections`, Spring Boot instances will **fail to start entirely** — they cannot acquire an initial connection from the pool. This is not a graceful degradation; the instance will not serve any requests. Check `max_connections` in your Postgres configuration (default is 100) before scaling out.

```sql
-- Check current Postgres connection limit
SHOW max_connections;

-- Check current active connections
SELECT count(*) FROM pg_stat_activity;
```

To run 3 replicas with the default pool size (60 connections), Postgres needs `max_connections >= 60` plus headroom for migrations, monitoring, and admin connections. A value of `max_connections = 100` leaves very little margin; set it to at least `replicas × pool_size × 1.5`. Alternatively, reduce `maximum-pool-size` in `application.yml` or use PgBouncer to multiplex connections.
