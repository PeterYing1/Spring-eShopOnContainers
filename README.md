# Spring eShopOnContainers MVP

This is a compact Spring Boot MVP generated from the local specification files in this repository. It preserves the core contracts for catalog browsing, basket management, checkout, order queries, and health endpoints while keeping infrastructure local and simple.

## Run

```powershell
mvn spring-boot:run
```

Then open:

- App UI: <http://localhost:8080>
- H2 console: <http://localhost:8080/h2-console>
- Health: <http://localhost:8080/hc>

Use JDBC URL `jdbc:h2:mem:eshop`, user `sa`, and an empty password for the H2 console.

## MVP Scope

- Catalog REST API under `/api/v1/catalog`
- Basket REST API under `/api/v1/basket`
- Ordering REST API under `/api/v1/orders`
- H2 schema and seed data based on `h2_schema.sql` and `h2_data.sql`
- In-memory basket storage replacing Redis for local MVP use
- Synchronous checkout-to-order creation replacing the event bus for local MVP use
- Static browser UI for browsing items, managing a basket, and checking out

The broader specs in the markdown files remain the backlog for splitting this into independent services with Redis, RabbitMQ, identity, marketing, locations, webhooks, and notification services.
