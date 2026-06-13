# Event Contracts

Source of truth: integration event classes, event handlers, startup subscriptions, event bus implementations, outbox services, and webhook sender code under `src/`.

This document describes the event-driven contract a Java Spring Boot implementation should preserve.

## Transport Conventions

The application supports two broker implementations:

- RabbitMQ via `EventBusRabbitMQ`.
- Azure Service Bus via `EventBusServiceBus`.

### Common Event Envelope

All integration events inherit from `IntegrationEvent`.

```json
{
  "Id": "1d0f969f-0d77-4db1-830d-7c3f31e35a70",
  "CreationDate": "2026-06-13T12:00:00Z"
}
```

Important serialization detail:

- Events are serialized with Newtonsoft.Json default settings.
- JSON property names are PascalCase, matching .NET property names.
- Read-only get-only properties are serialized by Newtonsoft.

### Message Names

RabbitMQ:

- Exchange: `eshop_event_bus`.
- Exchange type: `direct`.
- Routing key: full class name without namespace, including `IntegrationEvent`.
- Example routing key: `OrderStatusChangedToPaidIntegrationEvent`.
- Queue name: service `SubscriptionClientName`, such as `Ordering`, `Catalog`, `Basket`, `Payment`, `Marketing`, `Webhooks`, `Ordering.signalrhub`.

Azure Service Bus:

- Topic client sends messages with `Label` equal to event class name without suffix `IntegrationEvent`.
- Subscription rules filter by the same suffix-less label.
- On receive, the bus rebuilds the local event name by appending `IntegrationEvent`.
- Example label: `OrderStatusChangedToPaid`.

Interop rule for Spring:

- For RabbitMQ compatibility, publish using the full event class short name as routing key.
- For Azure Service Bus compatibility, publish with suffix-less label and keep JSON payload shape unchanged.

## Publish Reliability

### Outbox-backed publishers

Catalog and Ordering use `IntegrationEventLog` as an outbox.

Outbox states:

- `NotPublished = 0`
- `InProgress = 1`
- `Published = 2`
- `PublishedFailed = 3`

Outbox fields:

- `EventId`
- `EventTypeName`
- `State`
- `TimesSent`
- `CreationTime`
- `Content`
- `TransactionId`

Catalog:

- Saves catalog DB changes and integration event in the same local transaction.
- Marks event `InProgress`, publishes, then marks `Published`.
- On publish exception, marks `PublishedFailed`.

Ordering:

- Saves events to the outbox during the current ordering DB transaction.
- `TransactionBehaviour` commits the transaction, then publishes all `NotPublished` events for that transaction.
- Each event is marked `InProgress`, published, then `Published`; exceptions mark `PublishedFailed`.

### Direct publishers

These publish directly without the outbox:

- Basket checkout publishes `UserCheckoutAcceptedIntegrationEvent`.
- Payment publishes `OrderPaymentSucceededIntegrationEvent` or `OrderPaymentFailedIntegrationEvent`.
- Locations publishes `UserLocationUpdatedIntegrationEvent`.
- Ordering.BackgroundTasks publishes `GracePeriodConfirmedIntegrationEvent`.

If a direct publish throws, behavior depends on caller code. Basket logs and rethrows. Payment, Locations, and BackgroundTasks do not persist an outbox row.

## Retry and Acknowledgement Behavior

RabbitMQ publish retry:

- Retry count defaults to `5`.
- Config key: `EventBusRetryCount`.
- Retries `BrokerUnreachableException` and `SocketException`.
- Backoff: exponential seconds, `2^retryAttempt`.
- Published messages use persistent delivery mode `2`.

RabbitMQ consume behavior:

- Consumers use `autoAck: false`.
- On handler exception, the exception is logged.
- Message is still acknowledged with `BasicAck`.
- The code comment says a real-world app should use a dead letter exchange, but this implementation does not.
- No automatic requeue happens after handler failure.

Azure Service Bus consume behavior:

- `MaxConcurrentCalls = 10`.
- `AutoComplete = false`.
- Message is completed only when `ProcessEvent` returns true.
- Handler exceptions flow through the registered exception handler and are logged.

Webhook outbound behavior:

- Webhook forwarding sends HTTP POST requests concurrently with `Task.WhenAll`.
- There is no explicit retry, dead-letter storage, or response-code handling in `WebhooksSender`.

## Event Catalog

### `ProductPriceChangedIntegrationEvent`

Publisher:

- Catalog.API, when a catalog item update changes price.

Subscribers:

- Basket.API updates matching basket line prices.
- Webhooks.API subscribes, but current handler is a no-op.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "ProductId": 1,
  "NewPrice": 21.50,
  "OldPrice": 19.50
}
```

Basket handler behavior:

- Enumerates all Redis basket keys.
- For each basket item with matching `ProductId`, if `UnitPrice == OldPrice`, sets `OldUnitPrice` to original price and `UnitPrice` to `NewPrice`.
- Saves updated basket back to Redis.

### `UserCheckoutAcceptedIntegrationEvent`

Publisher:

- Basket.API, from `POST /api/v1/basket/checkout`.

Subscriber:

- Ordering.API creates an order through `CreateOrderCommand`.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "UserId": "user-id",
  "UserName": "user@example.com",
  "OrderNumber": 0,
  "City": "Redmond",
  "Street": "1 Microsoft Way",
  "State": "WA",
  "Country": "USA",
  "ZipCode": "98052",
  "CardNumber": "4012888888881881",
  "CardHolderName": "Jane Doe",
  "CardExpiration": "2027-12-31T00:00:00Z",
  "CardSecurityNumber": "123",
  "CardTypeId": 1,
  "Buyer": "buyer-id",
  "RequestId": "0cfa9a8f-73fb-4cfe-a66d-5db83ee80d4d",
  "Basket": {
    "BuyerId": "user-id",
    "Items": [
      {
        "Id": "line-id",
        "ProductId": 1,
        "ProductName": ".NET Bot Black Hoodie",
        "UnitPrice": 19.50,
        "OldUnitPrice": 0,
        "Quantity": 2,
        "PictureUrl": "http://host/api/v1/catalog/items/1/pic"
      }
    ]
  }
}
```

Ordering handler behavior:

- Requires non-empty `RequestId`.
- Builds `CreateOrderCommand` from basket items, user info, address, and card info.
- Wraps it in `IdentifiedCommand<CreateOrderCommand, bool>` using `RequestId`.
- Idempotency is stored in `ordering.requests`.
- If `RequestId` is empty, logs warning and does not create an order.

### `OrderStartedIntegrationEvent`

Publisher:

- Ordering.API, after `CreateOrderCommand` creates an order.

Subscriber:

- Basket.API deletes the buyer's basket.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "UserId": "user-id"
}
```

Basket handler behavior:

- Deletes Redis key equal to `UserId`.

### `OrderStatusChangedToSubmittedIntegrationEvent`

Publisher:

- Ordering.API domain handler after buyer/payment method is validated or created.

Subscriber:

- Ordering.SignalrHub notifies connected clients.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStatus": "submitted",
  "BuyerName": "Jane Doe"
}
```

SignalR behavior:

- Sends `UpdatedOrderState` to group `BuyerName`.
- Message body: `{ "OrderId": 123, "Status": "submitted" }`.

### `GracePeriodConfirmedIntegrationEvent`

Publisher:

- Ordering.BackgroundTasks.

Subscriber:

- Ordering.API sets order status to awaiting validation.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123
}
```

Publisher behavior:

- Background service periodically queries:
  - `ordering.orders`
  - `OrderStatusId = 1`
  - `DATEDIFF(minute, OrderDate, GETDATE()) >= GracePeriodTime`
- For each matching order id, publishes this event.
- Loop delay is `BackgroundTaskSettings.CheckUpdateTime`.

Ordering handler behavior:

- Sends `SetAwaitingValidationOrderStatusCommand(OrderId)`.

### `OrderStatusChangedToAwaitingValidationIntegrationEvent`

Publisher:

- Ordering.API after order status becomes `awaitingvalidation`.

Subscribers:

- Catalog.API checks stock availability.
- Ordering.SignalrHub notifies clients.

Payload published by Ordering.API:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStatus": "awaitingvalidation",
  "BuyerName": "Jane Doe",
  "OrderStockItems": [
    { "ProductId": 1, "Units": 2 }
  ]
}
```

Catalog local contract includes only the fields it consumes:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStockItems": [
    { "ProductId": 1, "Units": 2 }
  ]
}
```

Catalog handler behavior:

- For each `OrderStockItem`, loads catalog item by `ProductId`.
- Sets `HasStock = AvailableStock >= Units`.
- Publishes `OrderStockConfirmedIntegrationEvent` if all items have stock.
- Publishes `OrderStockRejectedIntegrationEvent` if any item lacks stock.
- Saves catalog changes and outgoing event through Catalog outbox.

SignalR behavior:

- Sends `UpdatedOrderState` to group `BuyerName`.

### `OrderStockConfirmedIntegrationEvent`

Publisher:

- Catalog.API, when all requested order items have available stock.

Subscriber:

- Ordering.API sets order status to stock confirmed.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123
}
```

Ordering handler behavior:

- Sends `SetStockConfirmedOrderStatusCommand(OrderId)`.

### `OrderStockRejectedIntegrationEvent`

Publisher:

- Catalog.API, when at least one requested order item lacks stock.

Subscriber:

- Ordering.API sets order status to cancelled because stock was rejected.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStockItems": [
    { "ProductId": 1, "HasStock": false },
    { "ProductId": 2, "HasStock": true }
  ]
}
```

Ordering handler behavior:

- Filters `OrderStockItems` where `HasStock == false`.
- Sends `SetStockRejectedOrderStatusCommand(OrderId, rejectedProductIds)`.

### `OrderStatusChangedToStockConfirmedIntegrationEvent`

Publisher:

- Ordering.API after status becomes `stockconfirmed`.

Subscribers:

- Payment.API simulates payment.
- Ordering.SignalrHub notifies clients.

Payload published by Ordering.API:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStatus": "stockconfirmed",
  "BuyerName": "Jane Doe"
}
```

Payment local contract consumes only:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123
}
```

Payment handler behavior:

- If `PaymentSettings.PaymentSucceeded == true`, publishes `OrderPaymentSucceededIntegrationEvent`.
- Otherwise publishes `OrderPaymentFailedIntegrationEvent`.
- This is a direct publish, not outbox-backed.

SignalR behavior:

- Sends `UpdatedOrderState` to group `BuyerName`.

### `OrderPaymentSucceededIntegrationEvent`

Publisher:

- Payment.API, after simulated successful payment.

Subscriber:

- Ordering.API sets order status to paid.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123
}
```

Ordering handler behavior:

- Sends `SetPaidOrderStatusCommand(OrderId)`.

### `OrderPaymentFailedIntegrationEvent`

Publisher:

- Payment.API, after simulated failed payment.

Subscriber:

- Ordering.API cancels the order.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123
}
```

Ordering handler behavior:

- Sends `CancelOrderCommand(OrderId)`.

### `OrderStatusChangedToPaidIntegrationEvent`

Publisher:

- Ordering.API after order status becomes `paid`.

Subscribers:

- Catalog.API decrements stock.
- Ordering.SignalrHub notifies clients.
- Webhooks.API forwards `OrderPaid` webhooks.

Payload published by Ordering.API:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStatus": "paid",
  "BuyerName": "Jane Doe",
  "OrderStockItems": [
    { "ProductId": 1, "Units": 2 }
  ]
}
```

Catalog and Webhooks local contracts consume:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStockItems": [
    { "ProductId": 1, "Units": 2 }
  ]
}
```

Catalog handler behavior:

- For each item, loads catalog item and calls `RemoveStock(Units)`.
- Saves catalog changes.
- If stock is empty or units are invalid, domain exception can occur; RabbitMQ consumer still acks after logging handler failure.

Webhooks handler behavior:

- Loads subscriptions of type `OrderPaid`.
- Wraps the integration event in `WebhookData`.
- Sends HTTP POST to each subscriber.

SignalR behavior:

- Sends `UpdatedOrderState` to group `BuyerName`.

### `OrderStatusChangedToShippedIntegrationEvent`

Publisher:

- Ordering.API after order status becomes `shipped`.

Subscribers:

- Ordering.SignalrHub notifies clients.
- Webhooks.API forwards `OrderShipped` webhooks.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStatus": "shipped",
  "BuyerName": "Jane Doe"
}
```

Webhooks handler behavior:

- Loads subscriptions of type `OrderShipped`.
- Wraps event in `WebhookData`.
- Sends HTTP POST to each subscriber.

SignalR behavior:

- Sends `UpdatedOrderState` to group `BuyerName`.

### `OrderStatusChangedToCancelledIntegrationEvent`

Publisher:

- Ordering.API after order status becomes `cancelled`.

Subscriber:

- Ordering.SignalrHub notifies clients.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "OrderId": 123,
  "OrderStatus": "cancelled",
  "BuyerName": "Jane Doe"
}
```

SignalR behavior:

- Sends `UpdatedOrderState` to group `BuyerName`.

### `UserLocationUpdatedIntegrationEvent`

Publisher:

- Locations.API, after user location is created or updated.

Subscriber:

- Marketing.API updates the Mongo read model.

Payload:

```json
{
  "Id": "guid",
  "CreationDate": "2026-06-13T12:00:00Z",
  "UserId": "user-id",
  "LocationList": [
    {
      "LocationId": 3,
      "Code": "WHT",
      "Description": "Washington"
    },
    {
      "LocationId": 4,
      "Code": "SEAT",
      "Description": "Seattle"
    }
  ]
}
```

Locations publisher behavior:

- Finds all ordered regions containing the user's current position.
- Updates `UserLocation` Mongo collection.
- Publishes event with mapped `UserLocationDetails`.

Marketing handler behavior:

- Loads `MarketingData` by `UserId`.
- Creates a new `MarketingData` if absent.
- Replaces `Locations` with mapped location list.
- Persists to Mongo `MarketingReadDataModel`.

## Webhook Contracts

Webhooks.API converts some integration events into external HTTP webhooks.

Webhook subscription types:

- `CatalogItemPriceChange = 1`
- `OrderShipped = 2`
- `OrderPaid = 3`

Currently forwarded:

- `OrderStatusChangedToPaidIntegrationEvent` -> `OrderPaid`
- `OrderStatusChangedToShippedIntegrationEvent` -> `OrderShipped`

Subscribed but not forwarded:

- `ProductPriceChangedIntegrationEvent`; handler is a no-op.

Outgoing webhook request:

- Method: `POST`
- URL: subscription `DestUrl`
- Content-Type: `application/json`
- Optional header: `X-eshop-whtoken: {subscription.Token}`

Outgoing body:

```json
{
  "When": "2026-06-13T12:00:00Z",
  "Payload": "{\"OrderId\":123,\"OrderStatus\":\"paid\",\"BuyerName\":\"Jane Doe\",\"Id\":\"guid\",\"CreationDate\":\"2026-06-13T12:00:00Z\"}",
  "Type": "OrderPaid"
}
```

Grant validation before subscription:

- Webhooks.API sends `OPTIONS` to `GrantUrl`.
- Header: `X-eshop-whtoken`.
- `Url` and `GrantUrl` must have same origin.
- Response must be success and must echo the expected token header.

Note: the implementation has a bug in `CheckSameOrigin`; it compares `firstUrl.Host == firstUrl.Host`, so host mismatch is not actually detected. Scheme and port are checked.

## Publisher and Subscriber Matrix

| Event | Publisher | Subscribers |
| --- | --- | --- |
| `ProductPriceChangedIntegrationEvent` | Catalog.API | Basket.API, Webhooks.API no-op |
| `UserCheckoutAcceptedIntegrationEvent` | Basket.API | Ordering.API |
| `OrderStartedIntegrationEvent` | Ordering.API | Basket.API |
| `OrderStatusChangedToSubmittedIntegrationEvent` | Ordering.API | Ordering.SignalrHub |
| `GracePeriodConfirmedIntegrationEvent` | Ordering.BackgroundTasks | Ordering.API |
| `OrderStatusChangedToAwaitingValidationIntegrationEvent` | Ordering.API | Catalog.API, Ordering.SignalrHub |
| `OrderStockConfirmedIntegrationEvent` | Catalog.API | Ordering.API |
| `OrderStockRejectedIntegrationEvent` | Catalog.API | Ordering.API |
| `OrderStatusChangedToStockConfirmedIntegrationEvent` | Ordering.API | Payment.API, Ordering.SignalrHub |
| `OrderPaymentSucceededIntegrationEvent` | Payment.API | Ordering.API |
| `OrderPaymentFailedIntegrationEvent` | Payment.API | Ordering.API |
| `OrderStatusChangedToPaidIntegrationEvent` | Ordering.API | Catalog.API, Ordering.SignalrHub, Webhooks.API |
| `OrderStatusChangedToShippedIntegrationEvent` | Ordering.API | Ordering.SignalrHub, Webhooks.API |
| `OrderStatusChangedToCancelledIntegrationEvent` | Ordering.API | Ordering.SignalrHub |
| `UserLocationUpdatedIntegrationEvent` | Locations.API | Marketing.API |

## Order Saga Flow

1. Basket checkout publishes `UserCheckoutAcceptedIntegrationEvent`.
2. Ordering handles checkout, creates order, validates/adds buyer and payment method.
3. Ordering publishes `OrderStartedIntegrationEvent`; Basket deletes the basket.
4. Ordering publishes `OrderStatusChangedToSubmittedIntegrationEvent`; SignalR notifies client.
5. Background task publishes `GracePeriodConfirmedIntegrationEvent` after grace period.
6. Ordering sets status to `awaitingvalidation` and publishes `OrderStatusChangedToAwaitingValidationIntegrationEvent`.
7. Catalog checks stock.
8. If stock is rejected:
   - Catalog publishes `OrderStockRejectedIntegrationEvent`.
   - Ordering cancels order and publishes `OrderStatusChangedToCancelledIntegrationEvent`.
   - SignalR notifies client.
9. If stock is confirmed:
   - Catalog publishes `OrderStockConfirmedIntegrationEvent`.
   - Ordering sets status to `stockconfirmed` and publishes `OrderStatusChangedToStockConfirmedIntegrationEvent`.
   - Payment simulates payment.
10. If payment fails:
   - Payment publishes `OrderPaymentFailedIntegrationEvent`.
   - Ordering cancels order and publishes `OrderStatusChangedToCancelledIntegrationEvent`.
11. If payment succeeds:
   - Payment publishes `OrderPaymentSucceededIntegrationEvent`.
   - Ordering sets status to `paid` and publishes `OrderStatusChangedToPaidIntegrationEvent`.
   - Catalog decrements stock.
   - Webhooks forwards `OrderPaid`.
   - SignalR notifies client.
12. If order is later shipped:
   - Ordering publishes `OrderStatusChangedToShippedIntegrationEvent`.
   - Webhooks forwards `OrderShipped`.
   - SignalR notifies client.

## Service Subscription Client Names

Runtime defaults from appsettings:

- Basket.API: `Basket`
- Catalog.API: `Catalog`
- Ordering.API: `Ordering`
- Payment.API: `Payment`
- Marketing.API: `Marketing`
- Locations.API: `Locations`
- Webhooks.API: `Webhooks`
- Ordering.SignalrHub: `Ordering.signalrhub`
- Ordering.BackgroundTasks: `BackgroundTasks`

These names become RabbitMQ queue names or Azure Service Bus subscription names.

## Spring Boot Implementation Notes

- Preserve event class short names exactly for broker routing compatibility.
- Preserve PascalCase JSON property names for integration event payloads.
- Include `Id` and `CreationDate` on every event.
- Use idempotency for checkout command processing with `RequestId`.
- Implement an outbox for Catalog and Ordering events if the Spring version needs the same reliability semantics.
- Match RabbitMQ behavior if reproducing exactly: direct exchange `eshop_event_bus`, durable named queues, persistent messages, publish retry, and acknowledge even after handler exceptions.
- Consider adding DLQ/retry improvements only if intentionally diverging from current behavior.

