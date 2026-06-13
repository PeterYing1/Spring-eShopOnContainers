# eShopOnContainers Requirements

This document describes the requirements implemented by the eShopOnContainers source code. It is written as a source-derived requirements document for the current application, not as a proposal for a new system.

## 1. Purpose

eShopOnContainers shall provide a containerized ecommerce reference application built with .NET Core microservices. The system shall allow users to browse products, manage a shopping basket, authenticate, check out, track orders, receive order notifications, view marketing campaigns, update location information, and subscribe to selected webhook notifications.

The application shall also demonstrate production-oriented architecture concerns such as service autonomy, per-service data ownership, API gateways, backend-for-frontend aggregation, asynchronous integration events, health checks, Docker Compose, Kubernetes deployment assets, and CI/CD pipeline structure.

## 2. Scope

The system includes:

- Web MVC shopping client.
- Angular SPA shopping client.
- Xamarin mobile client code.
- Webhooks demo client.
- Health status dashboard.
- API gateways based on Envoy.
- Web and mobile shopping HTTP aggregators.
- Identity, catalog, basket, ordering, payment, marketing, location, webhooks, ordering background task, and ordering SignalR services.
- Local runtime infrastructure for SQL Server, MongoDB, Redis, RabbitMQ, and Seq.
- Kubernetes/Helm deployment definitions and Azure DevOps build definitions.

The system intentionally simulates some real-world capabilities. In particular, payment processing is a sample event-driven simulation rather than an integration with a real payment provider.

## 3. Users and Actors

- Anonymous visitor: can browse public catalog information through the web experience.
- Authenticated shopper: can manage a basket, check out, view orders, cancel orders, view campaigns, and update location.
- Order operator or administrative caller: can invoke order shipping behavior where authorized.
- Webhook subscriber: can register callback URLs for supported event types.
- External webhook receiver: receives callback payloads from the webhooks service.
- System service: publishes and consumes integration events through the event bus.
- Operator/developer: runs the application locally, monitors health, views logs, tests services, and deploys containers.

## 4. Functional Requirements

### 4.1 Authentication and Identity

- The system shall provide an Identity API as the authority for authentication and authorization.
- The system shall support sign-in and sign-out flows for MVC, SPA, mobile, gateway, and service clients.
- Backend APIs that operate on user-specific data shall require authenticated requests.
- Services shall use the authenticated user identity to scope user-owned resources such as baskets, orders, campaigns, locations, and webhook subscriptions.
- The system shall configure client callback and redirect URLs through environment-specific configuration.

### 4.2 Catalog Browsing and Product Management

- The system shall expose catalog items through an API under `api/v1/catalog`.
- The system shall return paginated catalog item lists.
- The system shall allow catalog items to be retrieved by ID.
- The system shall allow catalog items to be retrieved by a comma-separated list of IDs.
- The system shall allow catalog items to be searched by name prefix.
- The system shall allow catalog items to be filtered by catalog type and catalog brand.
- The system shall expose catalog brands and catalog types.
- The system shall provide product picture URLs, using local or Azure Storage-backed paths depending on configuration.
- The system shall allow catalog items to be created, updated, and deleted.
- When a catalog item price changes, the system shall publish a product price changed integration event.
- The catalog service shall validate stock for orders that enter awaiting validation status and publish stock confirmed or stock rejected events.

### 4.3 Basket Management

- The system shall provide a basket API under `api/v1/basket`.
- Authenticated users shall be able to retrieve their basket.
- Authenticated users shall be able to create or update basket contents.
- Authenticated users shall be able to delete a basket.
- The web client shall allow users to add catalog items to the basket.
- The web client shall allow users to change item quantities in the basket.
- If the basket service is unavailable, the web client shall present an inoperative basket message instead of failing silently.
- Basket checkout shall require a request ID when idempotent behavior is needed.
- Basket checkout shall publish a `UserCheckoutAcceptedIntegrationEvent`.
- Basket checkout shall include buyer identity, address, payment card details, card type, request ID, and basket items.
- The basket service shall clear a checked-out basket when it receives an order-started integration event.
- The basket service shall update basket item prices when it receives product price changed events.

### 4.4 Ordering and Checkout

- The system shall create an order from a checked-out basket through asynchronous integration events.
- The web client shall create an order draft from basket contents before checkout.
- The order draft shall include buyer information and basket line items.
- Authenticated users shall be able to view their order history.
- Authenticated users shall be able to view order details by order ID.
- Authenticated users shall be able to cancel orders through an idempotent command.
- Authorized callers shall be able to mark orders as shipped through an idempotent command.
- The ordering service shall expose supported card types.
- The ordering service shall track order lifecycle states including submitted, awaiting validation, stock confirmed, paid, shipped, and cancelled.
- The ordering service shall publish integration events when order status changes.
- The ordering service shall consume checkout, grace-period, stock-confirmed, stock-rejected, payment-succeeded, and payment-failed events.
- The ordering service shall use command handling and validation for order operations.
- The ordering service shall protect command handling with idempotency using request IDs.

### 4.5 Order Workflow and Payment

- After checkout, the system shall create an order and publish an order-started event.
- The system shall wait for an order grace period before stock validation continues.
- The ordering background task shall periodically publish grace-period confirmation events.
- Catalog shall validate item stock when an order moves to awaiting validation.
- If stock is available, the system shall publish stock confirmed and continue to payment.
- If stock is unavailable, the system shall publish stock rejected and cancel or reject the order flow.
- Payment API shall consume stock-confirmed order events.
- Payment API shall publish either payment succeeded or payment failed events.
- Ordering shall consume payment outcome events and update order status accordingly.

### 4.6 Real-Time Order Notifications

- The system shall provide an Ordering SignalR Hub.
- The SignalR Hub shall subscribe to order status integration events.
- The SignalR Hub shall notify connected clients when order status changes.
- Web clients shall be configurable with the SignalR hub URL.

### 4.7 Marketing Campaigns

- The system shall provide a Marketing API under `api/v1/campaigns`.
- Authenticated users shall be able to retrieve campaigns.
- Authenticated users shall be able to retrieve campaign details by ID.
- The system shall allow campaigns to be created, updated, and deleted.
- The system shall return campaign picture URLs using local or Azure Storage-backed paths depending on configuration.
- The system shall support active-date filtering for campaigns targeted to users.
- The system shall support location-based campaign rules.
- The web client shall show paginated campaigns.
- The web client shall show campaign details.
- The web client shall allow users to submit or update their location so campaign targeting can be recalculated.

### 4.8 Locations

- The system shall provide a Locations API under `api/v1/locations`.
- Authenticated users shall be able to list known locations.
- Authenticated users shall be able to retrieve a location by ID.
- Authenticated users shall be able to retrieve user location data by user ID.
- Authenticated users shall be able to create or update their location.
- When user location data changes, the system shall publish a user location updated integration event.
- Marketing shall consume user location update events and use them for campaign targeting.

### 4.9 Webhooks

- The system shall provide a Webhooks API under `api/v1/webhooks`.
- Authenticated users shall be able to list their webhook subscriptions.
- Authenticated users shall be able to retrieve one of their webhook subscriptions by ID.
- Authenticated users shall be able to create webhook subscriptions.
- Authenticated users shall be able to delete webhook subscriptions.
- When creating a webhook subscription, the system shall validate the grant URL before storing the subscription.
- If grant URL validation fails, the system shall reject the subscription request.
- Webhook subscriptions shall store destination URL, optional token, event type, creation date, and user ID.
- The webhooks service shall consume supported integration events such as product price changes and order status changes.
- The webhooks service shall send callbacks to matching subscription destination URLs.
- The WebhookClient web project shall demonstrate subscription registration and callback receipt.

### 4.10 API Gateways and Aggregation

- The system shall expose web and mobile gateway entry points through Envoy.
- The system shall provide separate gateway configurations for web shopping, web marketing, mobile shopping, and mobile marketing traffic.
- The system shall provide web and mobile shopping aggregators.
- Aggregators shall compose basket, catalog, ordering, and identity data for client-specific shopping workflows.
- Aggregators shall use HTTP and gRPC calls to backend services.
- Aggregators shall expose health checks for dependent catalog, ordering, basket, identity, marketing, payment, and location services.

### 4.11 Web MVC Client

- The MVC client shall display catalog items with brand/type filters and pagination.
- The MVC client shall allow authenticated users to add items to the cart.
- The MVC client shall allow authenticated users to view and update cart quantities.
- The MVC client shall allow authenticated users to begin checkout from the cart.
- The MVC client shall create an order draft before final checkout.
- The MVC client shall submit checkout data to the basket service.
- The MVC client shall allow authenticated users to view order history and order details.
- The MVC client shall allow authenticated users to cancel orders.
- The MVC client shall display campaigns with pagination.
- The MVC client shall display campaign details.
- The MVC client shall allow authenticated users to update their location for campaign targeting.

### 4.12 Web SPA Client

- The SPA client shall provide catalog, basket, campaign, order, identity, and notification functionality.
- The SPA client shall call gateway-facing purchase and marketing URLs.
- The SPA client shall use the Identity API for authentication.
- The SPA client shall use SignalR notifications for order status updates.

### 4.13 Mobile Client

- The mobile client shall use mobile gateway endpoints rather than coupling directly to backend service topology.
- The mobile client shall support shopping flows backed by catalog, basket, ordering, identity, marketing, and location APIs.

### 4.14 Health and Operations UI

- Each service shall expose a health endpoint.
- The WebStatus application shall aggregate health checks for web apps, aggregators, APIs, SignalR hub, and background tasks.
- The local runtime shall expose WebStatus so operators can inspect system health.

## 5. Data Requirements

- Identity data shall be stored in SQL Server.
- Catalog item, brand, type, and stock data shall be stored in SQL Server.
- Ordering data, including orders, buyers, payment methods, card types, order statuses, and idempotency requests, shall be stored in SQL Server.
- Marketing campaign data shall be stored in SQL Server.
- Marketing read/location-targeting data shall be stored in MongoDB.
- User location data shall be stored in MongoDB.
- Basket data shall be stored in Redis.
- Webhook subscription data shall be stored in SQL Server.
- Integration event logs shall be persisted for services that coordinate relational data changes with event publication.
- Local development may run multiple logical databases in a single SQL Server container, but services shall remain logically responsible for their own data.

## 6. Integration Requirements

- Services shall communicate synchronously over HTTP for request/response APIs.
- Catalog, basket, and ordering shall expose gRPC endpoints for internal aggregator use.
- Services shall communicate asynchronously through an event bus.
- RabbitMQ shall be the default local event bus broker.
- Azure Service Bus shall be configurable as an alternative event bus provider.
- Integration events shall be used for cross-service workflows rather than distributed database transactions.
- Services shall publish and subscribe to integration events through shared event bus abstractions.
- Event publication from services with relational state changes shall support an integration event log pattern.
- Gateway configuration shall route client traffic to the correct service or aggregator.

## 7. Configuration Requirements

- The application shall be configurable through `appsettings.json`, environment variables, and Docker Compose overrides.
- Service URLs, external DNS names, path bases, ports, connection strings, event bus settings, and instrumentation keys shall be configurable.
- The system shall support local infrastructure by default and Azure-backed infrastructure through configuration.
- The system shall support Linux container images and include Windows-specific Compose/deployment variants.

## 8. Security Requirements

- APIs that expose user-specific or management behavior shall require authentication.
- Services shall validate access tokens using Identity API configuration.
- User-specific queries shall be scoped to the authenticated user where applicable.
- Webhook subscription operations shall be scoped to the authenticated user.
- Webhook subscription creation shall verify the subscriber grant URL before storing callbacks.
- Sensitive operational values such as database passwords, instrumentation keys, and event bus credentials shall be supplied through configuration rather than hardcoded into application logic.

## 9. Non-Functional Requirements

### 9.1 Deployability

- The system shall be runnable locally with Docker Compose.
- The system shall provide Kubernetes and Helm deployment assets.
- Each deployable service shall have its own Dockerfile.
- CI/CD definitions shall support building individual service images.

### 9.2 Observability

- Services shall expose health endpoints.
- WebStatus shall aggregate service health.
- Services shall use structured logging.
- Local Compose shall include Seq for log viewing.
- Services shall include Application Insights configuration hooks.

### 9.3 Reliability and Resiliency

- Cross-service business workflows shall use asynchronous events to reduce temporal coupling.
- Order-related command execution shall support idempotency through request IDs.
- Integration event publication shall support persisted event logs where transactional consistency with relational data matters.
- Health checks shall detect unhealthy dependencies and services.
- Clients shall handle selected downstream failures gracefully, such as basket service unavailability in the MVC client.

### 9.4 Scalability

- Services shall be independently containerized.
- Gateways and aggregators shall allow client-facing APIs to evolve independently from backend services.
- Stateless API services shall be suitable for horizontal scaling, with state stored in SQL Server, MongoDB, Redis, or the event broker.

### 9.5 Maintainability

- Service boundaries shall be organized by business capability.
- Shared infrastructure concerns shall be placed in building block projects.
- Ordering shall keep domain logic in a domain project and persistence in an infrastructure project.
- Tests shall be organized near the service they validate.

## 10. Runtime Environment Requirements

The local Docker Compose environment shall provide:

- SQL Server on host port `5433`.
- MongoDB on host port `27017`.
- Redis on host port `6379`.
- RabbitMQ on host ports `5672` and `15672`.
- Seq on host port `5340`.
- Web MVC on host port `5100`.
- Web SPA on host port `5104`.
- Identity API on host port `5105`.
- WebStatus on host port `5107`.
- Catalog, ordering, basket, payment, locations, marketing, webhooks, SignalR, background task, gateway, and aggregator services on the ports defined in `src/docker-compose.override.yml`.

## 11. Acceptance Criteria

- A developer can run the system locally from the `src` directory with Docker Compose.
- A user can browse catalog items with pagination and filtering.
- A user can authenticate and add catalog items to a basket.
- A user can update basket quantities and proceed to checkout.
- Checkout creates an order through the event-driven workflow.
- Stock validation and payment simulation are triggered through integration events.
- A user can view their order history and order details.
- A user can cancel an order.
- Order status changes are published and can be observed by SignalR and webhook consumers.
- A user can view marketing campaigns and update location data for targeted campaigns.
- A user can register and remove webhook subscriptions.
- WebStatus displays health information for the major services.
- Services can be built as independent container images.
- The application can be deployed with the provided Kubernetes/Helm assets.

## 12. Out of Scope or Simplified Behavior

- Real payment gateway integration is not implemented; Payment API simulates payment outcomes.
- Full ecommerce administration workflows are limited to the sample APIs present in the code.
- The application is a reference implementation and may include sample data, development credentials, and local-only defaults.
- Local Compose colocates multiple SQL-backed service databases in one SQL Server container for convenience.

## 13. Traceability Notes

These requirements are derived from:

- Service controllers under `src/Services`.
- Web MVC controllers under `src/Web/WebMVC`.
- Aggregator controllers and services under `src/ApiGateways`.
- Docker Compose runtime definitions under `src/docker-compose*.yml`.
- Service startup and integration event registrations.
- Functional and unit test project organization.
- Existing repository documentation and architecture assets.
