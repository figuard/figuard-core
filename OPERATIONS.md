# FiGuard Operations Runbook

Reference for operators running FiGuard in production.

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

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | ✅ | — | PostgreSQL JDBC URL |
| `DB_USERNAME` | — | `figuard` | Database username |
| `DB_PASSWORD` | ✅ | — | Database password |
| `WEBHOOK_SECRET_KEY` | ✅ | dev default | AES-256-GCM key (Base64, 32 bytes). Generate: `openssl rand -base64 32` |
| `PORT` | — | `8080` | HTTP port |

The dev default `WEBHOOK_SECRET_KEY` (all-zero bytes) ships in `application.yml`. The service starts with it but logs a warning. **Always set this in production.**

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
