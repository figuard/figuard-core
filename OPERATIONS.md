# FiGuard Operations Runbook

Reference for operators running FiGuard in production.

---

## Quick start

The fastest path to a running production instance:

```bash
# 1. Generate secrets
export DB_PASSWORD=$(openssl rand -base64 24)
export WEBHOOK_SECRET_KEY=$(openssl rand -base64 32)

# 2. Start PostgreSQL + FiGuard (pulls pre-built image from GHCR — no build step)
docker compose -f docker-compose.prod.yml up -d

# 3. Verify
curl http://localhost:8080/actuator/health
# → {"status":"UP"}
```

The compose file (`docker-compose.prod.yml`) uses `ghcr.io/figuard/figuard-core:latest`. It fails loudly if `DB_PASSWORD` or `WEBHOOK_SECRET_KEY` are unset. PostgreSQL data is persisted in a named volume (`postgres_data`).

FiGuard runs migrations on startup via Flyway — no manual SQL needed.

---

## Health check

```
GET /actuator/health
```

Returns `{"status":"UP"}` when the service and database are reachable. Safe to use as a load balancer or container health check endpoint. No authentication required.

---

## Metrics

```
GET /actuator/prometheus
```

No authentication required. Configure your Prometheus scrape target to `http://<host>:8080/actuator/prometheus`.

### Domain counters

In addition to Spring Boot's auto-emitted `http_server_requests_seconds`, FiGuard exposes these counters:

| Metric | Tags | Description |
|--------|------|-------------|
| `figuard.authorize.latency` | — | P50/P99 latency histogram for `POST /authorize` |
| `figuard.authorize.approved` | — | Count of AUTHORIZED decisions |
| `figuard.authorize.denied` | `denial_reason` | Count of DENIED decisions by reason code |
| `figuard.event.confirmed` | — | Count of spend events confirmed |
| `figuard.event.failed` | — | Count of spend events marked failed |
| `figuard.event.voided` | — | Count of spend events voided |
| `figuard.velocity.denied` | — | Count of first-in-window VELOCITY_LIMIT_EXCEEDED denials |
| `figuard.reservation.expired` | — | Count of reservations auto-voided on confirmation timeout |
| `figuard.sweep.runs` | `job` | Number of confirmation timeout sweep executions |
| `figuard.sweep.events_processed` | `job` | Stale AUTHORIZED events auto-voided per sweep |
| `figuard.sweep.pending_authorizations` | — | Live gauge: current in-flight AUTHORIZED events |
| `figuard.webhook.retry.attempts` | — | Total webhook retry attempts by sweep |
| `figuard.webhook.retry.recovered` | — | Deliveries that succeeded on retry |
| `figuard.webhook.retry.terminal` | — | Deliveries that exhausted all 10 attempts |
| `figuard.webhook.retry.renewal_alerts` | — | RENEWAL_TOKEN_DELIVERY_FAILED alerts fired |

### Useful Prometheus queries

```promql
# Authorization denial rate (last 5m)
rate(figuard_authorize_denied_total[5m])

# P99 authorization latency
histogram_quantile(0.99, rate(figuard_authorize_latency_seconds_bucket[5m]))

# HTTP error rate
rate(http_server_requests_seconds_count{status=~"5.."}[5m])

# Webhook terminal failures (deliveries that gave up)
increase(figuard_webhook_retry_terminal_total[1h])

# Velocity denial rate (first violations only, per budget)
rate(figuard_velocity_denied_total[5m])

# Reservation expiry rate (authorization timeouts not confirmed)
rate(figuard_reservation_expired_total[5m])

# Event lifecycle breakdown (confirms / voids / fails per minute)
rate(figuard_event_confirmed_total[1m])
rate(figuard_event_voided_total[1m])
rate(figuard_event_failed_total[1m])
```

### Alert rules (paste into Prometheus alerting rules)

```yaml
groups:
  - name: figuard
    rules:
      - alert: FiGuardHighDenialRate
        expr: rate(figuard_authorize_denied_total[5m]) > 10
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High authorization denial rate ({{ $value }}/s)"

      - alert: FiGuardP99LatencyHigh
        expr: histogram_quantile(0.99, rate(figuard_authorize_latency_seconds_bucket[5m])) > 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "P99 authorize latency > 500ms ({{ $value }}s)"

      - alert: FiGuardServiceDown
        expr: up{job="figuard"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "FiGuard service is down"

      - alert: FiGuardWebhookTerminalFailures
        expr: increase(figuard_webhook_retry_terminal_total[1h]) > 5
        for: 0m
        labels:
          severity: warning
        annotations:
          summary: "{{ $value }} webhook deliveries gave up in the last hour"
```

---

## SLOs

| SLO | Target | Measure |
|-----|--------|---------|
| Authorize availability | 99.9% | `http_server_requests_seconds_count{uri="/api/v1/authorize",status!~"5.."}` / total |
| Authorize P99 latency | < 200ms | `histogram_quantile(0.99, ...)` on `figuard_authorize_latency_seconds` |
| Webhook delivery success | > 95% within 1h | Recovered / (Recovered + Terminal) |

---

## Structured logging

FiGuard supports structured JSON logging via Spring Boot 3.5's built-in structured log encoder. Set the format at runtime — no code change or library needed:

```bash
# ECS (Elastic Common Schema) — compatible with Elasticsearch/Kibana, Datadog, OpenTelemetry
LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs

# Logstash JSON — compatible with Logstash pipelines, AWS OpenSearch
LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash
```

With structured logging enabled, every log line is a JSON object. The `figuard.*` fields (budgetId, eventId, decision, denialReason) appear as top-level keys, making them directly filterable in your log platform.

**Example ECS output** (pretty-printed):
```json
{
  "@timestamp": "2026-05-19T12:00:00.000Z",
  "log.level": "WARN",
  "message": "VELOCITY_LIMIT_EXCEEDED: budgetId=... limit=velocityMaxPerMinute key=...",
  "service.name": "figuard",
  "process.pid": 1
}
```

Unset or empty: plain text logs (default, suitable for local dev and simple deployments).

---

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | ✅ | — | PostgreSQL JDBC URL |
| `DB_USERNAME` | — | `figuard` | Database username |
| `DB_PASSWORD` | ✅ | — | Database password |
| `WEBHOOK_SECRET_KEY` | ✅ | dev default | AES-256-GCM key (Base64, 32 bytes). Generate: `openssl rand -base64 32` |
| `PORT` | — | `8080` | HTTP port |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | — | — | Set to `ecs` or `logstash` for structured JSON logs |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | — | `10` | Max DB connections per instance — see connection pool tuning below |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | — | `5` | Min idle connections kept warm |
| `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` | — | `30000` | ms to wait for a connection before failing |

The dev default `WEBHOOK_SECRET_KEY` (all-zero bytes) ships in `application.yml`. The service starts with it but logs a warning. **Always set this in production.**

---

## Sizing and resource limits

**Memory:** The JVM default heap is unbounded inside a container. Set `-Xmx` explicitly to avoid OOM kills:

```yaml
# docker-compose.prod.yml — add to the figuard service
environment:
  JAVA_TOOL_OPTIONS: "-Xms256m -Xmx512m"
```

For most deployments 512 MB heap is sufficient. Under sustained high load (>100 req/s), 1 GB gives headroom.

**CPU:** FiGuard is I/O-bound (DB locks, webhook HTTP). 1 vCPU handles ~50–100 authorize/s comfortably. 2 vCPU for production.

**Replicas:** FiGuard is stateless — run as many replicas as needed. ShedLock prevents the confirmation timeout sweep and webhook retry sweep from running concurrently across replicas. All other operations are safe to parallelize.

Recommended minimum for production:
- 2 replicas (one can restart without downtime)
- 1 vCPU, 768 MB memory each
- PostgreSQL: 2 vCPU, 4 GB RAM, 20 GB storage (adjust for event volume)

---

## Connection pool tuning

FiGuard uses HikariCP. The default pool size (10) is appropriate for a single instance handling moderate load.

**Rule of thumb:** `max_pool_size = (number_of_cores × 2) + effective_spindle_count` — for most cloud Postgres instances, a pool of 10–20 per replica is correct.

If you run multiple replicas, ensure the total connections across all replicas stays within PostgreSQL's `max_connections` (default 100):

```
total_connections = replicas × SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
```

For 3 replicas with pool size 20: 60 connections — well within the default. If you need more, increase `max_connections` in `postgresql.conf`.

**Monitor pool saturation:**
```promql
hikaricp_connections_active / hikaricp_connections_max
```
If this ratio stays above 0.8, increase pool size or add a replica.

---

## TLS and reverse proxy

FiGuard listens on plain HTTP (`PORT`, default 8080). **Do not expose port 8080 directly to the internet.** Terminate TLS at a reverse proxy.

**Caddy** (simplest — auto-TLS via Let's Encrypt):
```
figuard.yourdomain.com {
    reverse_proxy localhost:8080
}
```

**nginx:**
```nginx
server {
    listen 443 ssl;
    server_name figuard.yourdomain.com;
    ssl_certificate     /etc/ssl/certs/figuard.crt;
    ssl_certificate_key /etc/ssl/private/figuard.key;

    location / {
        proxy_pass         http://localhost:8080;
        proxy_set_header   Host $host;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 30s;
    }
}
```

Set `server.forward-headers-strategy=native` (or `framework`) in `application.yml` if you need FiGuard to see the real client IP from `X-Forwarded-For`.

---

## Graceful shutdown

FiGuard uses Spring Boot's default graceful shutdown (`server.shutdown=graceful` is not set — shutdown is immediate by default). In-flight HTTP requests complete; the JVM exits on SIGTERM.

**For zero-downtime deploys** (rolling restart with multiple replicas):

```yaml
# application.yml or env var SERVER_SHUTDOWN=graceful
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
```

With graceful shutdown enabled, Docker/Kubernetes will send SIGTERM and FiGuard will drain in-flight requests for up to 20 seconds before exiting. Pending DB transactions (authorize, confirm, void) complete normally — no partial writes.

---

## Webhook delivery

FiGuard delivers webhooks with up to 10 attempts on exponential backoff:

| Attempt | Delay after previous |
|---------|---------------------|
| 1–4 | Immediate (dispatcher inline) |
| 5 | 1 minute |
| 6 | 2 minutes |
| 7 | 4 minutes |
| 8 | 8 minutes |
| 9 | 16 minutes |
| 10 | Terminal — delivery marked FAILED |

The retry sweep runs every 60 seconds. ShedLock prevents concurrent sweeps across replicas.

**Manual retry:** `POST /api/v1/webhooks/deliveries/{id}/retry` — fires asynchronously, returns 202.

**Failed delivery count:** `GET /api/v1/webhooks/deliveries/failed-count`

---

## Database

FiGuard uses PostgreSQL 15+. Schema is managed by Flyway (migrations in `src/main/resources/db/migration/`). The service runs migrations on startup — no manual SQL needed.

**Backup:** Standard PostgreSQL backup (`pg_dump`) applies. No special considerations.

**Scaling:** Authorization calls use pessimistic row-level locks on the `agent_budgets` row. Throughput ceiling is ~200–500 authorizations/second per budget under contention. Distribute agents across multiple budgets for higher throughput.

---

## Incident playbook

### Service returns 500 on `/authorize`

1. Check `GET /actuator/health` — is the DB reachable?
2. Check logs for `ERROR` lines — look for `DataAccessException` or `LockTimeoutException`
3. Check PostgreSQL connection pool saturation: `hikaricp_connections_active` metric
4. If DB is overloaded, consider reducing `hikari.maximum-pool-size` and restarting

### Webhooks not delivering

1. `GET /api/v1/webhooks/deliveries/failed-count` — how many are failing?
2. `GET /api/v1/webhooks/deliveries?status=FAILED` — check `responseStatus` field for the receiver's HTTP response
3. Common causes: receiver returning non-2xx, SSL cert issues, firewall blocking outbound from FiGuard
4. Manual retry: `POST /api/v1/webhooks/deliveries/{id}/retry`
5. If all retries exhausted, investigate receiver, fix it, then recreate the webhook config

### Authorization denial spike

1. Check `figuard_authorize_denied_total` by `denial_reason` tag
2. `VELOCITY_LIMIT_EXCEEDED` spike → agent loop runaway, check the agent
3. `BUDGET_EXHAUSTED` spike → legitimate exhaustion or abuse, check the budget's ledger
4. `ALLOCATION_EXHAUSTED` → category limit hit, consider increasing allocation or adding `SOFT` mode
