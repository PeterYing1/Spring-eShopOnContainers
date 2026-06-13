# Business Rules

This document captures the business behavior of eShopOnContainers as "what happens when..." rules. It is intended for recreating the application in Java Spring Boot while preserving the original .NET behavior.

Use this together with `API_CONTRACTS.md`, `DATA_MODEL.md`, `EVENT_CONTRACTS.md`, `UI_BEHAVIOR.md`, `TEST_SCENARIOS.md`, and `DOTNET_TO_SPRING_MAPPING.md`.

## Global Rules

### BR-GEN-001 Public And Protected Areas

- Catalog browsing, catalog images, public campaign browsing, home pages, health checks, and liveness checks are public.
- Basket, checkout, order history, order detail, order cancellation, order shipping, user-location updates, user campaign lookup, and webhook subscription management require authentication unless a test harness explicitly bypasses auth.
- Admin or management actions such as catalog mutation, campaign mutation, and order shipping require elevated authorization in the Spring rewrite, even where the sample .NET app relies partly on gateway/UI protection.

### BR-GEN-002 User Identity

- Authenticated business operations use the authenticated user identity as the buyer/customer id.
- Basket checkout ignores any arbitrary customer id supplied by the caller and reads the current user identity from the request principal.
- Order list queries return orders for the authenticated user identity.
- Downstream BFF/API calls must propagate the user identity or access token.

### BR-GEN-003 Eventual Consistency

- Checkout, order workflow, stock validation, payment, basket deletion, stock decrement, webhook dispatch, and UI notifications are asynchronous.
- A successful request often means "the command/event was accepted," not "all downstream work is complete."
- Acceptance tests should poll observable APIs for final state rather than assuming immediate completion.

### BR-GEN-004 Idempotency

- Commands that create or change order state use `x-requestid` as an idempotency key.
- If `x-requestid` is missing, empty, or not a valid non-empty GUID, order cancel/ship endpoints return `400 Bad Request`.
- Duplicate valid request ids for order commands are treated as successful no-ops.
- Checkout events include a request id so Ordering can ignore duplicate order creation requests.
- Event handlers must be safe for duplicate delivery.

## Catalog Rules

### BR-CAT-001 Catalog Listing

When a user browses catalog items:

- Items are sorted by name for the base catalog listing.
- Default pagination is `pageSize=10` and `pageIndex=0`.
- The response includes page index, page size, total count, and page data.
- Product picture URLs are filled before returning catalog items.
- If Azure storage is enabled, picture URI is `PicBaseUrl + PictureFileName`.
- If Azure storage is disabled, picture URI is `PicBaseUrl` with `[0]` replaced by the catalog item id.

### BR-CAT-002 Catalog Filtering

When a user filters catalog items:

- Filtering by type returns only items whose `CatalogTypeId` matches.
- Filtering by brand returns only items whose `CatalogBrandId` matches.
- Filtering by both type and brand applies both constraints.
- Filtering by name uses `StartsWith(name)`.
- Unknown filters return an empty page, not an error.

### BR-CAT-003 Catalog Items By Id List

When `ids` is supplied to the catalog list endpoint:

- The value must be a comma-separated list of integers.
- If any id is not an integer, the endpoint returns `400 Bad Request` with the invalid ids message.
- If parsing succeeds, the endpoint returns the matching items.
- Missing ids simply produce fewer returned items.

### BR-CAT-004 Catalog Item Detail

When a catalog item is requested by id:

- Id values less than or equal to zero return `400 Bad Request`.
- Existing items return `200 OK`.
- Missing items return `404 Not Found`.
- Returned items have picture URLs filled.

### BR-CAT-005 Catalog Create

When a catalog item is created:

- A new row is inserted using brand id, type id, description, name, picture filename, and price from the request.
- The service returns `201 Created`.
- The created item can be retrieved through the item detail endpoint.
- The create operation does not publish a price-change event.

### BR-CAT-006 Catalog Update

When a catalog item is updated:

- If the item id does not exist, return `404 Not Found` with a message containing the missing id.
- If the item exists, replace the stored catalog item with the submitted data.
- Return `201 Created` pointing to the item detail endpoint.
- If the price did not change, save the catalog update only.
- If the price changed, save the catalog update and a `ProductPriceChangedIntegrationEvent` atomically through the integration-event log, then publish the event.

### BR-CAT-007 Catalog Delete

When a catalog item is deleted:

- If the item id does not exist, return `404 Not Found`.
- If the item exists, remove the row and return `204 No Content`.
- Deletion is hard delete in the sample service.

### BR-CAT-008 Stock Validation

When Catalog receives an order awaiting validation:

- For each ordered product, find the catalog item by product id.
- The item has stock if `AvailableStock >= Units`.
- If every item has stock, publish `OrderStockConfirmedIntegrationEvent`.
- If any item lacks stock, publish `OrderStockRejectedIntegrationEvent` with per-item stock confirmation results.
- This validation step does not decrement stock.

### BR-CAT-009 Stock Decrement After Payment

When Catalog receives an order paid event:

- For each paid order item, call `RemoveStock(units)`.
- If available stock is zero, raise a catalog domain error: the item is sold out.
- If requested units are less than or equal to zero, raise a catalog domain error.
- If requested units exceed available stock, remove only what is available.
- Save catalog stock changes after all items are processed.

### BR-CAT-010 Stock Replenishment

When stock is added to a catalog item:

- Stock may not exceed `MaxStockThreshold`.
- If the requested addition would exceed the maximum threshold, only add enough units to reach the maximum.
- Adding stock clears `OnReorder`.
- The method returns the number of units actually added.

## Basket Rules

### BR-BAS-001 Basket Ownership

When Basket API operations run:

- The Basket API requires authentication.
- Basket ids are buyer/customer ids.
- The checkout operation uses the authenticated user id, not a caller-supplied id.
- A missing basket read returns an empty `CustomerBasket` for the requested id.

### BR-BAS-002 Basket Create Or Replace

When a basket is posted:

- The submitted `CustomerBasket` is saved as the current basket for its buyer id.
- The response returns the persisted basket.
- Existing basket contents are replaced by the submitted content according to the Redis repository behavior.

### BR-BAS-003 Add Item From MVC

When an authenticated user adds a catalog item from the MVC catalog page:

- The MVC app calls the basket service/BFF with the authenticated user.
- If the product id is present, the item is added to the user's basket.
- The user is redirected back to the catalog page.
- If the basket service is unavailable or circuit-opened, the user is redirected to the catalog page with a basket-inoperative message.

### BR-BAS-004 Update Quantities From Cart

When an authenticated user submits the cart form:

- The MVC app sends the provided quantity dictionary to the basket service.
- Updated quantities are persisted before deciding the next page.
- If the action is `[ Checkout ]`, redirect to order creation.
- Otherwise, remain on or return to the cart view.
- UI quantity controls should prevent quantities below `1`; service/domain logic should still reject invalid quantities where applicable.

### BR-BAS-005 Basket Checkout

When an authenticated user checks out:

- Basket API loads the basket for the authenticated user id.
- If the basket does not exist, return `400 Bad Request`.
- If the basket exists, build `UserCheckoutAcceptedIntegrationEvent` using:
  - authenticated user id,
  - authenticated user name claim,
  - shipping address fields,
  - card fields,
  - card type id,
  - buyer data,
  - request id,
  - full basket contents.
- Publish the checkout event.
- Return `202 Accepted`.
- Do not synchronously create the order in Basket.
- Do not synchronously delete the basket in Basket checkout.

### BR-BAS-006 Basket Deletion After Order Starts

When Basket receives `OrderStartedIntegrationEvent`:

- Delete the basket for the event user id.
- Repeated deletion is safe.
- Basket deletion happens after Ordering has accepted/started order creation.

### BR-BAS-007 Price Change Propagation

When Basket receives `ProductPriceChangedIntegrationEvent`:

- Load all known basket user ids from the repository.
- For each basket, find items whose product id matches the changed product.
- Update an item only if its current `UnitPrice` equals the event `OldPrice`.
- Set `UnitPrice` to the event `NewPrice`.
- Set `OldUnitPrice` to the original price.
- Save each basket that had matching items.
- If the item price already differs from `OldPrice`, do not update it; this prevents repeated or stale events from corrupting basket prices.

## Checkout Rules

### BR-CHK-001 Checkout Page

When an authenticated user opens checkout:

- The MVC app requests an order draft for the user's basket.
- The app maps user profile information into the order view model.
- Card expiration is formatted for short UI display.
- If the user has no basket, the UI should block or fail gracefully according to the basket/order draft response.

### BR-CHK-002 Checkout Validation

When checkout is submitted:

- Street is required.
- City is required.
- State is required.
- Country is required.
- Zip code is required.
- Card number is required and must be 12 to 19 characters.
- Card holder name is required.
- Card expiration is required and must be greater than or equal to current UTC time.
- Card security number is required and must be exactly 3 characters.
- Card type id is required.
- At least one order item is required.
- The MVC UI should not submit invalid forms; APIs still validate commands.

### BR-CHK-003 Checkout Failure

When checkout fails in MVC:

- The app adds a model error: `It was not possible to create a new order, please try later on (...)`.
- The checkout page is redisplayed with the submitted model.
- No redirect to order history occurs.

### BR-CHK-004 Checkout Success

When checkout succeeds in MVC:

- The mapped order is sent to Basket checkout.
- The user is redirected to order history.
- The final order may not appear immediately because order creation is event-driven.

## Ordering Rules

### BR-ORD-001 Order Creation

When Ordering handles a checkout event:

- It creates an `OrderStartedIntegrationEvent` for Basket cleanup and saves it through the integration-event log.
- It creates an address value object from street, city, state, country, and zip code.
- It creates a new order in status `submitted`.
- It sets the order date to current UTC time.
- It raises an order-started domain event carrying user id, user name, card type, card number, security number, holder name, and expiration.
- It adds each checkout basket item through the order aggregate.
- It saves the order and domain changes transactionally.

### BR-ORD-002 Order Item Rules

When adding an order item:

- Units must be greater than zero for a new order item.
- Discount must not exceed `unitPrice * units`.
- If the same product already exists in the order, merge with the existing line.
- If the new discount is greater than the existing line discount, replace the line discount.
- Add the new units to the existing line.
- Adding negative units to an existing line is invalid.
- The order total is the sum of `units * unitPrice` for all lines; discounts are not subtracted by `GetTotal()` in the current domain method.

### BR-ORD-003 Buyer And Payment Method

When an order starts:

- Ordering verifies or creates the buyer aggregate for the user identity.
- If the buyer already has a payment method with the same card type, card number, and expiration, reuse it.
- If no matching payment method exists, create one.
- Card number, security number, and card holder name must not be blank.
- Payment expiration must not be in the past.
- A buyer/payment-method verified domain event updates the order with buyer id and payment method id.

### BR-ORD-004 Order Status Values

The only valid order statuses are:

1. `submitted`
2. `awaitingvalidation`
3. `stockconfirmed`
4. `paid`
5. `shipped`
6. `cancelled`

Unknown status ids or names are domain errors.

### BR-ORD-005 Submitted To Awaiting Validation

When the grace period is confirmed:

- Ordering loads the order.
- If the order is missing, the command returns false and the API/event handler treats it as failed or bad request depending on the entry point.
- If the order is currently `submitted`, transition it to `awaitingvalidation`.
- Raise an order-status-changed-to-awaiting-validation domain event.
- Publish an integration event containing order stock items for Catalog validation.
- If the order is not `submitted`, the transition is ignored by the domain method.

### BR-ORD-006 Awaiting Validation To Stock Confirmed

When Catalog confirms stock:

- Ordering delays about 10 seconds in the sample command handler to simulate processing.
- Ordering loads the order.
- If missing, return false.
- If the order is currently `awaitingvalidation`, transition it to `stockconfirmed`.
- Set description to `All the items were confirmed with available stock.`
- Raise a stock-confirmed domain event.
- Publish an integration event for Payment.
- If the order is not `awaitingvalidation`, the transition is ignored by the domain method.

### BR-ORD-007 Awaiting Validation To Cancelled Because Of Stock

When Catalog rejects stock:

- Ordering delays about 10 seconds in the sample command handler to simulate processing.
- Ordering loads the order.
- If missing, return false.
- If the order is currently `awaitingvalidation`, transition it to `cancelled`.
- Set the description to list product names that do not have stock.
- No payment step should run for this order.
- If the order is not `awaitingvalidation`, the transition is ignored by the domain method.

### BR-ORD-008 Stock Confirmed To Paid

When Payment succeeds:

- Ordering delays about 10 seconds in the sample command handler to simulate payment validation.
- Ordering loads the order.
- If missing, return false.
- If the order is currently `stockconfirmed`, transition it to `paid`.
- Set the description to the simulated bank-payment message.
- Raise a paid domain event.
- Publish an order-paid integration event with stock items so Catalog can decrement stock.
- If the order is not `stockconfirmed`, the transition is ignored by the domain method.

### BR-ORD-009 Payment Failed To Cancelled

When Payment fails:

- Ordering loads the order through the payment-failed event handler/command path.
- If the order can be cancelled, transition it to `cancelled`.
- Paid or shipped orders cannot be cancelled.
- The cancelled status is visible in order history.

### BR-ORD-010 Customer Cancellation

When a user cancels an order:

- The request must include a valid non-empty `x-requestid`.
- The command must include a non-empty order number.
- If the order is missing, the command returns false and the API returns `400 Bad Request`.
- If the order is `paid` or `shipped`, cancellation throws a domain exception and must be rejected.
- Other states can transition to `cancelled`.
- A cancelled domain event is raised.
- A cancelled integration event is saved/published.

### BR-ORD-011 Shipping

When an order manager ships an order:

- The request must include a valid non-empty `x-requestid`.
- The command must include a non-empty order number.
- If the order is missing, the command returns false and the API returns `400 Bad Request`.
- Only `paid` orders can transition to `shipped`.
- Shipping any non-paid order throws a domain exception and must be rejected.
- A shipped domain event is raised.
- A shipped integration event is saved/published.

### BR-ORD-012 Order Queries

When an authenticated user queries orders:

- `GET /api/v1/orders` returns order summaries for the authenticated user.
- `GET /api/v1/orders/{id}` returns order details when found.
- If order detail lookup fails, return `404 Not Found`.
- `GET /api/v1/orders/cardtypes` returns all seeded card types.

## Payment Rules

### BR-PAY-001 Simulated Payment

When Payment receives `OrderStatusChangedToStockConfirmedIntegrationEvent`:

- It does not contact a real payment gateway.
- It reads `PaymentSettings.PaymentSucceeded`.
- If `PaymentSucceeded` is true, publish `OrderPaymentSucceededIntegrationEvent`.
- If `PaymentSucceeded` is false, publish `OrderPaymentFailedIntegrationEvent`.
- The event contains the order id.
- Payment publishes the result immediately after handling the stock-confirmed event.

### BR-PAY-002 Payment Assumptions

- Payment authorization, capture, fraud checks, refunds, and chargebacks are outside the sample app.
- Card data is handled as demo data and is not PCI-compliant.
- Payment success/failure is an environment/configuration toggle, not a real business decision.
- The Spring rewrite should preserve the simulation unless a later requirement replaces it with a real gateway.

## Webhook And Notification Rules

### BR-NOT-001 Order Status Notifications

When order status changes to paid, shipped, or cancelled:

- Ordering raises domain and integration events for the status change.
- Webhook subscribers for matching event types receive webhook delivery attempts.
- Authenticated browser clients connected to the ordering notification hub receive status updates.
- The MVC UI refreshes order pages or shows a toast according to `UI_BEHAVIOR.md`.

### BR-NOT-002 Webhook Delivery

When a webhook event is delivered:

- The subscriber must have an active subscription for the event type.
- The registered destination URL is called by the webhook service.
- Delivery should be retried according to event bus/webhook retry policy.
- Duplicate webhook delivery must not corrupt receiver state.

## Error Handling Rules

### BR-ERR-001 Validation Errors

When model validation fails:

- Return `400 Bad Request`.
- Basket validation errors return a JSON object with `messages`.
- Catalog domain validation errors return validation problem details with `DomainValidations`.
- Ordering command validation errors should be surfaced as bad request responses.

### BR-ERR-002 Domain Errors

When a domain rule is violated:

- Basket domain exceptions return `400 Bad Request`.
- Catalog domain exceptions return `400 Bad Request`.
- Ordering domain exceptions should be mapped to `400 Bad Request` or an equivalent client-error response in the Spring rewrite.
- The error response should not expose stack traces outside development mode.

### BR-ERR-003 Unexpected Errors

When an unexpected exception occurs:

- Log the exception with service context.
- Return `500 Internal Server Error`.
- In development mode, include developer exception details.
- Outside development mode, return a generic message such as `An error occurred. Try it again.`

### BR-ERR-004 Not Found

When requested data does not exist:

- Catalog item detail returns `404 Not Found`.
- Catalog update/delete missing item returns `404 Not Found`.
- Catalog picture missing file returns `404 Not Found`.
- Ordering detail lookup failure returns `404 Not Found`.
- Basket read of a missing basket returns an empty basket instead of not found.

### BR-ERR-005 Downstream Service Failure In MVC

When Basket service fails while the MVC cart/catalog flow is running:

- The MVC controller catches the exception.
- It sets `Basket Service is inoperative {ExceptionType} - {Message}` as the user-facing message.
- Add-to-cart redirects back to catalog with the error message.
- Cart index returns the cart view without crashing the process.

## Cross-Service "What Happens When" Summary

### User Checks Out

1. User submits checkout from MVC.
2. MVC validates the order view model.
3. MVC maps order data to a basket checkout payload.
4. Basket verifies a basket exists for the authenticated user.
5. Basket publishes `UserCheckoutAcceptedIntegrationEvent`.
6. Basket returns `202 Accepted`.
7. Ordering consumes the checkout event.
8. Ordering creates an order in `submitted`.
9. Ordering publishes `OrderStartedIntegrationEvent`.
10. Basket consumes `OrderStartedIntegrationEvent` and deletes the basket.
11. Ordering background processing eventually moves the order to `awaitingvalidation`.
12. Catalog validates stock.
13. Payment simulates success or failure.
14. Ordering marks the order `paid` or `cancelled`.
15. Catalog decrements stock only after paid.
16. Webhooks and UI notifications are sent for relevant status changes.

### Product Price Changes

1. Catalog item update detects old price differs from new price.
2. Catalog saves the product change and event log entry atomically.
3. Catalog publishes `ProductPriceChangedIntegrationEvent`.
4. Basket scans user baskets.
5. Matching basket items are updated only if their current unit price equals the event old price.
6. Basket records the original price in `OldUnitPrice`.
7. Cart UI shows the updated price and old price warning.

### Stock Is Missing

1. Ordering asks Catalog to validate stock for an awaiting-validation order.
2. Catalog checks `AvailableStock >= Units` for each item.
3. Catalog publishes `OrderStockRejectedIntegrationEvent` if any item lacks stock.
4. Ordering transitions the order from `awaitingvalidation` to `cancelled`.
5. Ordering description names the products without stock.
6. Payment is not processed for the cancelled order.
7. The cancelled order appears in order history.

### Payment Is Successful

1. Ordering reaches `stockconfirmed`.
2. Payment receives the stock-confirmed event.
3. Payment publishes success because `PaymentSucceeded=true`.
4. Ordering transitions the order to `paid`.
5. Ordering publishes paid status event.
6. Catalog decrements stock.
7. Webhooks and UI notifications can be sent.

### Payment Fails

1. Ordering reaches `stockconfirmed`.
2. Payment receives the stock-confirmed event.
3. Payment publishes failure because `PaymentSucceeded=false`.
4. Ordering cancels the order if it is not paid or shipped.
5. Catalog does not decrement stock as a paid order.
6. The user sees the cancelled status in order history.

