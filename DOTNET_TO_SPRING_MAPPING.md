# .NET To Spring Boot Mapping

This document maps each .NET eShopOnContainers service, web app, gateway, shared library, and test project to a Spring Boot equivalent. Use it as the target project/module map when recreating the application in Java.

The goal is behavioral equivalence, not a literal framework translation. Preserve the contracts documented in `API_CONTRACTS.md`, `DATA_MODEL.md`, `EVENT_CONTRACTS.md`, `UI_BEHAVIOR.md`, and `TEST_SCENARIOS.md`.

## Recommended Spring Workspace Layout

Recommended top-level structure:

```text
eshop-spring/
  pom.xml
  common/
    event-bus/
    event-log/
    web-support/
    security/
    test-support/
  services/
    catalog-service/
    basket-service/
    identity-service/
    ordering-service/
    ordering-background-service/
    ordering-notification-service/
    marketing-service/
    location-service/
    payment-service/
    webhooks-service/
  gateways/
    web-shopping-aggregator/
    mobile-shopping-aggregator/
    envoy/
  web/
    webmvc-app/
    webspa-host/
    webstatus-app/
    webhook-client-app/
  tests/
    application-acceptance-tests/
```

Use a Maven multi-module build or Gradle multi-project build. A Maven parent `pom.xml` with Spring Boot dependency management is the simplest equivalent to the .NET solution file.

## Service And Application Mapping

| .NET project/container | Spring Boot equivalent | Target artifact | Primary responsibility | Notes |
| --- | --- | --- | --- | --- |
| `Services/Catalog/Catalog.API` / `catalog-api` | `catalog-service` | Spring Boot REST service | Catalog items, brands, types, images, catalog stock, catalog integration events | Use Spring MVC, Spring Data JPA, SQL Server/PostgreSQL-compatible schema, RabbitMQ listener/publisher, Actuator health. |
| `Services/Basket/Basket.API` / `basket-api` | `basket-service` | Spring Boot REST service | Buyer baskets, basket checkout command, basket deletion, basket price-change event handling | Use Spring MVC, Spring Data Redis, RabbitMQ, OAuth2 resource server. |
| `Services/Identity/Identity.API` / `identity-api` | `identity-service` or external IdP integration | Spring Boot auth service or Keycloak/Auth0 config | Login, OAuth/OIDC, demo users, token issuing, consent/logout equivalents | Prefer Keycloak for production-like parity. A Spring Authorization Server implementation is possible but heavier. |
| `Services/Ordering/Ordering.API` / `ordering-api` | `ordering-service` | Spring Boot REST/gRPC service | Orders API, order domain commands, order queries, order saga event handlers, card types | Use Spring MVC, Spring Data JPA, optional jOOQ/JdbcTemplate for read models, RabbitMQ, gRPC if preserving aggregator protocol. |
| `Services/Ordering/Ordering.Domain` | `ordering-domain` package inside `ordering-service` | Java package/module | Order aggregate, buyer aggregate, value objects, domain events, status/card type model | Keep this as plain Java domain code with no Spring annotations where practical. |
| `Services/Ordering/Ordering.Infrastructure` | `ordering-infrastructure` package inside `ordering-service` | Java package/module | JPA mappings, repositories, query implementations, event-log integration | Can be a nested package or separate Maven module if enforcing hexagonal boundaries. |
| `Services/Ordering/Ordering.BackgroundTasks` / `ordering-backgroundtasks` | `ordering-background-service` | Spring Boot worker service | Grace-period/order-state background processing | Use `@Scheduled`, Spring Integration, or a queue-driven worker. Keep separately deployable if matching containers. |
| `Services/Ordering/Ordering.SignalrHub` / `ordering-signalrhub` | `ordering-notification-service` | Spring Boot WebSocket/STOMP service | Order status push notifications to browsers | Replace SignalR with WebSocket/STOMP or SockJS. Preserve client-visible `UpdatedOrderState` behavior. |
| `Services/Marketing/Marketing.API` / `marketing-api` | `marketing-service` | Spring Boot REST service | Campaigns, campaign images, campaign location rules, user campaign selection, location-event consumption | Use Spring MVC, Spring Data JPA for campaign data, Spring Data MongoDB for user-location projection if preserving source split. |
| `Services/Marketing/Infrastructure/AzureFunctions/marketing-functions` | Fold into `marketing-service` or `marketing-functions-worker` | Spring Cloud Function or scheduled/queue worker | Legacy Azure Functions marketing infrastructure | Only create a separate module if the original function behavior is required independently. |
| `Services/Location/Locations.API` / `locations-api` | `location-service` | Spring Boot REST service | User location storage, location history, location integration events | Use Spring MVC, Spring Data MongoDB, RabbitMQ publisher. |
| `Services/Payment/Payment.API` / `payment-api` | `payment-service` | Spring Boot event worker/API service | Simulated payment processing and payment success/failure events | Mostly event-driven. Use RabbitMQ listeners/publishers and Actuator health. |
| `Services/Webhooks/Webhooks.API` / `webhooks-api` | `webhooks-service` | Spring Boot REST/event service | Webhook subscriptions and webhook dispatch for integration events | Use Spring MVC, Spring Data JPA, RabbitMQ listeners, `WebClient` for delivery. |
| `ApiGateways/Web.Bff.Shopping/aggregator` / `webshoppingagg` | `web-shopping-aggregator` | Spring Boot BFF service | Web shopping aggregation, order draft, basket aggregation, downstream REST/gRPC calls | Use Spring MVC or WebFlux, OAuth2 resource server/client, OpenFeign/WebClient, gRPC client if preserving Ordering gRPC. |
| `ApiGateways/Mobile.Bff.Shopping/aggregator` / `mobileshoppingagg` | `mobile-shopping-aggregator` | Spring Boot BFF service | Mobile shopping aggregation | Same stack as web aggregator; keep separate if mobile response contracts differ. |
| Envoy `webshoppingapigw` | `envoy/webshopping` config | Envoy configuration | Web shopping gateway routing | Keep Envoy as infrastructure, not Spring Boot. Alternative: Spring Cloud Gateway, but matching Envoy is lower risk. |
| Envoy `webmarketingapigw` | `envoy/webmarketing` config | Envoy configuration | Web marketing gateway routing | Preserve routes and public ports. |
| Envoy `mobileshoppingapigw` | `envoy/mobileshopping` config | Envoy configuration | Mobile shopping gateway routing | Preserve mobile client route shape. |
| Envoy `mobilemarketingapigw` | `envoy/mobilemarketing` config | Envoy configuration | Mobile marketing gateway routing | Preserve mobile client route shape. |
| `Web/WebMVC` / `webmvc` | `webmvc-app` | Spring MVC + Thymeleaf app | Server-rendered catalog, cart, checkout, orders, campaigns, login/logout shell | Use Spring MVC, Thymeleaf, Spring Security OAuth2 client, WebClient/Feign to BFF/APIs. |
| `Web/WebSPA` / `webspa` | `webspa-host` plus frontend app | Static frontend host or Spring Boot static host | SPA asset hosting and config endpoints | Keep the SPA framework if desired; Spring Boot can serve static assets and `/hc`/config. |
| `Web/WebStatus` / `webstatus` | `webstatus-app` | Spring Boot admin/status app | Health check dashboard and `/Config` endpoint | Use Spring Boot Admin or a small Actuator health dashboard. Preserve `/Config` behavior. |
| `Web/WebhookClient` / `webhooks-client` | `webhook-client-app` | Spring MVC + Thymeleaf app or REST receiver | Webhook subscription UI and webhook receiver endpoint | Use Spring MVC, Spring Security OAuth2 client, persistent received-webhook store if needed. |
| `Mobile/eShopOnContainers.Core` | Out of Spring scope; optional `mobile-client-contracts` docs | Mobile app/client | Xamarin mobile client core | Do not port to Spring Boot. Preserve APIs/gateway behavior so a future client can work. |
| `Mobile/eShopOnContainers.iOS` | Out of Spring scope | Mobile app | Xamarin iOS client | No Spring replacement unless rewriting mobile app separately. |
| `Mobile/eShopOnContainers.Droid` | Out of Spring scope | Mobile app | Xamarin Android client | No Spring replacement unless rewriting mobile app separately. |
| `Mobile/eShopOnContainers.Windows` | Out of Spring scope | Mobile/desktop app | Xamarin/UWP client | No Spring replacement unless rewriting client separately. |

## Shared Building Block Mapping

| .NET building block | Spring equivalent | Recommended location | Notes |
| --- | --- | --- | --- |
| `BuildingBlocks/EventBus/EventBus` | Event bus abstraction | `common/event-bus` | Define Java interfaces for publish, subscribe/listener registration, event envelope, and handler idempotency. |
| `BuildingBlocks/EventBus/EventBusRabbitMQ` | RabbitMQ implementation | `common/event-bus` | Use Spring AMQP. Preserve exchange, queue, routing, retry, ack, and event naming behavior from `EVENT_CONTRACTS.md`. |
| `BuildingBlocks/EventBus/EventBusServiceBus` | Azure Service Bus implementation | Optional `common/event-bus-azure` | Only implement if Azure Service Bus deployment parity is required. RabbitMQ is enough for local/container parity. |
| `BuildingBlocks/EventBus/IntegrationEventLogEF` | Integration event log/outbox | `common/event-log` | Implement with Spring Data JPA/JdbcTemplate. Each SQL-backed service owns its own event log table. |
| `BuildingBlocks/WebHostCustomization/WebHost.Customization` | Web host startup/migration helpers | `common/web-support` | Replace with Flyway/Liquibase migrations, Spring Boot startup checks, retry templates, and Actuator health. |
| `BuildingBlocks/Devspaces.Support` | Dev environment forwarding helpers | Optional `common/dev-support` | Usually omit unless recreating Azure Dev Spaces behavior. |
| Shared Serilog/Application Insights config | Logging/telemetry support | `common/web-support` | Use Logback/SLF4J, Micrometer, OpenTelemetry, and optional Application Insights exporter. |
| Swagger/Swashbuckle usage | OpenAPI generation | Each REST service | Use `springdoc-openapi`. Preserve API contracts, not generated URLs necessarily. |
| ASP.NET health checks | Spring Actuator health | Each service/app | Expose `/hc` and `/liveness` compatibility endpoints, either directly or via Actuator route mapping. |

## Test Project Mapping

| .NET test project | Spring equivalent | Scope |
| --- | --- | --- |
| `Services/Catalog/Catalog.UnitTests` | `catalog-service/src/test` | Catalog domain/controller/repository tests with JUnit 5, Mockito, Spring MVC Test, Testcontainers SQL. |
| `Services/Catalog/Catalog.FunctionalTests` | `catalog-service/src/test` or `tests/application-acceptance-tests` | Catalog API functional tests. |
| `Services/Basket/Basket.UnitTests` | `basket-service/src/test` | Basket service, Redis repository, controller tests. |
| `Services/Basket/Basket.FunctionalTests` | `basket-service/src/test` | Basket API + Redis tests using Testcontainers Redis. |
| `Services/Ordering/Ordering.UnitTests` | `ordering-service/src/test` | Order aggregate, command handlers, validators, value objects. |
| `Services/Ordering/Ordering.FunctionalTests` | `ordering-service/src/test` | Ordering API and persistence tests. |
| `Services/Location/Locations.FunctionalTests` | `location-service/src/test` | Locations API + MongoDB tests. |
| `Services/Marketing/Marketing.FunctionalTests` | `marketing-service/src/test` | Campaign API and location projection tests. |
| `Tests/Services/Application.FunctionalTests` | `tests/application-acceptance-tests` | Cross-service acceptance tests from `TEST_SCENARIOS.md`; use Testcontainers Compose or Docker Compose. |
| `BuildingBlocks/EventBus/EventBus.Tests` | `common/event-bus/src/test` | Event bus subscription, publish, retry, and duplicate-delivery behavior. |
| Mobile test runner projects | Out of Spring scope | Keep API compatibility so client tests can be recreated later if needed. |

## Infrastructure Mapping

| .NET compose service | Spring/infrastructure equivalent | Notes |
| --- | --- | --- |
| `sqldata` / `eshop-sqldata` | SQL Server container, PostgreSQL, or MySQL | SQL Server preserves schema semantics closest to the original. PostgreSQL is acceptable if schemas and behavior are mapped explicitly. |
| `nosqldata` / `eshop-nosqldata` | MongoDB container | Used by Locations and Marketing user-location projection. |
| `basketdata` / `eshop-basketdata` | Redis container | Used by Basket and web data-protection/session equivalents where needed. |
| `rabbitmq` | RabbitMQ management container | Use Spring AMQP with same event names and retry semantics. |
| `seq` | Logging backend | Replace with OpenTelemetry collector, Grafana/Loki, ELK, or keep Seq if desired. |
| `docker-compose*.yml` | `docker-compose.yml` for Spring stack | Keep service names stable where possible: `catalog-api`, `basket-api`, etc., can point to Spring images for easier gateway parity. |
| Kubernetes/Helm assets if used | Spring service deployments | Preserve ports, env vars, health probes, and dependencies. |

## Recommended Java Package Pattern

Use package names that expose bounded contexts clearly:

```text
com.eshop.catalog
  api
  application
  domain
  infrastructure
  integrationevents
  config

com.eshop.ordering
  api
  application.commands
  application.queries
  domain.aggregatesmodel.order
  domain.aggregatesmodel.buyer
  infrastructure
  integrationevents
  grpc
```

Apply the same shape to basket, marketing, locations, payment, and webhooks. Smaller services may omit `domain` if they are CRUD/event handlers only.

## Framework Translation Guide

| .NET pattern/library | Spring Boot equivalent |
| --- | --- |
| ASP.NET Core Controller | `@RestController` or `@Controller` |
| Razor MVC Views | Thymeleaf templates |
| ASP.NET Core Identity / IdentityServer | Keycloak, Spring Authorization Server, or Spring Security OAuth2 client/resource server |
| `[Authorize]` policies | Spring Security method/web authorization |
| Entity Framework Core | Spring Data JPA + Hibernate, with Flyway/Liquibase migrations |
| Dapper read queries | JdbcTemplate, jOOQ, or Spring Data projections |
| MongoDB.Driver | Spring Data MongoDB |
| StackExchange Redis | Spring Data Redis |
| MediatR commands/events | Spring services plus application command handlers; optional command bus abstraction |
| FluentValidation | Jakarta Bean Validation plus custom validators |
| SignalR | Spring WebSocket/STOMP/SockJS |
| gRPC ASP.NET | `grpc-java` with Spring Boot starter, or REST if contracts are intentionally simplified |
| RabbitMQ.Client | Spring AMQP |
| Azure Service Bus client | Spring Cloud Azure Service Bus |
| Polly retry | Resilience4j and Spring Retry |
| Swashbuckle | springdoc-openapi |
| ASP.NET HealthChecks | Spring Boot Actuator |
| Serilog | SLF4J + Logback, Micrometer, OpenTelemetry |
| xUnit | JUnit 5 |
| Moq | Mockito |
| ASP.NET TestServer | Spring Boot Test + MockMvc/WebTestClient |

## Boundary Decisions

### Preserve As Separate Spring Boot Services

Create independently deployable Spring Boot applications for:

- `catalog-service`
- `basket-service`
- `identity-service` or external IdP configuration
- `ordering-service`
- `ordering-background-service`
- `ordering-notification-service`
- `marketing-service`
- `location-service`
- `payment-service`
- `webhooks-service`
- `web-shopping-aggregator`
- `mobile-shopping-aggregator`
- `webmvc-app`
- `webstatus-app`
- `webhook-client-app`

This keeps the microservice topology closest to the .NET deployment.

### Fold Into Shared Libraries

Do not create standalone services for:

- `Ordering.Domain`
- `Ordering.Infrastructure`
- `EventBus`
- `EventBusRabbitMQ`
- `IntegrationEventLogEF`
- `WebHost.Customization`
- `Devspaces.Support`

Translate these into reusable Java packages/modules.

### Optional Or Out Of Scope

- Mobile Xamarin projects are clients, not Spring services.
- Azure Functions marketing infrastructure can be folded into `marketing-service`.
- Azure Service Bus support is optional if RabbitMQ is the selected event bus.
- Envoy can remain Envoy; replacing it with Spring Cloud Gateway is possible but not required for behavioral parity.

## Container Name Compatibility

For easiest migration, keep Docker service names compatible with the original names even if the Java artifact names are cleaner:

| Original compose name | Spring image/artifact |
| --- | --- |
| `catalog-api` | `catalog-service` |
| `basket-api` | `basket-service` |
| `identity-api` | `identity-service` or Keycloak alias |
| `ordering-api` | `ordering-service` |
| `ordering-backgroundtasks` | `ordering-background-service` |
| `ordering-signalrhub` | `ordering-notification-service` |
| `marketing-api` | `marketing-service` |
| `locations-api` | `location-service` |
| `payment-api` | `payment-service` |
| `webhooks-api` | `webhooks-service` |
| `webshoppingagg` | `web-shopping-aggregator` |
| `mobileshoppingagg` | `mobile-shopping-aggregator` |
| `webmvc` | `webmvc-app` |
| `webspa` | `webspa-host` |
| `webstatus` | `webstatus-app` |
| `webhooks-client` | `webhook-client-app` |

Keeping original compose aliases avoids unnecessary changes in gateway configuration, environment variables, health checks, and client-facing URLs.

## Implementation Order

Recommended build order for the Java rewrite:

1. `common/event-bus`, `common/event-log`, `common/web-support`, and baseline security support.
2. `catalog-service` and seed data.
3. `basket-service` with Redis and product-price-change event handling.
4. `identity-service` or Keycloak realm/config plus demo user.
5. `ordering-service` domain, persistence, REST, and gRPC/draft behavior.
6. `ordering-background-service`, `payment-service`, and the checkout/order saga.
7. `web-shopping-aggregator` and `webmvc-app` cart/checkout/order flows.
8. `location-service` and `marketing-service`.
9. `webhooks-service`, `webhook-client-app`, and `ordering-notification-service`.
10. `webstatus-app`, Envoy route parity, and full acceptance suite.

## Cross-Document Traceability

- Use `API_CONTRACTS.md` to implement every REST, BFF, MVC, health, and webhook route.
- Use `DATA_MODEL.md` to create JPA entities, Mongo documents, Redis models, constraints, indexes, and seed data.
- Use `EVENT_CONTRACTS.md` to implement RabbitMQ exchanges, event names, payloads, publishers, subscribers, retries, and outbox behavior.
- Use `UI_BEHAVIOR.md` to recreate web page behavior in Thymeleaf or a separate frontend.
- Use `TEST_SCENARIOS.md` as the acceptance-test backlog for proving the Spring Boot rewrite matches the .NET application.

