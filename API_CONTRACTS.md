# API Contracts

Source of truth: ASP.NET Core controllers, action attributes, model classes, and validators in `src/`.

This document is intended to guide a Java Spring Boot implementation that behaves like the current .NET application. JSON property names below preserve the effective ASP.NET Core/System.Text.Json default contract style: PascalCase .NET properties serialize as camelCase unless a service explicitly returns lower-case query DTO properties.

## Global Conventions

- REST APIs use JSON request and response bodies unless stated otherwise.
- Authenticated APIs require a bearer access token or the equivalent gateway-authenticated principal.
- MVC browser routes use cookie/OpenID Connect authentication and anti-forgery tokens for form posts where `[ValidateAntiForgeryToken]` is present.
- API controllers marked with `[ApiController]` return framework validation errors automatically for invalid model binding.
- Health endpoints are exposed by most services:
  - `GET /hc`: service health checks.
  - `GET /liveness`: liveness probe.
- gRPC-enabled services also expose:
  - `GET /_proto/`: proto descriptor helper route in Catalog, Basket, and Ordering.
- Route prefixes used behind Ocelot/API gateway may add path segments such as `/c`, `/b`, `/o`, `/m`, or `/l`; service-local routes are listed below.

## Shared Schemas

### CatalogItem

```json
{
  "id": 1,
  "name": ".NET Bot Black Hoodie",
  "description": "Sample catalog item",
  "price": 19.50,
  "pictureFileName": "1.png",
  "pictureUri": "http://host/api/v1/catalog/items/1/pic",
  "catalogTypeId": 2,
  "catalogType": { "id": 2, "type": "T-Shirt" },
  "catalogBrandId": 1,
  "catalogBrand": { "id": 1, "brand": ".NET" },
  "availableStock": 100,
  "restockThreshold": 10,
  "maxStockThreshold": 500,
  "onReorder": false
}
```

### PaginatedItemsViewModel<T>

```json
{
  "pageIndex": 0,
  "pageSize": 10,
  "count": 42,
  "data": []
}
```

### CustomerBasket

Validation: each `items[].quantity` must be `>= 1`.

```json
{
  "buyerId": "user-guid-or-name",
  "items": [
    {
      "id": "basket-line-id",
      "productId": 1,
      "productName": ".NET Bot Black Hoodie",
      "unitPrice": 19.50,
      "oldUnitPrice": 0,
      "quantity": 2,
      "pictureUrl": "http://host/api/v1/catalog/items/1/pic"
    }
  ]
}
```

### BasketCheckout

```json
{
  "city": "Redmond",
  "street": "1 Microsoft Way",
  "state": "WA",
  "country": "USA",
  "zipCode": "98052",
  "cardNumber": "4012888888881881",
  "cardHolderName": "Jane Doe",
  "cardExpiration": "2027-12-31T00:00:00Z",
  "cardSecurityNumber": "123",
  "cardTypeId": 1,
  "buyer": "buyer-id",
  "requestId": "00000000-0000-0000-0000-000000000000"
}
```

### Order Query Responses

Order detail uses intentionally lower-case property names from `OrderViewModel.cs`.

```json
{
  "ordernumber": 123,
  "date": "2026-06-13T12:00:00Z",
  "status": "submitted",
  "description": "Order description",
  "street": "1 Microsoft Way",
  "city": "Redmond",
  "zipcode": "98052",
  "country": "USA",
  "orderitems": [
    {
      "productname": ".NET Bot Black Hoodie",
      "units": 2,
      "unitprice": 19.5,
      "pictureurl": "http://host/api/v1/catalog/items/1/pic"
    }
  ],
  "total": 39.0
}
```

```json
{
  "ordernumber": 123,
  "date": "2026-06-13T12:00:00Z",
  "status": "submitted",
  "total": 39.0
}
```

### BFF BasketData

```json
{
  "buyerId": "user-id",
  "items": [
    {
      "id": "line-id",
      "productId": 1,
      "productName": ".NET Bot Black Hoodie",
      "unitPrice": 19.50,
      "oldUnitPrice": 0,
      "quantity": 2,
      "pictureUrl": "http://host/api/v1/catalog/items/1/pic"
    }
  ]
}
```

### BFF OrderData

```json
{
  "orderNumber": "123",
  "date": "2026-06-13T12:00:00Z",
  "status": "submitted",
  "total": 39.0,
  "description": "Order description",
  "city": "Redmond",
  "street": "1 Microsoft Way",
  "state": "WA",
  "country": "USA",
  "zipCode": "98052",
  "cardNumber": "4012888888881881",
  "cardHolderName": "Jane Doe",
  "isDraft": true,
  "cardExpiration": "2027-12-31T00:00:00Z",
  "cardExpirationShort": "12/27",
  "cardSecurityNumber": "123",
  "cardTypeId": 1,
  "buyer": "buyer-id",
  "orderItems": [
    {
      "productId": 1,
      "productName": ".NET Bot Black Hoodie",
      "unitPrice": 19.50,
      "discount": 0,
      "units": 2,
      "pictureUrl": "http://host/api/v1/catalog/items/1/pic"
    }
  ]
}
```

## Catalog.API

Base route: `/api/v1/catalog`. Authentication: none declared on `CatalogController` or `PicController`.

### `GET /api/v1/catalog/items`

Query:

- `pageSize` integer, default `10`.
- `pageIndex` integer, default `0`.
- `ids` optional comma-separated integer list.

Responses:

- `200 OK` with `PaginatedItemsViewModel<CatalogItem>` when `ids` is absent.
- `200 OK` with `CatalogItem[]` when `ids` is present and valid.
- `400 Bad Request` with text `ids value invalid. Must be comma-separated list of numbers` when `ids` contains non-integers or resolves to no items.

Sample:

```http
GET /api/v1/catalog/items?pageSize=2&pageIndex=0
```

```json
{
  "pageIndex": 0,
  "pageSize": 2,
  "count": 12,
  "data": [
    { "id": 1, "name": ".NET Bot Black Hoodie", "price": 19.50, "pictureUri": "http://host/api/v1/catalog/items/1/pic" }
  ]
}
```

### `GET /api/v1/catalog/items/{id:int}`

Validation: `id > 0`.

Responses:

- `200 OK` with `CatalogItem`.
- `400 Bad Request` when `id <= 0`.
- `404 Not Found` when no item exists.

### `GET /api/v1/catalog/items/withname/{name:minlength(1)}`

Query: `pageSize`, `pageIndex`.

Responses:

- `200 OK` with `PaginatedItemsViewModel<CatalogItem>`.
- Framework route rejection/404 if `name` is empty.

Behavior: filters catalog items where `Name.StartsWith(name)`.

### `GET /api/v1/catalog/items/type/{catalogTypeId}/brand/{catalogBrandId:int?}`

Query: `pageSize`, `pageIndex`.

Responses:

- `200 OK` with `PaginatedItemsViewModel<CatalogItem>`.

Behavior: filters by type and optionally brand.

### `GET /api/v1/catalog/items/type/all/brand/{catalogBrandId:int?}`

Query: `pageSize`, `pageIndex`.

Responses:

- `200 OK` with `PaginatedItemsViewModel<CatalogItem>`.

Behavior: filters only by brand when supplied.

### `GET /api/v1/catalog/catalogtypes`

Responses:

- `200 OK` with `[{ "id": 1, "type": "Mug" }]`.

### `GET /api/v1/catalog/catalogbrands`

Responses:

- `200 OK` with `[{ "id": 1, "brand": ".NET" }]`.

### `PUT /api/v1/catalog/items`

Request body: `CatalogItem`.

Responses:

- `201 Created` with `Location` pointing to `ItemByIdAsync`; empty body.
- `404 Not Found` with `{ "message": "Item with id {id} not found." }`.

Behavior: updates the full catalog item. If price changes, publishes `ProductPriceChangedIntegrationEvent`.

### `POST /api/v1/catalog/items`

Request body: `CatalogItem`; only `catalogBrandId`, `catalogTypeId`, `description`, `name`, `pictureFileName`, and `price` are copied.

Responses:

- `201 Created` with `Location` pointing to the created item; empty body.

### `DELETE /api/v1/catalog/{id}`

Responses:

- `204 No Content`.
- `404 Not Found`.

### `GET /api/v1/catalog/items/{catalogItemId:int}/pic`

Validation: `catalogItemId > 0`.

Responses:

- `200 OK` with binary image content. MIME is inferred from the catalog item file extension.
- `400 Bad Request` when `catalogItemId <= 0`.
- `404 Not Found` when no catalog item exists.

Supported MIME mappings: `.png`, `.gif`, `.jpg`, `.jpeg`, `.bmp`, `.tiff`, `.wmf`, `.jp2`, `.svg`; otherwise `application/octet-stream`.

## Basket.API

Base route: `/api/v1/basket`. Authentication: required for all endpoints.

### `GET /api/v1/basket/{id}`

Responses:

- `200 OK` with existing `CustomerBasket`.
- `200 OK` with `{ "buyerId": "{id}", "items": [] }` when no basket exists.

### `POST /api/v1/basket`

Request body: `CustomerBasket`.

Responses:

- `200 OK` with saved `CustomerBasket`.
- Framework validation error when any item has `quantity < 1`.

### `POST /api/v1/basket/checkout`

Headers:

- `x-requestid`: optional GUID. If present and non-empty it overrides `basketCheckout.requestId`.

Request body: `BasketCheckout`.

Responses:

- `202 Accepted` when a basket for the authenticated user exists and checkout event is published.
- `400 Bad Request` when no basket exists for the authenticated user.

Behavior: authenticated user id is authoritative; request body `buyer` is forwarded into the integration event but basket lookup uses the identity service user id.

### `DELETE /api/v1/basket/{id}`

Responses:

- `200 OK` with empty body.

Behavior: deletes basket by id. Method returns `Task` without explicit action result, so ASP.NET Core produces an empty success response.

## Ordering.API

Base route: `/api/v1/orders`. Authentication: required for all endpoints.

### `PUT /api/v1/orders/cancel`

Headers:

- `x-requestid`: required non-empty GUID for command execution.

Request body:

```json
{ "orderNumber": 123 }
```

Validation:

- `orderNumber` must be non-empty/non-zero; message `No orderId found`.
- `x-requestid` must parse as a non-empty GUID.

Responses:

- `200 OK` when command succeeds.
- `400 Bad Request` when request id is invalid, validation fails, or command returns false.

### `PUT /api/v1/orders/ship`

Headers:

- `x-requestid`: required non-empty GUID for command execution.

Request body:

```json
{ "orderNumber": 123 }
```

Validation:

- `orderNumber` must be non-empty/non-zero; message `No orderId found`.

Responses:

- `200 OK` when command succeeds.
- `400 Bad Request` when request id is invalid, validation fails, or command returns false.

### `GET /api/v1/orders/{orderId:int}`

Responses:

- `200 OK` with order detail object using lower-case property names.
- `404 Not Found` if query throws or no order is found.

### `GET /api/v1/orders`

Responses:

- `200 OK` with `OrderSummary[]` for the authenticated user.

### `GET /api/v1/orders/cardtypes`

Responses:

- `200 OK` with `[{ "id": 1, "name": "Amex" }]`.

### `POST /api/v1/orders/draft`

Request body:

```json
{
  "buyerId": "user-id",
  "items": [
    {
      "id": "line-id",
      "productId": 1,
      "productName": ".NET Bot Black Hoodie",
      "unitPrice": 19.5,
      "oldUnitPrice": 0,
      "quantity": 2,
      "pictureUrl": "http://host/api/v1/catalog/items/1/pic"
    }
  ]
}
```

Responses:

- `200 OK` with:

```json
{
  "orderItems": [
    {
      "productId": 1,
      "productName": ".NET Bot Black Hoodie",
      "unitPrice": 19.5,
      "discount": 0,
      "units": 2,
      "pictureUrl": "http://host/api/v1/catalog/items/1/pic"
    }
  ],
  "total": 39.0
}
```

## Marketing.API

Campaign base route: `/api/v1/campaigns`. Authentication: required for campaign and location-rule APIs. Image endpoint has no `[Authorize]`.

### `GET /api/v1/campaigns`

Responses:

- `200 OK` with `CampaignDTO[]`.
- `200 OK` with empty body if the campaign list is null.

`CampaignDTO`:

```json
{
  "id": 1,
  "name": "Campaign",
  "description": "Description",
  "from": "2026-01-01T00:00:00Z",
  "to": "2026-12-31T23:59:59Z",
  "pictureUri": "http://host/api/v1/campaigns/1/pic",
  "detailsUri": "http://function-url&campaignId=1&userId=user-id"
}
```

### `GET /api/v1/campaigns/{id:int}`

Responses:

- `200 OK` with `CampaignDTO`.
- `404 Not Found`.

### `POST /api/v1/campaigns`

Request body: `CampaignDTO`.

Responses:

- `201 Created` with empty body.
- `400 Bad Request` when body is null.

### `PUT /api/v1/campaigns/{id:int}`

Request body: `CampaignDTO`.

Validation: `id >= 1`, body is not null.

Responses:

- `201 Created` with empty body.
- `400 Bad Request`.
- `404 Not Found`.

### `DELETE /api/v1/campaigns/{id:int}`

Validation: `id >= 1`.

Responses:

- `204 No Content`.
- `400 Bad Request`.
- `404 Not Found`.

### `GET /api/v1/campaigns/user`

Query:

- `pageSize` integer, default `10`.
- `pageIndex` integer, default `0`.

Responses:

- `200 OK` with `PaginatedItemsViewModel<CampaignDTO>`.

Behavior: uses authenticated user id to load marketing data, matches campaigns whose date window contains current server time and whose location rule matches the user's locations.

### `GET /api/v1/campaigns/{campaignId:int}/locations/{userLocationRuleId:int}`

Validation: both ids must be `>= 1`.

Responses:

- `200 OK` with `UserLocationRuleDTO`.
- `400 Bad Request`.
- `404 Not Found`.

`UserLocationRuleDTO`:

```json
{ "id": 1, "locationId": 10, "description": "Seattle area" }
```

### `GET /api/v1/campaigns/{campaignId:int}/locations`

Validation: `campaignId >= 1`.

Responses:

- `200 OK` with `UserLocationRuleDTO[]`.
- `200 OK` with empty body if list is null.
- `400 Bad Request`.

### `POST /api/v1/campaigns/{campaignId:int}/locations`

Request body: `UserLocationRuleDTO`.

Validation: `campaignId >= 1`, body is not null.

Responses:

- `201 Created` with empty body.
- `400 Bad Request`.

### `DELETE /api/v1/campaigns/{campaignId:int}/locations/{userLocationRuleId:int}`

Validation: both ids must be `>= 1`.

Responses:

- `204 No Content`.
- `400 Bad Request`.
- `404 Not Found`.

### `GET /api/v1/campaigns/{campaignId:int}/pic`

Responses:

- `200 OK` with `image/png`.

Behavior: reads `{campaignId}.png` from web root. The current implementation does not handle missing files and may return a server error if the image is absent.

## Locations.API

Base route: `/api/v1/locations`. Authentication: required for all endpoints.

### `GET /api/v1/locations/user/{userId:guid}`

Responses:

- `200 OK` with `UserLocation`.

```json
{
  "id": "mongo-object-id",
  "userId": "user-guid",
  "locationId": 10,
  "updateDate": "2026-06-13T12:00:00Z"
}
```

### `GET /api/v1/locations`

Responses:

- `200 OK` with location records.

```json
[
  {
    "id": "mongo-object-id",
    "locationId": 10,
    "code": "SEA",
    "parent_Id": null,
    "description": "Seattle",
    "latitude": 47.6062,
    "longitude": -122.3321,
    "location": {},
    "polygon": {}
  }
]
```

### `GET /api/v1/locations/{locationId}`

Responses:

- `200 OK` with a location record. The service layer determines null/not-found behavior.

### `POST /api/v1/locations`

Request body:

```json
{ "longitude": -122.3321, "latitude": 47.6062 }
```

Responses:

- `200 OK` when user location is created or updated.
- `400 Bad Request` when the service returns false.

Behavior: authenticated user id is used; there is no `userId` in the body.

## Webhooks.API

Base route: `/api/v1/webhooks`. Authentication: required for all endpoints.

### `GET /api/v1/webhooks`

Responses:

- `200 OK` with subscriptions belonging to the authenticated user.

```json
[
  {
    "id": 1,
    "type": "OrderPaid",
    "date": "2026-06-13T12:00:00Z",
    "destUrl": "https://client.example/webhook-received",
    "token": "shared-secret",
    "userId": "user-id"
  }
]
```

### `GET /api/v1/webhooks/{id:int}`

Responses:

- `200 OK` with `WebhookSubscription`.
- `404 Not Found` with text `Subscriptions {id} not found`.

### `POST /api/v1/webhooks`

Request body:

```json
{
  "url": "https://client.example/webhook-received",
  "token": "shared-secret",
  "event": "OrderPaid",
  "grantUrl": "https://client.example/webhook-grant"
}
```

Validation:

- `grantUrl` must be an absolute well-formed URI; error `GrantUrl is not valid`.
- `url` must be an absolute well-formed URI; error `Url is not valid`.
- `event` must parse as one of `CatalogItemPriceChange`, `OrderShipped`, `OrderPaid`; error `{event} is invalid event name`.
- Grant URL must validate through `IGrantUrlTesterService`.

Responses:

- `201 Created` with created `WebhookSubscription`.
- `400 Bad Request`/validation problem when model validation fails.
- `418` with text `Grant url can't be validated`.

### `DELETE /api/v1/webhooks/{id:int}`

Responses:

- `202 Accepted`.
- `404 Not Found` with text `Subscriptions {id} not found`.

## WebhookClient Receiver

Base route: `/webhook-received`.

### `OPTIONS /check`

Headers:

- Webhook check header name comes from `HeaderNames.WebHookCheckHeader`.

Responses:

- `200 OK` when token validation is disabled or the supplied check header matches configured `Token`.
- `200 OK` also echoes the configured token in the same webhook check header when token is not blank.
- `400 Bad Request` with text `Invalid token` when token validation is enabled and token does not match.

### Other methods `/check`

Response: `400 Bad Request`.

### `POST /webhook-received`

Request body shape is `WebhookData`:

```json
{
  "when": "2026-06-13T12:00:00Z",
  "payload": "{ serialized event payload }"
}
```

Headers:

- Webhook check header name comes from `HeaderNames.WebHookCheckHeader`.

Responses:

- `200 OK` with stored `WebHookReceived` when token validation is disabled or token matches configured token.
- `400 Bad Request` when token validation is enabled and token does not match.

## Web/Mobile Shopping BFF Aggregators

There are two aggregators with identical routes:

- `src/ApiGateways/Web.Bff.Shopping/aggregator`
- `src/ApiGateways/Mobile.Bff.Shopping/aggregator`

Base routes: `/api/v1/basket` and `/api/v1/order`. Authentication: required.

### `GET /`

Auth: anonymous.

Response: redirects to `~/swagger`.

### `POST /api/v1/basket` and `PUT /api/v1/basket`

Request body:

```json
{
  "buyerId": "user-id",
  "items": [
    { "id": "line-id", "productId": 1, "quantity": 2 }
  ]
}
```

Validation:

- `items` must not be null or empty; error `Need to pass at least one basket line`.
- Every `productId` must exist in Catalog; error `Basket refers to a non-existing catalog item ({productId})`.

Responses:

- `200 OK` with `BasketData`.
- `400 Bad Request` with text error.

Behavior:

- Retrieves or creates basket by `buyerId`.
- Groups submitted items by `productId` and sums quantities.
- Adds new basket lines or updates existing line quantities.

### `PUT /api/v1/basket/items`

Request body:

```json
{
  "basketId": "user-id",
  "updates": [
    { "basketItemId": "line-id", "newQty": 3 }
  ]
}
```

Validation:

- `updates` must not be empty; error `No updates sent`.
- Basket must exist; error `Basket with id {basketId} not found.`
- Each basket item id must exist; error `Basket item with id {basketItemId} not found`.

Responses:

- `200 OK` with `BasketData`.
- `400 Bad Request` with text error.

### `POST /api/v1/basket/items`

Request body:

```json
{
  "catalogItemId": 1,
  "basketId": "user-id",
  "quantity": 1
}
```

Validation:

- Body must not be null and `quantity != 0`; error `Invalid payload`.

Responses:

- `200 OK` with empty body.
- `400 Bad Request` with text error.

Behavior difference:

- Web BFF: if basket already contains the product, increments the existing quantity.
- Mobile BFF: always appends a new basket line.

### `GET /api/v1/order/draft/{basketId}`

Validation:

- `basketId` must not be null or empty; error `Need a valid basketid`.
- Basket must exist; error `No basket found for id {basketId}`.

Responses:

- `200 OK` with `OrderData`.
- `400 Bad Request` with text error.

## WebMVC Browser Routes

Default route: `/{controller=Catalog}/{action=Index}/{id?}`. Responses are HTML unless noted.

### CatalogController

`GET /Catalog/Index`

Query:

- `BrandFilterApplied` optional integer.
- `TypesFilterApplied` optional integer.
- `page` optional integer.
- `errorMsg` optional string.

Auth: anonymous.

Response: `200 OK` HTML catalog page with catalog items, brands, types, pagination, and optional basket error message.

### CartController

Class auth: OpenID Connect required.

`GET /Cart/Index`

- Response: `200 OK` HTML cart page.

`POST /Cart/Index`

Form fields:

- `quantities`: dictionary from basket line id to quantity.
- `action`: string. If exactly `[ Checkout ]`, redirect to `/Order/Create`.

Responses:

- Redirect to order creation for checkout.
- HTML cart view otherwise.

`GET /Cart/AddToCart`

Query/model-bound fields include `Id` from `CatalogItem`.

Responses:

- Redirect to `/Catalog/Index`.
- Redirect to `/Catalog/Index?errorMsg=...` when basket service fails.

### OrderController

Class auth: OpenID Connect required.

`GET /Order/Create`

- Builds order draft from current user's basket and user profile.
- Response: `200 OK` HTML checkout page.

`POST /Order/Checkout`

Form/model: WebMVC `Order` view model.

Responses:

- Redirect to `/Order/Index` when model is valid and checkout succeeds.
- HTML `Create` view with model errors when invalid or service call fails.

`GET /Order/Cancel?orderId={id}`

- Cancels order, then redirects to `/Order/Index`.

`GET /Order/Detail?orderId={id}`

- Response: `200 OK` HTML order detail.

`GET /Order/Index`

- Response: `200 OK` HTML order history.

### OrderManagementController

Class auth: OpenID Connect required.

`GET /OrderManagement/Index`

- Response: `200 OK` HTML order-management page.

`POST /OrderManagement/OrderProcess`

Form fields:

- `orderId`: string.
- `actionCode`: string. If equal to `OrderProcessAction.Ship.Code`, ships the order.

Response: redirect to `/OrderManagement/Index`.

### CampaignsController

Class auth: OpenID Connect required.

`GET /Campaigns/Index`

Query:

- `page` default `0`.
- `pageSize` default `10`.

Response: `200 OK` HTML campaign page.

`GET /Campaigns/Details/{id}` or `/Campaigns/Details?id={id}`

Responses:

- `200 OK` HTML campaign detail.
- `404 Not Found` when campaign does not exist.

`POST /Campaigns/CreateNewUserLocation`

Form/model: `CampaignViewModel`, notably longitude/latitude fields `Lon` and `Lat`.

Responses:

- Redirect to `/Campaigns/Index` when model is valid.
- HTML index view with validation errors otherwise.

### WebMVC AccountController

Class auth: OpenID Connect required.

`GET /Account/SignIn?returnUrl={url}`

- Logs authenticated user, stores access token in `ViewData["access_token"]` when present, redirects to `/Catalog/Index`.

`GET /Account/Signout`

- Signs out cookie and OpenID Connect schemes, then returns an OpenID Connect sign-out result with redirect to catalog home.

### TestController

Class auth: required.

`GET /Test/Ocelot`

- Calls `http://apigw/shopping/api/v1/basket/items` with sample add-to-basket payload and returns either response body or `{ statusCode, reasonPhrase }`.
- This is a diagnostic endpoint, not part of normal user workflow.

## Identity.API Browser Routes

Default route: `/{controller=Home}/{action=Index}/{id?}`. Responses are HTML/redirects unless noted.

### `GET /Home/Index?returnUrl={url}`

Response: identity home view.

### `GET /Home/ReturnToOriginalApplication?returnUrl={url}`

Responses:

- Redirects to the original application URI extracted from `returnUrl` when present.
- Redirects to `/Home/Index` when `returnUrl` is absent.

### `GET /Home/Error?errorId={id}`

Response: error view populated from IdentityServer error context when available.

### `GET /Account/Login?returnUrl={url}`

Auth: anonymous.

Response: login view.

Behavior:

- If authorization context specifies an external IdP, throws `NotImplementedException("External login is not implemented!")`.
- Login form model has `email`, `password`, `rememberMe`, and `returnUrl`.

### `POST /Account/Login`

Anti-forgery: required.

Form/model:

```json
{
  "email": "user@example.com",
  "password": "Pass@word1",
  "rememberMe": false,
  "returnUrl": "/connect/authorize/..."
}
```

Validation:

- `email` required and must be email.
- `password` required.

Responses:

- Redirect to valid `returnUrl` after successful login.
- Redirect to `~/` when return URL is not valid.
- Login view with model error `Invalid username or password.` on failure.

### `GET /Account/Logout?logoutId={id}`

Responses:

- Logged-out flow when user is not authenticated or sign-out prompt is disabled.
- Logout confirmation view otherwise.

### `POST /Account/Logout`

Anti-forgery: required.

Form/model:

```json
{ "logoutId": "logout-context-id" }
```

Responses:

- Redirect to post-logout redirect URI from IdentityServer logout context.

Behavior: signs out external IdP if present, local authentication cookie, and application scheme.

### `GET /Account/DeviceLogOut?redirectUrl={url}`

Responses:

- Redirect to `redirectUrl` after clearing authentication cookie.

### `GET /Account/Register?returnUrl={url}`

Auth: anonymous.

Response: registration view.

### `POST /Account/Register?returnUrl={url}`

Auth: anonymous. Anti-forgery: required.

Form/model:

```json
{
  "email": "user@example.com",
  "password": "Pass@word1",
  "confirmPassword": "Pass@word1",
  "user": {
    "name": "Jane",
    "lastName": "Doe",
    "cardHolderName": "Jane Doe",
    "cardNumber": "4012888888881881",
    "cardType": 1,
    "city": "Redmond",
    "country": "USA",
    "expiration": "2027-12-31T00:00:00Z",
    "street": "1 Microsoft Way",
    "state": "WA",
    "zipCode": "98052",
    "phoneNumber": "555-0100",
    "securityNumber": "123"
  }
}
```

Validation:

- `email` required and valid email.
- `password` required, length 6 to 100.
- `confirmPassword` must match `password`.
- Additional ASP.NET Identity password/user rules may add errors.

Responses:

- If `returnUrl` is provided and current user is authenticated: redirect to `returnUrl`.
- If `returnUrl` is provided and registration model is valid but user is not authenticated: redirect to `/Account/Login?returnUrl={returnUrl}`.
- If invalid: registration view with errors.
- Without `returnUrl`: redirect to `/Home/Index`.

### `GET /Account/Redirecting`

Response: redirecting view.

### `GET /Consent/Index?returnUrl={url}`

Response:

- Consent view when authorization context, client, and resources are valid.
- Error view otherwise.

### `POST /Consent/Index`

Anti-forgery: required.

Form/model:

```json
{
  "button": "yes",
  "scopesConsented": ["openid", "profile", "orders"],
  "rememberConsent": true,
  "returnUrl": "/connect/authorize/..."
}
```

Validation/behavior:

- `button == "no"` denies consent and redirects to return URL.
- `button == "yes"` requires at least one selected scope; otherwise model error `You must pick at least one permission.`
- Any other button value adds model error `Invalid Selection`.

Responses:

- Redirect to `returnUrl` when consent response is granted/denied.
- Consent view with errors when invalid.
- Error view when consent context cannot be rebuilt.

## WebhookClient Browser Routes

Razor Pages:

- `GET /` or `GET /Index`: displays received webhooks.
- `GET /Privacy`: privacy page.
- `GET /Error`: error page.
- `GET /WebhooksList`: loads current user's webhook subscriptions from Webhooks.API and renders them.
- `GET /RegisterWebhook`: authenticated registration page; initializes token from settings.
- `POST /RegisterWebhook`: authenticated form post. Form field `Token`; constructs `event=OrderPaid`, `grantUrl={selfUrl}/check`, `url={selfUrl}/webhook-received`, and posts to Webhooks.API `POST /api/v1/webhooks`. Redirects to `/WebhooksList` on success; redisplays page with response diagnostics on failure.

### `GET /Account/SignIn?returnUrl={url}`

Auth: required.

Response: redirects to Razor page `/Index`.

### `GET /Account/Signout`

Auth: required.

Response: OpenID Connect sign-out result, redirecting to Razor page `/Index`.

## WebStatus

Default route: `/{controller=Home}/{action=Index}/{id?}`.

### `GET /`

Response: redirects to `{PATH_BASE}/hc-ui`.

### `GET /Config`

Response: JSON/config object produced by `HomeController`. Used by status dashboard.

### `GET /Home/Error`

Response: error view.

## Swagger and Utility Home Routes

The following API services have a default `HomeController.Index` route that redirects to `~/swagger`:

- Catalog.API: `GET /` or `GET /Home/Index`.
- Basket.API: `GET /` or `GET /Home/Index`.
- Ordering.API: `GET /` or `GET /Home/Index`.
- Marketing.API: `GET /` or `GET /Home/Index`.
- Locations.API: `GET /` or `GET /Home/Index`.
- Webhooks.API: `GET /` or `GET /Home/Index`.

## WebSPA Server

### `GET /Home/Configuration`

Auth: anonymous.

Response: JSON serialization of WebSPA `AppSettings`.

## SignalR

Ordering.SignalrHub maps:

- `GET /hub/notificationhub` and WebSocket upgrade path for SignalR clients.

Auth: required on `NotificationHub`.
