# Test Scenarios

This document defines user-journey and API-level acceptance tests for recreating the eShopOnContainers behavior in a Java Spring Boot implementation. It complements `REQUIREMENTS.md`, `ARCHITECTUR.md`, `API_CONTRACTS.md`, `DATA_MODEL.md`, `EVENT_CONTRACTS.md`, and `UI_BEHAVIOR.md`.

The scenarios are written as externally observable behavior. A Spring Boot rewrite should pass these tests through HTTP APIs, web UI flows, persistence side effects, and integration-event processing, even if the internal implementation differs.

## Test Environment Assumptions

- Use the seeded demo catalog, identity, order status, card type, marketing campaign, and location data described in `DATA_MODEL.md`.
- Use demo credentials `demouser@microsoft.com` / `Pass@word1` where an authenticated shopper is required.
- Use administrator credentials or an equivalent test principal for order management and catalog/marketing mutation endpoints.
- Use a real or test RabbitMQ-compatible event bus for event-driven tests unless a scenario explicitly says an in-memory bus is acceptable.
- Use Redis-compatible storage for basket acceptance tests, or a test double that preserves Redis semantics for key-based basket retrieval and replacement.
- Use SQL persistence for catalog, ordering, identity, marketing, and event log assertions.
- Use MongoDB-compatible persistence for user locations and user-location history.
- For asynchronous flows, poll until the expected state appears or a timeout is reached. The original functional tests commonly poll up to about 20 attempts with short delays.
- API tests should assert status code, content type, response shape, validation behavior, auth requirement, and durable side effects.

## Acceptance Data

Recommended stable test inputs:

| Name | Value |
| --- | --- |
| Buyer id | `9e3163b9-1ae6-4652-9dc6-7898ab7b7a00` |
| Alternate buyer id | `JohnId` |
| Demo user | `demouser@microsoft.com` |
| Demo password | `Pass@word1` |
| Product id | Any seeded catalog item id, commonly `1` |
| Card type id | A seeded card type id, commonly `1` |
| Valid expiration | A future date, for UI entry use `12/30` or later |
| Invalid latitude | `91` |
| Invalid longitude | `181` |
| Seattle latitude | `47.60461` |
| Seattle longitude | `-122.315752` |

## User Journey Acceptance Tests

### UJ-001 Anonymous User Browses Catalog

Given an anonymous browser is on the catalog home page,
when the page loads,
then the catalog items page is displayed with seeded products, brand filter options, type filter options, pagination controls, product names, product prices, product images, and add-to-cart actions.

Expected API calls:

- `GET /api/v1/catalog/catalogbrands`
- `GET /api/v1/catalog/catalogtypes`
- `GET /api/v1/catalog/items?pageIndex=0&pageSize=...`

Assertions:

- The page does not require login.
- Catalog item count, page index, page size, and item data match the API response.
- Product image URLs resolve through the catalog picture endpoint.
- Selecting a brand and type filter reloads the catalog with the selected filter values.
- Pagination keeps the selected filter state.

### UJ-002 Anonymous User Attempts To Add Item To Cart

Given an anonymous browser is viewing a product in the catalog,
when the user activates the add-to-cart action,
then the app requires authentication before basket mutation.

Assertions:

- The user is redirected to the login/sign-in flow or otherwise blocked by the authenticated route requirement.
- No basket is created or mutated for the anonymous browser.
- After successful login, the user can return to the catalog and add the product.

### UJ-003 User Logs In And Logs Out

Given a browser is unauthenticated,
when the user signs in with `demouser@microsoft.com` and `Pass@word1`,
then the user returns to the MVC application with an authenticated session and identity claims usable by the BFF and downstream APIs.

Assertions:

- Login rejects missing or invalid credentials with validation messages.
- Login succeeds with demo credentials.
- The authenticated header/navigation state is shown.
- Logout clears the authenticated session.
- Protected pages such as cart, order history, checkout, and campaigns require login after logout.

### UJ-004 Authenticated User Adds Catalog Item To Cart

Given an authenticated user is viewing a catalog item,
when the user adds quantity `1` of the item to the cart,
then the cart contains that item with product id, product name, current unit price, picture URL, quantity, and zero or absent old unit price.

Expected API calls:

- `POST /api/v1/basket/items` through the WebMVC BFF
- Downstream basket persistence through `POST /api/v1/basket`

Assertions:

- The request uses the authenticated user's buyer id.
- The response redirects back to the catalog or updates the cart state according to the UI flow.
- The cart page shows the added product.
- Adding the same product again increases quantity or replaces basket data according to the BFF contract, without creating duplicate inconsistent rows.

### UJ-005 User Updates Basket Quantities

Given an authenticated user has a basket with one or more products,
when the user changes product quantities and selects Update,
then the basket is persisted with the new quantities and totals are recalculated.

Assertions:

- Quantity inputs reject values below `1`.
- Removed or zero-quantity behavior matches the existing UI/BFF contract.
- `PUT /api/v1/basket/items` or `PUT /api/v1/basket` updates the basket.
- The cart page displays updated line totals and basket total.
- Product id, product name, unit price, and picture URL are preserved.

### UJ-006 User Sees Price Change Warning In Basket

Given a user has a basket containing catalog item A with unit price P1,
when an administrator changes item A price to P2 through the catalog API,
then the user's basket eventually shows item A with unit price P2 and old unit price P1.

Expected event flow:

- Catalog publishes `ProductPriceChangedIntegrationEvent`.
- Basket consumes the event and updates every matching basket item.

Assertions:

- `GET /api/v1/basket/{buyerId}` returns the updated item.
- `unitPrice == P2`.
- `oldUnitPrice == P1`.
- The cart UI shows a price-change indication for that item.

### UJ-007 Authenticated User Checks Out Successfully

Given an authenticated user has a non-empty basket,
when the user opens checkout,
then an order draft is displayed with basket items and editable shipping/payment fields.

When the user submits valid shipping and payment data,
then the basket checkout API accepts the request, an order is created asynchronously, and the order appears in order history.

Expected API calls:

- `GET /api/v1/order/draft/{basketId}` or equivalent BFF call for the checkout page.
- `POST /api/v1/basket/checkout` with buyer, address, card, card type, and request id.
- `GET /api/v1/orders` to observe the created order.
- `GET /api/v1/orders/{orderId}` to observe order details.

Assertions:

- Checkout requires authentication.
- Request id is a unique idempotency value.
- The initial order status is `submitted`.
- Order detail preserves buyer, address, card metadata, order items, quantities, unit prices, and totals from the basket.
- Basket is deleted after `OrderStartedIntegrationEvent` is processed.
- UI redirects to order history or success state.

### UJ-008 Checkout Validation Errors

Given an authenticated user is on the checkout page,
when required shipping or payment fields are missing or invalid,
then the app blocks submission and shows validation errors.

Validation assertions:

- Street, city, state, country, zip code, card number, card holder name, card expiration, card security number, and card type are required.
- Card expiration entered in the UI must match `MM/YY`.
- Card expiration must be in the future.
- Card type must be a seeded card type id.
- Invalid forms must not call `POST /api/v1/basket/checkout`.

### UJ-009 User Views And Cancels Submitted Order

Given an authenticated user has an order in status `submitted`,
when the user opens order history,
then the order list shows order number, date, status, total, and detail action.

When the user opens the order detail,
then the order lines and shipping data are displayed.

When the user cancels the submitted order,
then the order status changes to `cancelled`.

Expected API calls:

- `GET /api/v1/orders`
- `GET /api/v1/orders/{orderId}`
- `PUT /api/v1/orders/cancel`

Assertions:

- Cancel is available only for orders in `submitted` status.
- The cancel request includes the target order number/id.
- After cancellation, `GET /api/v1/orders/{orderId}` or the order list shows status `cancelled`.
- Cancelling an order not owned by the user is forbidden.
- Cancelling an order in a non-cancellable state is rejected.

### UJ-010 Order Manager Ships Paid Order

Given an authenticated order-management user views a paid order,
when the manager selects Ship,
then the order status changes to `shipped`.

Expected API calls:

- MVC `POST /OrderManagement/Ship`
- Downstream `PUT /api/v1/orders/ship`

Assertions:

- Order management requires the configured management role or policy.
- Ship is available only for `paid` orders.
- Shipped orders no longer expose a ship action.
- A shipped order may publish webhook and SignalR notifications as described in `EVENT_CONTRACTS.md`.

### UJ-011 User Updates Location And Sees Campaigns

Given an authenticated user opens the campaigns page,
when the user submits latitude `47.60461` and longitude `-122.315752`,
then the location API stores the user location and the marketing API eventually returns campaigns for that location.

Expected API calls:

- `POST /api/v1/locations`
- `GET /api/v1/campaigns`
- `GET /api/v1/campaigns/user`
- `GET /api/v1/campaigns/{id}`

Assertions:

- Latitude must be between `-90` and `90`.
- Longitude must be between `-180` and `180`.
- Invalid coordinates show validation errors and do not update the location.
- The campaigns list contains at least one seeded campaign when rules match.
- Campaign details show name, description, image, and enabled/disabled state.

### UJ-012 Webhook Subscriber Receives Paid Order Notification

Given a webhook subscription exists for the paid-order event,
when an order reaches `paid`,
then the webhook client receives and records a webhook notification.

Expected API calls:

- `POST /api/v1/webhooks` to register a subscription.
- `POST /webhook-received` from the webhook service to the client.
- `GET /api/v1/webhooks` to verify registration.

Assertions:

- Subscription creation requires authentication.
- Destination URL and event name are required.
- The webhook payload contains the integration event id, creation date, event name, and order data.
- Duplicate webhook deliveries are either idempotently recorded or visible as separate received attempts according to the existing client behavior.

### UJ-013 SignalR Order Status Notification

Given an authenticated user is viewing any page with the SignalR client connected,
when an order status update for that user is published,
then the UI displays a notification.

Assertions:

- The SignalR connection is established only for authenticated users.
- The client handles `UpdatedOrderState`.
- If the user is on an order page, the page reloads or refreshes order state.
- If the user is on another page, a toast/notification is shown without losing current navigation state.

## API-Level Acceptance Tests

### API-001 Catalog Items List

Request:

```http
GET /api/v1/catalog/items?pageSize=10&pageIndex=0
```

Expected:

- `200 OK`.
- Response body is a paginated object with `pageIndex`, `pageSize`, `count`, and `data`.
- Each item includes id, name, description, price, picture URI or filename, catalog type id, catalog brand id, available stock, restock threshold, max stock threshold, and on-reorder flag as exposed by the .NET API.
- `pageSize` and `pageIndex` control pagination.
- Missing query values use service defaults.

### API-002 Catalog Item By Id

Request:

```http
GET /api/v1/catalog/items/{id}
```

Expected:

- Existing item returns `200 OK` and one catalog item.
- Missing item returns the .NET-compatible not-found behavior.
- Invalid non-integer id is rejected by routing or validation.

### API-003 Catalog Item By Name

Request:

```http
GET /api/v1/catalog/items/withname/{name}
```

Expected:

- Name with at least one character is accepted.
- Matching item returns `200 OK`.
- Empty name does not match the route.

### API-004 Catalog Filters And Lookups

Requests:

```http
GET /api/v1/catalog/catalogtypes
GET /api/v1/catalog/catalogbrands
GET /api/v1/catalog/items/type/{catalogTypeId}/brand/{catalogBrandId}
GET /api/v1/catalog/items/type/all/brand/{catalogBrandId}
```

Expected:

- Lookup endpoints return seeded types and brands.
- Filtered list returns paginated data.
- Unknown type or brand returns an empty page, not unrelated items.

### API-005 Catalog Mutation And Price Change Event

Given an authorized catalog administrator,
when the administrator updates an item price through:

```http
PUT /api/v1/catalog/items
```

Expected:

- Valid update returns success.
- The catalog row is updated.
- Price change publishes `ProductPriceChangedIntegrationEvent`.
- Basket items for the changed product are eventually updated with `unitPrice` equal to the new price and `oldUnitPrice` equal to the prior price.
- Unauthorized requests are rejected.

Also verify:

- `POST /api/v1/catalog/items` creates an item with valid required fields.
- `DELETE /api/v1/catalog/{id}` removes an item or marks it unavailable according to source behavior.
- Invalid fields such as negative price or invalid type/brand are rejected.

### API-006 Catalog Picture

Request:

```http
GET /api/v1/catalog/items/{catalogItemId}/pic
```

Expected:

- Existing picture returns image content with an image content type.
- Missing picture returns the configured placeholder or not-found behavior.
- Image URLs returned in catalog responses resolve through this endpoint.

### API-007 Basket Read And Replace

Given a basket payload with customer id and items,
when the client calls:

```http
POST /api/v1/basket
GET /api/v1/basket/{customerId}
```

Expected:

- Basket creation/update returns success and the persisted basket.
- Read returns the same customer id and item list.
- Unknown customer id returns an empty basket, `null`, or not-found according to existing basket API behavior.
- Item quantity, product id, product name, unit price, old unit price, and picture URL round-trip without loss.

### API-008 Basket Checkout

Request:

```http
POST /api/v1/basket/checkout
```

With a body containing buyer, request id, address, card fields, card type id, and basket-derived order items.

Expected:

- Valid checkout returns accepted/success status.
- `UserCheckoutAcceptedIntegrationEvent` is published.
- Missing basket, missing buyer, invalid request id, or invalid required fields are rejected.
- Reusing the same request id does not create duplicate orders.

### API-009 Basket Delete

Request:

```http
DELETE /api/v1/basket/{customerId}
```

Expected:

- Existing basket is removed.
- Subsequent `GET /api/v1/basket/{customerId}` reflects deletion.
- Repeated delete is idempotent or returns the existing .NET-compatible status.

### API-010 BFF Basket Add And Quantity Update

Requests:

```http
POST /api/v1/basket/items
PUT /api/v1/basket/items
PUT /api/v1/basket
GET /api/v1/basket
```

Expected:

- All endpoints require the authenticated MVC user.
- Add item appends or increments the user's basket.
- Quantity update changes only the selected item quantities.
- Full basket update replaces the user's basket with the supplied item state.
- Responses and redirects match the MVC flow documented in `UI_BEHAVIOR.md`.

### API-011 Ordering Draft

Request:

```http
POST /api/v1/orders/draft
```

Expected:

- Requires authentication.
- Accepts basket items and buyer data.
- Returns a draft order with address/payment defaults and line totals.
- Empty basket or invalid item data is rejected.

### API-012 Ordering List And Detail

Requests:

```http
GET /api/v1/orders
GET /api/v1/orders/{orderId}
```

Expected:

- Requires authentication.
- List returns only orders owned by the authenticated user unless called by an authorized management principal.
- Detail returns the requested order with items, address, status, date, and total.
- Requesting another user's order is forbidden or not found.
- Missing order returns not found.

### API-013 Ordering Cancel

Request:

```http
PUT /api/v1/orders/cancel
```

Expected:

- Requires authentication.
- Submitted orders transition to `cancelled`.
- Nonexistent order returns not found.
- Orders already paid, shipped, or cancelled are rejected.
- The cancellation is durable and visible in list/detail APIs.

### API-014 Ordering Ship

Request:

```http
PUT /api/v1/orders/ship
```

Expected:

- Requires order-management authorization.
- Paid orders transition to `shipped`.
- Submitted, awaiting validation, cancelled, or nonexistent orders are rejected.
- Shipped transition can trigger webhook and SignalR notifications.

### API-015 Ordering Card Types

Request:

```http
GET /api/v1/orders/cardtypes
```

Expected:

- Returns seeded card types.
- Checkout UI uses these values.
- Unknown card type ids are rejected during checkout/order creation.

### API-016 Locations API

Requests:

```http
GET /api/v1/locations
GET /api/v1/locations/{locationId}
GET /api/v1/locations/user/{userId}
POST /api/v1/locations
```

Expected:

- Create validates latitude and longitude bounds.
- Create stores the current user location and appends history.
- Get by user returns the latest or configured user location record.
- Location-created/updated integration event is published for marketing.
- Missing location id returns not found.

### API-017 Marketing Campaigns API

Requests:

```http
GET /api/v1/campaigns
GET /api/v1/campaigns/{id}
GET /api/v1/campaigns/user
POST /api/v1/campaigns
PUT /api/v1/campaigns/{id}
DELETE /api/v1/campaigns/{id}
GET /api/v1/campaigns/{campaignId}/pic
```

Expected:

- Public list/detail endpoints return enabled seeded campaigns.
- User campaigns reflect user-location rules.
- Create/update/delete require authorization.
- Invalid date ranges, missing names, invalid discounts, or invalid picture data are rejected.
- Campaign picture endpoint returns image content or configured missing-image behavior.

### API-018 Campaign Location Rules API

Requests:

```http
GET /api/v1/campaigns/{campaignId}/locations
GET /api/v1/campaigns/{campaignId}/locations/{userLocationRuleId}
POST /api/v1/campaigns/{campaignId}/locations
DELETE /api/v1/campaigns/{campaignId}/locations/{userLocationRuleId}
```

Expected:

- Rule endpoints require authorization.
- Rules belong to the requested campaign.
- Latitude/longitude or area rule values are validated.
- Deleting a rule removes it from subsequent user-campaign matching.

### API-019 Webhooks API

Requests:

```http
GET /api/v1/webhooks
GET /api/v1/webhooks/{id}
POST /api/v1/webhooks
DELETE /api/v1/webhooks/{id}
```

Expected:

- Requires authentication.
- List returns only subscriptions owned by the authenticated user.
- Create validates destination URL and event type.
- Delete removes only the caller's subscription.
- Webhook delivery uses the registered URL and event type.

### API-020 Health And Home Endpoints

Requests:

```http
GET /
GET /hc
GET /liveness
GET /Config
```

Expected:

- Home endpoints return a simple service identity page or configured status response.
- Health endpoints report readiness/liveness for dependencies used by each service.
- WebStatus `/Config` returns the UI health-check configuration.

## Event-Driven Acceptance Tests

### EV-001 Checkout Creates Order And Clears Basket

Given a buyer has a basket,
when `POST /api/v1/basket/checkout` succeeds,
then Basket publishes `UserCheckoutAcceptedIntegrationEvent`.

When Ordering consumes the event,
then it creates an order in status `submitted` and publishes `OrderStartedIntegrationEvent`.

When Basket consumes `OrderStartedIntegrationEvent`,
then it deletes the buyer basket.

Assertions:

- Event ids are unique.
- Event creation dates are populated.
- Ordering uses the checkout request id for idempotency.
- Replaying the same checkout event does not create a duplicate order.
- Basket deletion is safe to replay.

### EV-002 Grace Period Moves Submitted Order To Awaiting Validation

Given an order is created in `submitted`,
when the grace period expires,
then Ordering publishes the validation command/event and transitions the order to `awaitingvalidation` according to the original saga.

Assertions:

- The transition is durable.
- The transition is not run twice for the same order.
- The next stock-validation step receives all order item product ids and quantities.

### EV-003 Stock Confirmed Path Leads Toward Payment

Given an order is awaiting validation and Catalog has sufficient stock,
when Catalog handles the stock-confirmation request,
then Catalog publishes stock-confirmed event data for the order.

When Ordering consumes stock-confirmed data,
then the order moves to the next payment-related state.

Assertions:

- Catalog checks available stock for every order item.
- The event includes order id and validated item data.
- No stock is decremented yet unless the original flow decrements at this stage; final stock decrement is covered by EV-005.

### EV-004 Stock Rejected Path Cancels Order

Given an order is awaiting validation and at least one item lacks stock,
when Catalog handles the stock-validation request,
then Catalog publishes stock-rejected event data.

When Ordering consumes stock-rejected data,
then the order becomes `cancelled`.

Assertions:

- The cancelled order is visible in `GET /api/v1/orders`.
- No payment success flow runs for the cancelled order.
- Basket remains deleted after checkout, matching the original service behavior.

### EV-005 Payment Succeeded Path Pays Order And Decrements Stock

Given an order has passed stock validation,
when Payment publishes payment-succeeded event data,
then Ordering marks the order `paid`.

When Catalog consumes the paid-order/stock-decrement event,
then Catalog decrements stock for each ordered product.

Assertions:

- Order status is `paid`.
- Catalog available stock is reduced by ordered quantities.
- Stock never becomes negative.
- Paid-order webhook subscribers receive a notification.
- Authenticated UI clients receive an order-status SignalR update.

### EV-006 Payment Failed Path Cancels Order

Given an order has passed stock validation,
when Payment publishes payment-failed event data,
then Ordering marks the order `cancelled`.

Assertions:

- The order is not marked paid.
- Catalog stock is not decremented as a paid order.
- The cancellation is visible in order history.
- User notification follows the same status-update mechanism as other order state changes.

### EV-007 Product Price Change Updates Existing Baskets

Given a catalog item has price P1 and appears in a user's basket,
when the catalog item is updated to price P2,
then Catalog publishes `ProductPriceChangedIntegrationEvent`.

When Basket consumes the event,
then every basket item with the product id is updated to P2 and preserves P1 as old unit price.

Assertions:

- The catalog API returns P2.
- The basket API returns P2 and old unit price P1.
- The cart UI indicates the price change.
- Replaying the same event does not keep shifting old unit price incorrectly.

### EV-008 User Location Update Refreshes Marketing Campaign Eligibility

Given a user submits a new location,
when Locations persists the location,
then it publishes a user-location event.

When Marketing consumes the event,
then `GET /api/v1/campaigns/user` reflects campaigns matching the new location.

Assertions:

- The event contains user id, latitude, longitude, and creation/update timestamp.
- Marketing stores or updates the user-location rule projection.
- The campaign result changes when the user's location changes across rule boundaries.

### EV-009 Integration Event Log Persists And Marks Events

Given a service publishes an integration event inside a database transaction,
when the transaction succeeds,
then the event is recorded in the service integration event log.

When the event bus publish succeeds,
then the event log status is updated to published.

Assertions:

- Failed database transactions do not publish orphan events.
- Failed event-bus publish attempts leave the event available for retry.
- Retried publish does not create duplicate business side effects in subscribers.

### EV-010 Event Bus Retry And Dead Letter Behavior

Given a subscriber throws a transient exception while handling an event,
when the event bus retry policy runs,
then the event is retried according to the configured RabbitMQ/event-bus policy.

Assertions:

- Messages are acknowledged only after successful handling.
- Transient failures are retried.
- Poison messages do not block unrelated event handling forever.
- Subscriber handlers are idempotent for duplicate delivery.
- Logs include event id, event name, handler, and failure reason.

## Security And Authorization Acceptance Tests

### SEC-001 Public Versus Protected Endpoints

Assertions:

- Catalog browse, catalog pictures, public campaigns, health, and home endpoints are publicly accessible.
- Basket, checkout, orders, order management, webhooks, user campaigns, and user location flows require authentication where documented.
- Catalog and marketing mutation endpoints require administrator authorization.
- Order shipping requires order-management authorization.
- A user cannot read, cancel, ship, delete, or mutate another user's protected resource.

### SEC-002 Token And Cookie Propagation

Given a user is authenticated in WebMVC,
when WebMVC calls Basket, Ordering, Marketing, Locations, or Webhooks,
then the downstream service receives the authenticated identity or access token needed to enforce ownership and policy.

Assertions:

- Missing token returns unauthorized.
- Invalid token returns unauthorized.
- Valid token with insufficient role returns forbidden.
- Buyer/user id claims map consistently across MVC, BFF, and service APIs.

## Compatibility Assertions For The Spring Boot Rewrite

- Preserve route paths, HTTP methods, status-code semantics, and JSON field names from `API_CONTRACTS.md`.
- Preserve persisted table/document fields and relationship behavior from `DATA_MODEL.md`.
- Preserve event names, payload fields, casing, publisher/subscriber relationships, and retry/idempotency requirements from `EVENT_CONTRACTS.md`.
- Preserve user-visible page flow, button behavior, validation messages, redirects, and authentication behavior from `UI_BEHAVIOR.md`.
- Preserve asynchronous eventual-consistency behavior; tests should poll observable APIs instead of assuming synchronous event handling.
- Preserve idempotency for checkout and event consumption.
- Preserve seeded demo data sufficiently for these scenarios to run repeatably.

## Minimum Regression Suite

A practical first-pass acceptance suite for the Java Spring Boot implementation should include:

1. UJ-001 anonymous catalog browse.
2. UJ-004 authenticated add to cart.
3. UJ-005 update basket quantities.
4. UJ-007 successful checkout.
5. UJ-009 order list/detail/cancel.
6. UJ-011 location-driven campaigns.
7. API-001 through API-004 catalog reads.
8. API-007 through API-009 basket lifecycle.
9. API-011 through API-015 ordering lifecycle.
10. EV-001 checkout creates order and clears basket.
11. EV-007 product price change updates baskets.
12. SEC-001 public/protected endpoint boundaries.

