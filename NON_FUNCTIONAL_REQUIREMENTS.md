# Non-Functional Requirements

This document captures cross-cutting quality requirements for recreating eShopOnContainers in Java Spring Boot: logging, observability, health checks, resilience, retries, caching/state, validation, exception formats, and performance expectations.

Use this with `CONFIG_DEPLOYMENT.md`, `AUTH_SECURITY.md`, `BUSINESS_RULES.md`, `EVENT_CONTRACTS.md`, `API_CONTRACTS.md`, and `TEST_SCENARIOS.md`.

## Logging

### NFR-LOG-001 Structured Logging

All services should emit structured logs.

.NET source behavior:

- Services use Serilog or Microsoft logging abstractions.
- Logs are written to console.
- Optional sinks include Seq and Logstash through `Serilog:SeqServerUrl` and `Serilog:LogstashgUrl`.
- Application settings commonly set:
  - `Serilog:MinimumLevel:Default=Information`
  - `Serilog:MinimumLevel:Override:Microsoft=Warning`
  - `Serilog:MinimumLevel:Override:System=Warning`
  - `Serilog:MinimumLevel:Override:Microsoft.eShopOnContainers=Information`

Spring Boot equivalent:

- Use SLF4J + Logback or Log4j2 with structured JSON-capable output.
- Emit logs to stdout/stderr for containers.
- Support optional forwarding to Seq, ELK, Loki, OpenTelemetry collector, or equivalent.
- Keep log levels configurable per package/service.

### NFR-LOG-002 Required Log Events

Services should log:

- Application startup and fatal startup failures.
- Database migration and seeding start/success/failure.
- Health dependency failures.
- HTTP unhandled exceptions.
- Command handling start/end in Ordering.
- Validation failures.
- Integration event publishing, subscription, receipt, handling, and handler errors.
- RabbitMQ connection attempts, reconnects, publish retries, and missing event subscriptions.
- Webhook grant validation and delivery failures.

### NFR-LOG-003 Correlation Context

Integration event handlers should include event context in logs.

.NET source behavior:

- Several handlers push `IntegrationEventContext` as `{eventId}-{appName}`.
- Ordering command logs include command type and payload.
- HTTP request ids are propagated through command headers such as `x-requestid`.

Spring Boot equivalent:

- Put event id, event name, handler, service name, request id, order id, buyer id, and correlation id into MDC where available.
- Include these fields in every structured log record emitted during the request/event scope.

## Observability

### NFR-OBS-001 Telemetry

.NET source behavior:

- Most services call `AddApplicationInsightsTelemetry`.
- Kubernetes enrichment is enabled through Application Insights Kubernetes enricher.
- `ApplicationInsights:InstrumentationKey` is configurable and may be empty locally.

Spring Boot equivalent:

- Use Micrometer and OpenTelemetry.
- Expose metrics compatible with Prometheus/OpenTelemetry collectors.
- Support optional Application Insights exporter if Azure deployment parity is desired.

### NFR-OBS-002 Metrics

At minimum, collect:

- HTTP request count, latency, and error rate by route/status.
- JVM memory, CPU, thread, GC, and datasource pool metrics.
- RabbitMQ publish/consume success/failure counts.
- Event handler duration and failure counts by event name.
- Redis operation errors and latency.
- SQL/Mongo connection pool and query error metrics where available.
- Checkout/order workflow state transition counts.
- Webhook delivery success/failure counts.

### NFR-OBS-003 Tracing

Distributed tracing should propagate through:

- Browser to MVC/BFF/API requests where headers exist.
- MVC and BFF downstream HTTP/gRPC calls.
- Event publication and event consumption.
- Webhook delivery calls.

The Spring rewrite should preserve request/event causality even though the .NET sample does not enforce a full trace standard everywhere.

## Health Checks

### NFR-HC-001 Endpoints

Every deployable service should expose:

- `/hc` for readiness/full health.
- `/liveness` for self-only liveness.

The `/hc` response should include service and dependency health details compatible with WebStatus-style aggregation.

### NFR-HC-002 Readiness Dependencies

Dependency checks must match service ownership:

| Component | Readiness checks |
| --- | --- |
| Identity | self, Identity SQL DB |
| Catalog | self, Catalog SQL DB, optional Azure Blob Storage, RabbitMQ or Azure Service Bus |
| Basket | self, Redis, RabbitMQ or Azure Service Bus |
| Ordering API | self, Ordering SQL DB, RabbitMQ or Azure Service Bus |
| Ordering BackgroundTasks | self, Ordering SQL DB, event bus |
| Ordering notification hub | self, RabbitMQ or Azure Service Bus |
| Marketing | self, Marketing SQL DB, MongoDB, RabbitMQ or Azure Service Bus |
| Locations | self, MongoDB, RabbitMQ or Azure Service Bus |
| Payment | self, RabbitMQ or Azure Service Bus |
| Webhooks | self, Webhooks SQL DB |
| MVC/SPA | self, Identity `/hc` |
| BFF aggregators | self and downstream Catalog, Ordering, Basket, Identity, Marketing, Payment, Locations `/hc` URLs |
| WebStatus | self and configured service health endpoints |

### NFR-HC-003 Liveness

`/liveness` should only report process/self health. It should not fail because SQL, Redis, MongoDB, RabbitMQ, or downstream HTTP dependencies are temporarily unavailable.

This distinction is important for orchestrators so dependency outages do not cause unnecessary process restarts.

## Resilience And Retries

### NFR-RES-001 SQL Resilience

.NET source behavior:

- EF Core SQL Server contexts use `EnableRetryOnFailure(maxRetryCount: 15, maxRetryDelay: 30 seconds)`.
- Identity, Catalog, Ordering, Marketing, and Webhooks SQL-backed services use SQL connection resiliency.

Spring Boot equivalent:

- Configure datasource and transaction retry for transient SQL failures.
- Use Spring Retry, Resilience4j, or database-driver retry features.
- Preserve a retry budget comparable to 15 attempts with up to 30 seconds delay for startup/migration/transient SQL failures.

### NFR-RES-002 Startup Migration Retry

.NET source behavior:

- Database migration/seeding uses Polly retries outside Kubernetes.
- Retry count is commonly 10.
- Delay is exponential: `2^retryAttempt` seconds.
- Under Kubernetes, migration errors are rethrown so the orchestrator can restart the pod.

Spring Boot equivalent:

- Flyway/Liquibase migrations should tolerate database startup order in local Compose.
- Outside Kubernetes/local Compose, retry migration/seed on transient SQL availability errors.
- Under Kubernetes, prefer failing fast and relying on restart/backoff unless deployment policy says otherwise.

### NFR-RES-003 RabbitMQ Connection Retry

.NET source behavior:

- RabbitMQ persistent connection retries connection on socket/broker unreachable exceptions.
- Default `EventBusRetryCount` is `5`.
- Backoff is exponential: `2^retryAttempt` seconds.
- The connection reconnects after shutdown, callback exception, or blocked connection events.

Spring Boot equivalent:

- Configure Spring AMQP connection retry/recovery with comparable retry count and exponential backoff.
- Log every reconnect attempt and failure reason.

### NFR-RES-004 RabbitMQ Publish Retry

.NET source behavior:

- Publishing retries `BrokerUnreachableException` and `SocketException`.
- Default retry count is `5`.
- Backoff is exponential: `2^retryAttempt` seconds.
- Messages are published to direct exchange `eshop_event_bus`.
- Published messages are marked persistent with delivery mode `2`.

Spring Boot equivalent:

- Use durable exchange/queues and persistent messages.
- Retry transient publish failures with exponential backoff.
- Persist outgoing events in an outbox/integration-event log before publishing where the source service does so.

### NFR-RES-005 RabbitMQ Consume Semantics

.NET source behavior:

- Consumers use manual ack (`autoAck=false`).
- The handler catches and logs processing exceptions.
- The message is acknowledged even when handler processing fails.
- Source comments explicitly note that a real-world app should use a dead-letter exchange.

Spring Boot equivalent for exact parity:

- Acknowledge messages after handler invocation attempt, even on handler exception.
- Log handler exceptions with event payload and event name.

Recommended hardening:

- Use dead-letter queues, retry topics, and poison-message handling.
- If hardening changes ack behavior, document the intentional difference because it affects event replay and failure semantics.

### NFR-RES-006 HTTP Client Resilience

.NET source behavior:

- MVC appsettings include `UseResilientHttp=True`, `HttpClientRetryCount=8`, and `HttpClientExceptionsAllowedBeforeBreaking=7`.
- HttpClient handlers are pooled with 5-minute handler lifetime for most clients.
- Some webhook clients use infinite handler lifetime for a named client and 5-minute lifetime for grant client.

Spring Boot equivalent:

- Use WebClient/RestClient/Feign with connection pooling.
- Configure retry and circuit-breaker policies for downstream HTTP calls.
- Preserve comparable retry count and circuit-breaker threshold for MVC-to-service calls.
- Keep downstream call timeouts finite and configurable.

### NFR-RES-007 Idempotency

Ordering commands and checkout processing must remain idempotent:

- `x-requestid` identifies order commands.
- Duplicate command ids return successful no-op results.
- Event consumers must tolerate duplicate events.
- Basket deletion after order start must be safe to replay.
- Product price updates should not repeatedly overwrite `OldUnitPrice` when stale duplicate price-change events arrive.

## Caching And State

### NFR-CACHE-001 Basket Store

Basket data is stored in Redis.

Requirements:

- Basket lookup by buyer/customer id must be fast and key-based.
- Missing basket reads return an empty basket object for the requested id.
- Basket create/update replaces the current basket document for the buyer.
- Basket delete removes the key.
- Basket service must tolerate Redis unavailability through health reporting and clear error handling.

### NFR-CACHE-002 Distributed Session And Data Protection

.NET source behavior:

- MVC uses distributed memory cache and session locally.
- When `IsClusterEnv=True`, WebMVC, WebSPA, and Identity persist data-protection keys to Redis key `DataProtection-Keys`.
- Ordering SignalRHub can use Redis backplane via `SignalrStoreConnectionString` in clustered mode.

Spring Boot equivalent:

- Use Spring Session only where cookie/session parity requires it.
- Use Redis-backed sessions or shared signing/encryption keys for clustered web apps.
- Use Redis or broker-backed messaging for WebSocket scaling.

### NFR-CACHE-003 HTTP/Response Caching

The source does not define broad HTTP response caching for APIs. Do not introduce stale response caching for catalog, basket, ordering, campaign, or location APIs unless explicitly required.

Static assets may be served using normal web-server/browser caching.

## Validation

### NFR-VAL-001 API Model Validation

APIs should validate request bodies and route/query inputs before business logic.

Source examples:

- Basket uses a model-state filter that returns `400 Bad Request` with a `messages` array.
- Catalog configures invalid model-state responses as `ValidationProblemDetails` with content types `application/problem+json` and `application/problem+xml`.
- Ordering uses FluentValidation in a MediatR pipeline for command validation.

Spring Boot equivalent:

- Use Jakarta Bean Validation annotations and explicit validators.
- Return service-compatible error formats described below.
- Log validation failures with command/request type and errors.

### NFR-VAL-002 Ordering Command Validation

Ordering must validate:

- Required street, city, state, country, and zip code.
- Card number required and length 12 to 19.
- Card holder name required.
- Card expiration required and not in the past.
- Card security number required and length exactly 3.
- Card type id required.
- Order items collection is non-empty.
- Cancel and ship commands include a non-empty order number.
- Identified commands include a non-empty id/request id.

### NFR-VAL-003 Domain Validation

Domain validation must protect invariants even when API validation is bypassed:

- Order item units must be positive for new items.
- Negative added units are invalid.
- Discounts cannot exceed line total.
- Paid or shipped orders cannot be cancelled.
- Only paid orders can be shipped.
- Catalog stock removal requires positive quantity and non-empty stock.
- Payment method card number, security number, and holder name cannot be blank.
- Payment method expiration cannot be in the past.

## Exception And Error Formats

### NFR-ERR-001 Domain Exception Mapping

Service-specific domain exceptions map to `400 Bad Request`.

| Service | Domain exception format |
| --- | --- |
| Catalog | `ValidationProblemDetails` with `status=400`, `detail="Please refer to the errors property for additional details."`, and `errors.DomainValidations` |
| Ordering | `ValidationProblemDetails` with `status=400`, `detail="Please refer to the errors property for additional details."`, and `errors.DomainValidations` |
| Basket | JSON object with `messages: [message]` |
| Marketing | JSON object with `messages: [message]` |
| Locations | JSON object with `messages: [message]` |

Spring Boot equivalent:

- Implement per-service exception handlers that preserve these response shapes.
- Use `application/problem+json` where the .NET service does.

### NFR-ERR-002 Validation Error Format

Known validation formats:

- Catalog invalid model state returns `ValidationProblemDetails` with `application/problem+json` or `application/problem+xml`.
- Basket invalid model state returns `{ "messages": [...] }`.
- Ordering command validation throws an ordering domain exception and should surface as the Ordering domain problem-details format.

### NFR-ERR-003 Unexpected Exception Format

Unexpected exceptions map to `500 Internal Server Error`.

Message strings differ by service and should be preserved where feasible:

| Service group | Generic message |
| --- | --- |
| Basket | `An error occurred. Try it again.` |
| Catalog | `An error ocurred.` |
| Ordering | `An error occur.Try it again.` |
| Marketing | `An error occur.Try it again.` |
| Locations | `An error occur.Try it again.` |

Development mode may include a `developerMessage`/developer details field containing exception details. Production mode should not expose stack traces.

### NFR-ERR-004 Not Found And Bad Request

Preserve explicit status-code behavior from `API_CONTRACTS.md`:

- Missing catalog item detail returns `404`.
- Invalid catalog item id less than or equal to zero returns `400`.
- Invalid catalog ids query returns `400`.
- Missing basket checkout basket returns `400`.
- Missing basket read returns an empty basket, not `404`.
- Missing order detail returns `404`.
- Missing order command target generally causes command false and API `400`.

## Health And Readiness Performance

### NFR-PERF-001 Health Check Cost

Health checks must be lightweight:

- `/liveness` should perform only an in-process self check.
- `/hc` may check dependencies but should not perform expensive business queries.
- Health checks should time out quickly enough for orchestrators and WebStatus to remain responsive.

### NFR-PERF-002 Startup Behavior

Services may run database migrations and seed data at startup. This can make initial startup slower than steady-state restarts.

Requirements:

- Startup should tolerate dependency order in Docker Compose through retries.
- Startup should fail clearly when required dependencies remain unavailable.
- In orchestrated environments, readiness should remain unhealthy until migration/seed and dependency checks are ready.

## Runtime Performance Expectations

The repository does not define hard latency, throughput, or resource SLAs. The Spring Boot rewrite should therefore use compatibility-oriented expectations:

- Catalog list/detail endpoints should be fast enough for interactive page browsing with default page size 10.
- Basket operations should be low-latency key/value Redis operations.
- Checkout should return quickly after publishing the checkout event; downstream order workflow is asynchronous.
- Order status transitions may intentionally include simulated delays of about 10 seconds in stock/payment command handlers.
- Ordering background checks use configurable intervals such as `CheckUpdateTime`.
- BFF aggregators should avoid unnecessary serial calls when independent downstream calls can run concurrently.
- Image/static file delivery should not block API request processing.
- Event handlers should process one event without unbounded synchronous blocking except for intentional sample delays.

## Availability And Degradation

### NFR-AVAIL-001 Dependency Failure Behavior

When dependencies fail:

- Readiness should report unhealthy for affected services.
- Liveness should remain healthy unless the process itself is broken.
- MVC cart/add-to-cart catches Basket service failures and shows a basket-inoperative message.
- Event publish failures should be logged and retried according to event bus retry policy.
- Unhandled API exceptions should produce service-specific `500` responses.

### NFR-AVAIL-002 Data Consistency

Requirements:

- SQL-backed event publishers should use integration-event/outbox tables where present.
- Catalog price change and product update must save atomically with the integration-event log entry.
- Ordering order creation and order-started integration event must be saved consistently.
- Event consumers must be idempotent enough for duplicate event delivery.

## Security-Adjacent Non-Functional Requirements

Security details are defined in `AUTH_SECURITY.md`; operationally:

- Do not log raw passwords.
- Avoid logging full card/security-number values in production.
- Production mode must not expose developer exception details.
- Use configurable secrets and connection strings, not hard-coded values.
- Keep CORS behavior compatible for local parity, but restrict origins in production if hardening is enabled.

## Spring Boot Implementation Checklist

- Use Spring Boot Actuator for `/hc` and `/liveness`.
- Use Micrometer/OpenTelemetry for metrics and tracing.
- Use structured JSON logging with correlation ids and event ids.
- Use Spring Retry/Resilience4j for SQL, HTTP, and event-bus transient failures.
- Use Flyway or Liquibase with startup retry behavior matching local Compose.
- Use Spring AMQP durable direct exchange `eshop_event_bus`, persistent messages, and configured retry/backoff.
- Preserve source-compatible exception response shapes per service.
- Preserve validation rules and return `400` instead of allowing invalid data into domain logic.
- Use Redis for basket state and optional clustered sessions/backplane.
- Avoid adding broad HTTP response caching that changes API freshness.
- Keep runtime delays, async workflow behavior, and eventual consistency visible in tests.

