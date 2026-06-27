# Event Ledger

A two-service system for processing financial transaction events: an **Event Gateway** (public-facing) and an **Account Service** (internal). Built to handle out-of-order delivery, duplicate submissions, and downstream failure gracefully.

## Architecture

```
                          ┌──────────────────────┐
Browser / Client ──────→  │  Event Gateway API    │
                          │  (public-facing)      │
                          └──────┬───────────────┘
                                 │ REST (sync, via Feign)
                                 ▼
                          ┌──────────────────────┐
                          │  Account Service      │
                          │  (internal only)      │
                          └──────────────────────┘
```

### Event Gateway (`event-gateway`)

The entry point for all client requests. Validates and persists incoming events to its own H2 database, enforces idempotency, then forwards each transaction to the Account Service. If the Account Service is slow, failing, or returns a definitive rejection, the Gateway degrades gracefully rather than losing the event or hanging.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event by ID |
| `GET` | `/events?account={accountId}` | List events for an account, chronological by `eventTimestamp` |
| `GET` | `/accounts/{accountId}/balance` | *(bonus, not in original spec)* Proxy a balance lookup to the Account Service |
| `GET` | `/accounts/{accountId}` | *(bonus, not in original spec)* Proxy an account-details lookup |
| `GET` | `/actuator/health` | Health check (Spring Boot Actuator) |

Interactive API docs (Swagger UI): `http://localhost:8080/swagger-ui/index.html`

### Account Service (`account-service`)

Manages account state — balances, transaction history. Only called by the Gateway; never exposed externally. Accounts are created **implicitly** on first transaction; there is no separate account-creation endpoint.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction (requires `Idempotency-Key` header) |
| `GET` | `/accounts/{accountId}/balance` | Current balance, computed fresh on every call |
| `GET` | `/accounts/{accountId}` | Account details + 10 most recent transactions |
| `GET` | `/actuator/health` | Health check |

---

## Setup

### Prerequisites

- Java 17
- Maven 3.9+
- (Optional) Docker + Docker Compose

### Versions used

| Component | Version |
|---|---|
| Spring Boot | 3.5.15 |
| Spring Cloud | 2025.0.3 *(officially paired with Boot 3.5.15 — see Spring Cloud 2025.0.3 release notes)* |
| Resilience4j | 2.4.0 |
| H2 | runtime-managed by Spring Boot's parent BOM |

### Build & run (manual, no Docker)

```bash
# Terminal 1 — Account Service
cd account-service
mvn clean install
mvn spring-boot:run
# starts on port 8081

# Terminal 2 — Event Gateway
cd event-gateway
mvn clean install
mvn spring-boot:run
# starts on port 8080
```

The Gateway is configured to reach the Account Service at `http://localhost:8081` by default (`ACCOUNT_SERVICE_URL` env var to override).

### Run with Docker Compose

```bash
docker-compose up --build
```

This starts both services with the correct startup order and networking; the Gateway reaches the Account Service via its Docker Compose service name rather than `localhost`.

### H2 Console (for inspecting either service's data live)

Each service exposes its own H2 console at `/h2-console` (enabled in `application.yml`). JDBC URL, username, and password are printed in each service's startup logs.

---

## Running the tests

```bash
# from each service's directory
mvn test
```

Tests cover core functionality (idempotency, out-of-order tolerance, balance correctness, validation), resiliency behavior (circuit breaker opening under simulated Account Service failure), trace propagation, and at least one full Gateway → Account Service integration flow.

---

## Design decisions

### Idempotency — two independent layers, not one

Idempotency is enforced **separately** at two different boundaries, because each protects against a different failure mode:

1. **Gateway (`eventId` as primary key on `EventEntity`)** — protects against the *client* submitting the same event more than once. Same `eventId` + same payload → returns the original (200), not a fresh 201. Same `eventId` + a *different* payload → `409 Conflict`.
2. **Account Service (`eventId` as primary key on `TransactionEntity`, received via the `Idempotency-Key` header)** — protects against the *Gateway's own retry mechanism* re-sending a request whose first attempt actually succeeded but whose acknowledgment was lost (a timeout on the Gateway's side doesn't mean the Account Service didn't process the request). Without this second layer, a Gateway-side retry after a lost ack could silently double-apply a transaction even though the client only submitted once.

A concurrent-duplicate-insert race (two requests for the same `eventId` arriving close enough together that both pass the existence check before either commits) is handled by catching the resulting database constraint violation and treating the loser as an idempotent replay — not as an error.

### Out-of-order tolerance — balance is never stored, always computed

`TransactionEntity` has **no balance column**. Every balance query is a fresh `SUM(CASE WHEN type = CREDIT THEN amount ELSE -amount END)` over the account's transaction rows. Since addition is commutative, the result is identical regardless of the order transactions were *inserted* in — out-of-order arrival is handled "for free" by this design, rather than by special-casing arrival order anywhere in the code. Event listings are separately sorted by `eventTimestamp` (the order things *happened*), independent of insertion order.

### Resiliency pattern — Retry + Circuit Breaker (Resilience4j, annotation-based)

The Gateway's call to the Account Service is wrapped with:

- **`@Retry`** — up to 3 attempts, exponential backoff with jitter (200ms → 400ms → 800ms, ±50% randomized so multiple Gateway instances don't retry in lockstep).
- **`@CircuitBreaker`** — opens after 5+ calls with a ≥50% failure rate; stays open 10s before allowing trial requests through again.

**Why this pair, not bulkhead:** the assessment allows any one of circuit breaker / bulkhead / timeout+retry. Retry+breaker were chosen because they directly address the two most realistic local-network failure modes for this system — transient blips (handled by retry) and a genuinely struggling downstream (handled by the breaker, which stops hammering a service that's already failing). 4xx responses (400, 404) are explicitly excluded from both retry and the breaker's failure accounting, since a 4xx is a definitive answer, not a transient or infrastructural problem — retrying it would be pointless and would unfairly count against the breaker.

**Bonus implemented — rate limiting:** `POST /events` is capped at 10 requests/second (one shared bucket across all callers) via `@RateLimiter`, returning `429 Too Many Requests` when exceeded.

### Graceful degradation

- The event is persisted to the Gateway's local database **before** the Account Service is called — durability doesn't depend on the downstream call succeeding.
- If the Account Service call fails for an infrastructural reason (timeout, connection refused, 5xx, breaker open), the event is marked `FAILED_DOWNSTREAM` and the Gateway returns `503`. Resubmitting the same event later **retries** the forward — safe because the Account Service's own idempotency check prevents double-application even if the original attempt had actually succeeded.
- If the Account Service returns a **definitive** `404` (account doesn't exist), the event is marked `REJECTED` instead — resubmitting does *not* automatically retry, since retrying would just hit the same 404 again.
- `GET /events/{id}` and `GET /events?account=...` depend only on the Gateway's own database and continue to work normally even while the Account Service is completely down.

### Distributed tracing

Trace propagation uses W3C `traceparent` headers via Micrometer Tracing + OpenTelemetry. Both services log `traceId`/`spanId` automatically (populated into MDC by the tracing auto-configuration) — **no manual code writes these into logs**. The one piece that does need explicit code: Feign does not automatically propagate trace headers the way `RestClient`/`RestTemplate` do, so a `RequestInterceptor` (`FeignClientConfig`) manually injects the current trace context onto every outgoing Feign request. Without it, the Account Service would start a disconnected trace for every Gateway-originated call.

### Structured logging

JSON-formatted logs (Elastic Common Schema via `logstash-logback-encoder`) include `service`, `env`, `traceId`, `spanId`, timestamp, and log level on every line. Async appenders decouple log writes from request-handling threads; a separate `*-error.log` stream isolates WARN/ERROR for easier alerting. Local/dev profile uses a human-readable plain-text console pattern instead.

---

## Known limitations

- The Gateway cannot fully distinguish "the Account Service applied the transaction but the acknowledgment was lost" from "the Account Service never received the request at all" — both surface identically as a timeout/connection exception. This is mitigated by the Account Service's own idempotency check (safe to retry either way), but a `FAILED_DOWNSTREAM` event with no automatic background retry job may sit in that state until manually resubmitted.
- No automated reconciliation/background job currently retries `FAILED_DOWNSTREAM` events on its own — retry only happens if the same event is resubmitted by the client.
- Spring Cloud 2025.0.3 / Spring Boot 3.5.15 is the final open-source release of the 2025.0.x train (OSS support ends June 30, 2026) — fine for this assessment, not a long-term production pairing without extended support.
