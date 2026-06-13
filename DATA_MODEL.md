# Data Model

Source of truth: EF Core `DbContext` and entity configuration classes, Mongo context/model classes, Redis repository code, migrations, and the generated `h2_schema.sql` / `h2_data.sql`.

This document describes the data stores that a Java Spring Boot implementation should reproduce. For exact executable H2 DDL and test/demo inserts, use `h2_schema.sql` and `h2_data.sql` as companion files.

## Storage Boundaries

The application is split across several persistence boundaries:

- Catalog: relational SQL tables `Catalog`, `CatalogBrand`, `CatalogType`.
- Basket: Redis string values keyed by buyer id.
- Ordering: relational SQL schema `ordering` plus `IntegrationEventLog`.
- Identity: ASP.NET Identity tables plus IdentityServer configuration and persisted grant tables.
- Marketing: relational SQL tables `Campaign`, `Rule`; Mongo read model `MarketingReadDataModel`.
- Locations: Mongo collections `Locations`, `UserLocation`.
- Webhooks: relational SQL table `Subscriptions`.

## Catalog Relational Model

Context: `CatalogContext`.

### `CatalogBrand`

Entity: `CatalogBrand`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server uses HiLo sequence `catalog_brand_hilo`; H2 uses identity |
| `Brand` | varchar(100) | no | Required, max length 100 |

Seed data:

- Runtime defaults: `Azure`, `.NET`, `Visual Studio`, `SQL Server`, `Other`.
- H2/test data also includes `CatalogBrandTestOne`, `CatalogBrandTestTwo`.

### `CatalogType`

Entity: `CatalogType`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server uses HiLo sequence `catalog_type_hilo`; H2 uses identity |
| `Type` | varchar(100) | no | Required, max length 100 |

Seed data:

- Runtime defaults: `Mug`, `T-Shirt`, `Sheet`, `USB Memory Stick`.
- H2/test data also includes `CatalogTypeTestOne`, `CatalogTypeTestTwo`.

### `Catalog`

Entity: `CatalogItem`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server uses HiLo sequence `catalog_hilo`; H2 uses identity |
| `Name` | varchar(50) | no | Required, max length 50 |
| `Description` | varchar(255) | yes | Optional in SQL; model property is plain string |
| `Price` | decimal(18,2) | no | Required |
| `PictureFileName` | varchar(255) | yes | Optional |
| `CatalogTypeId` | int | no | FK to `CatalogType.Id` |
| `CatalogBrandId` | int | no | FK to `CatalogBrand.Id` |
| `AvailableStock` | int | no | H2 default `0`; model field represents current stock |
| `RestockThreshold` | int | no | H2 default `0` |
| `MaxStockThreshold` | int | no | H2 default `0` |
| `OnReorder` | boolean | no | H2 default `false` |

Ignored/computed property:

- `PictureUri` is ignored by EF and built at read time from `PicBaseUrl` and `PictureFileName`.

Relationships:

- Many `Catalog` rows belong to one `CatalogBrand`.
- Many `Catalog` rows belong to one `CatalogType`.
- H2 DDL uses `ON DELETE CASCADE` for both FKs.

Indexes:

- `IX_Catalog_CatalogBrandId` on `Catalog(CatalogBrandId)`.
- `IX_Catalog_CatalogTypeId` on `Catalog(CatalogTypeId)`.

Domain constraints:

- `RemoveStock(quantityDesired)` throws if stock is empty or `quantityDesired <= 0`.
- `AddStock(quantity)` caps stock at `MaxStockThreshold` and clears `OnReorder`.

Seed data:

- Runtime default seed inserts 12 catalog items using the five default brands and four default types.
- `h2_data.sql` inserts 13 items; item 13 is test/demo data named `pepito`.
- Runtime customization mode can seed from `Setup/CatalogBrands.csv`, `Setup/CatalogTypes.csv`, `Setup/CatalogItems.csv`, and extract `Setup/CatalogItems.zip` into the image folder.

## Basket Redis Model

Repository: `RedisBasketRepository`.

There is no basket SQL table in the .NET implementation. Each basket is stored as a Redis string:

- Redis key: `CustomerBasket.BuyerId`.
- Redis value: JSON serialization of `CustomerBasket`.
- Delete: `KeyDeleteAsync(id)`.
- List users: enumerates Redis keys.

### `CustomerBasket`

| Field | Type | Null | Constraints |
| --- | --- | --- | --- |
| `BuyerId` | string | yes | Used as Redis key when saving |
| `Items` | array of `BasketItem` | no | Defaults to empty list |

### `BasketItem`

| Field | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | string | yes | Basket line id |
| `ProductId` | int | no | Catalog item id |
| `ProductName` | string | yes | Denormalized catalog name |
| `UnitPrice` | decimal | no | Denormalized current price |
| `OldUnitPrice` | decimal | no | Previous price when catalog price changes |
| `Quantity` | int | no | Must be `>= 1`; validation error `Invalid number of units` |
| `PictureUrl` | string | yes | Denormalized catalog picture URL |

Checkout data is not persisted in Basket. `BasketCheckout` is an API payload that becomes an integration event.

## Ordering Relational Model

Context: `OrderingContext`.

Default schema: `ordering`.

### `ordering.cardtypes`

Entity: `CardType`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; value generated never; EF default value `1` |
| `Name` | varchar(200) | no | Required, max length 200 |

Seed data:

- Runtime enum seed: `1 Amex`, `2 Visa`, `3 MasterCard`.
- H2/test seed also includes `4 Capital One`.
- Customization mode can seed from `Setup/CardTypes.csv`.

### `ordering.orderstatus`

Entity: `OrderStatus`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; value generated never; EF default value `1` |
| `Name` | varchar(200) | no | Required, max length 200 |

Seed data:

- `1 submitted`
- `2 awaitingvalidation`
- `3 stockconfirmed`
- `4 paid`
- `5 shipped`
- `6 cancelled`

Customization mode can seed from `Setup/OrderStatus.csv`.

### `ordering.buyers`

Entity: `Buyer`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server HiLo sequence `buyerseq`; H2 identity |
| `IdentityGuid` | varchar(200) | no | Required, max length 200, unique |
| `Name` | varchar(255) | yes | Optional in EF config; domain constructor requires non-empty |

Indexes:

- Unique `IX_buyers_IdentityGuid` on `IdentityGuid`.

Relationships:

- One buyer has many payment methods.
- Deleting a buyer cascades to `paymentmethods`.

Domain constraints:

- `Buyer(identity, name)` rejects null/blank identity and name.
- `VerifyOrAddPaymentMethod` reuses an existing payment method with same card type, card number, and expiration.

### `ordering.paymentmethods`

Entity: `PaymentMethod`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server HiLo sequence `paymentseq`; H2 identity |
| `Alias` | varchar(200) | no | Required, max length 200 |
| `BuyerId` | int | no | FK to `ordering.buyers.Id` |
| `CardHolderName` | varchar(200) | no | Required, max length 200 |
| `CardNumber` | varchar(25) | no | Required, max length 25 |
| `CardTypeId` | int | no | FK to `ordering.cardtypes.Id` |
| `Expiration` | timestamp | no | Required |

Relationships:

- Many payment methods belong to one buyer, cascade delete.
- Many payment methods use one card type.

Indexes:

- `IX_paymentmethods_BuyerId` on `BuyerId`.
- `IX_paymentmethods_CardTypeId` on `CardTypeId`.

Domain constraints:

- `cardNumber`, `securityNumber`, and `cardHolderName` must be non-blank.
- `expiration` must be greater than or equal to current UTC time.
- Security number is validated in the domain constructor but is not persisted in `paymentmethods`.

### `ordering.orders`

Entity: `Order`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server HiLo sequence `orderseq`; H2 identity |
| `BuyerId` | int | yes | FK to `ordering.buyers.Id` |
| `Description` | varchar(255) | yes | Optional status/change description |
| `OrderDate` | timestamp | no | Required |
| `OrderStatusId` | int | no | FK to `ordering.orderstatus.Id` |
| `PaymentMethodId` | int | yes | FK to `ordering.paymentmethods.Id` |
| `Street` | varchar(255) | yes | Owned `Address.Street` |
| `City` | varchar(255) | yes | Owned `Address.City` |
| `State` | varchar(255) | yes | Owned `Address.State` |
| `Country` | varchar(255) | yes | Owned `Address.Country` |
| `ZipCode` | varchar(255) | yes | Owned `Address.ZipCode` |

Relationships:

- Optional many orders to one buyer.
- Required many orders to one order status.
- Optional many orders to one payment method; EF uses restrict delete.
- One order has many order items.

Indexes:

- `IX_orders_BuyerId` on `BuyerId`.
- `IX_orders_OrderStatusId` on `OrderStatusId`.
- `IX_orders_PaymentMethodId` on `PaymentMethodId`.

Domain constraints and lifecycle:

- New real orders start with status `submitted`, current UTC `OrderDate`, and an `OrderStartedDomainEvent`.
- Draft orders are transient (`_isDraft = true`) and not used as a persisted status field.
- Valid status transitions:
  - `submitted` -> `awaitingvalidation`.
  - `awaitingvalidation` -> `stockconfirmed`.
  - `stockconfirmed` -> `paid`.
  - `paid` -> `shipped`.
  - `awaitingvalidation` -> `cancelled` when stock is rejected.
  - Cancel is rejected from `paid` or `shipped`.
- Status changes update `Description` with human-readable messages.

### `ordering.orderItems`

Entity: `OrderItem`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server HiLo sequence `orderitemseq`; H2 identity |
| `Discount` | decimal(18,2) | no | Required |
| `OrderId` | int | no | FK to `ordering.orders.Id` |
| `PictureUrl` | varchar(255) | yes | Optional |
| `ProductId` | int | no | Required; denormalized catalog item id |
| `ProductName` | varchar(255) | no | Required; denormalized catalog item name |
| `UnitPrice` | decimal(18,2) | no | Required; denormalized catalog price |
| `Units` | int | no | Required |

Relationships:

- Many order items belong to one order.
- Deleting an order cascades to order items.

Indexes:

- `IX_orderItems_OrderId` on `OrderId`.

Domain constraints:

- Constructor rejects `units <= 0`.
- Constructor rejects `unitPrice * units < discount`.
- `SetNewDiscount` rejects negative discounts.
- `AddUnits` rejects negative units.

### `ordering.requests`

Entity: `ClientRequest`.

Used for idempotent command handling.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | uuid | no | Primary key; request id from `x-requestid` |
| `Name` | varchar(255) | no | Required command name |
| `Time` | timestamp | no | Required |

### `IntegrationEventLog`

Context: `IntegrationEventLogContext`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `EventId` | uuid | no | Primary key |
| `Content` | clob/text | no | Serialized event JSON |
| `CreationTime` | timestamp | no | Required |
| `EventTypeName` | varchar(255) | no | Required |
| `State` | int | no | Required event state enum |
| `TimesSent` | int | no | Required publish attempt count |
| `TransactionId` | varchar(255) | yes | Added by later migration; links event to local transaction |

Used by Catalog and Ordering integration-event publishing to coordinate database updates with event bus publication.

## Identity Relational Model

Contexts:

- `ApplicationDbContext`: ASP.NET Identity user store with custom `ApplicationUser`.
- IdentityServer `ConfigurationDbContext`: clients, API resources, identity resources, scopes, secrets, redirect URIs.
- IdentityServer `PersistedGrantDbContext`: device codes and persisted grants.

For exact IdentityServer DDL, use `h2_schema.sql`, because the generated tables mirror IdentityServer EF migrations.

### `AspNetUsers`

Entity: `ApplicationUser`.

Identity columns:

| Column | Type | Null | Notes |
| --- | --- | --- | --- |
| `Id` | varchar(450) | no | Primary key |
| `UserName` | varchar(256) | yes | Identity username |
| `NormalizedUserName` | varchar(256) | yes | Unique index `UserNameIndex` |
| `Email` | varchar(256) | yes | User email |
| `NormalizedEmail` | varchar(256) | yes | Index `EmailIndex` |
| `EmailConfirmed` | boolean | no | Identity flag |
| `PasswordHash` | varchar(255) | yes | Hashed password in runtime seed; H2 seed uses placeholder/hash value depending generator |
| `SecurityStamp` | varchar(255) | yes | Identity stamp |
| `ConcurrencyStamp` | varchar(255) | yes | Identity concurrency stamp |
| `PhoneNumber` | varchar(255) | yes | Phone number |
| `PhoneNumberConfirmed` | boolean | no | Identity flag |
| `TwoFactorEnabled` | boolean | no | Identity flag |
| `LockoutEnd` | timestamp with time zone | yes | Identity lockout |
| `LockoutEnabled` | boolean | no | Identity flag |
| `AccessFailedCount` | int | no | Identity counter |

Custom profile/payment columns:

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `CardNumber` | varchar(255) | no | `[Required]` |
| `SecurityNumber` | varchar(255) | no | `[Required]` |
| `Expiration` | varchar(255) | no | `[Required]`, must match `MM/YY` regex |
| `CardHolderName` | varchar(255) | no | `[Required]` |
| `CardType` | int | no | Card type id |
| `Street` | varchar(255) | no | `[Required]` |
| `City` | varchar(255) | no | `[Required]` |
| `State` | varchar(255) | no | `[Required]` |
| `Country` | varchar(255) | no | `[Required]` |
| `ZipCode` | varchar(255) | no | `[Required]` |
| `Name` | varchar(255) | no | `[Required]` |
| `LastName` | varchar(255) | no | `[Required]` |

Indexes:

- `EmailIndex` on `NormalizedEmail`.
- Unique `UserNameIndex` on `NormalizedUserName`.

Seed data:

- Runtime default creates `demouser@microsoft.com` with password `Pass@word1`, card `4012888888881881`, security number `535`, expiration `12/21`, address `15703 NE 61st Ct`, `Redmond`, `WA`, `U.S.`, `98052`.
- Customization mode can seed from `Setup/Users.csv`.
- H2 seed fixes the demo user id to `00000000-0000-0000-0000-000000000001`.

### ASP.NET Identity Support Tables

| Table | Primary key | Important FKs / indexes |
| --- | --- | --- |
| `AspNetRoles` | `Id` | Unique `RoleNameIndex` on `NormalizedName` |
| `AspNetRoleClaims` | `Id` | FK `RoleId` -> `AspNetRoles.Id` cascade; index `RoleId` |
| `AspNetUserClaims` | `Id` | FK `UserId` -> `AspNetUsers.Id` cascade; index `UserId` |
| `AspNetUserLogins` | `(LoginProvider, ProviderKey)` | FK `UserId` -> `AspNetUsers.Id` cascade; index `UserId` |
| `AspNetUserRoles` | `(UserId, RoleId)` | FKs to users and roles cascade; index `RoleId` |
| `AspNetUserTokens` | `(UserId, LoginProvider, Name)` | FK `UserId` -> `AspNetUsers.Id` cascade |

### IdentityServer Configuration Tables

Core tables:

| Table | Purpose | Key / unique indexes |
| --- | --- | --- |
| `ApiResources` | API resource definitions | PK `Id`; unique `IX_ApiResources_Name` |
| `ApiScopes` | Scopes belonging to API resources | PK `Id`; FK `ApiResourceId`; unique `IX_ApiScopes_Name` |
| `Clients` | OAuth/OIDC clients | PK `Id`; unique `IX_Clients_ClientId` |
| `IdentityResources` | Identity resources such as `openid`, `profile` | PK `Id`; unique `IX_IdentityResources_Name` |

Child tables, all with cascade FK to their parent:

- `ApiClaims`, `ApiProperties`, `ApiScopeClaims`, `ApiSecrets`.
- `ClientClaims`, `ClientCorsOrigins`, `ClientGrantTypes`, `ClientIdPRestrictions`, `ClientPostLogoutRedirectUris`, `ClientProperties`, `ClientRedirectUris`, `ClientScopes`, `ClientSecrets`.
- `IdentityClaims`, `IdentityProperties`.

Persisted operational tables:

| Table | Primary key | Indexes |
| --- | --- | --- |
| `DeviceCodes` | `UserCode` | Unique `IX_DeviceCodes_DeviceCode` |
| `PersistedGrants` | `Key` | `IX_PersistedGrants_SubjectId_ClientId_Type` on `(SubjectId, ClientId, Type)` |

Seed data:

- `ConfigurationDbContextSeed` seeds clients from `Config.GetClients(...)`, identity resources from `Config.GetResources()`, and API resources from `Config.GetApis()`.
- H2 seed includes identity resources `openid`, `profile`; API resources/scopes for `orders`, `basket`, `marketing`, `locations`, `mobileshoppingagg`, `webshoppingagg`, `orders.signalrhub`, and `webhooks`; client grant types, secrets, redirect URIs, post-logout redirect URIs, CORS origins, and scopes.

## Marketing Relational Model

Context: `MarketingContext`.

### `Campaign`

Entity: `Campaign`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server HiLo sequence `campaign_hilo`; H2 identity |
| `Name` | varchar(255) | no | Required |
| `Description` | varchar(255) | no | Required |
| `From` | timestamp | no | Required; quoted in H2 as `"From"` |
| `To` | timestamp | no | Required; quoted in H2 as `"To"` |
| `PictureUri` | varchar(255) | no | Required |
| `PictureName` | varchar(255) | yes | Present in model/migrations/H2 |
| `DetailsUri` | varchar(255) | yes | Present in model/migrations/H2 |

Relationships:

- One campaign has many rules.
- Deleting a campaign cascades to rules.

Seed data:

- Runtime seed inserts two campaigns:
  - `.NET Bot Black Hoodie 50% OFF`, active from current time to +7 days, picture `1.png`, location rule id `1`.
  - `Roslyn Red T-Shirt 3x2`, active from -7 days to +14 days, picture `2.png`, location rule id `3`.
- H2 seed contains static equivalent rows with fixed dates from the generated file.

### `Rule`

Entity hierarchy: abstract `Rule`; derived `UserProfileRule`, `PurchaseHistoryRule`, `UserLocationRule`.

EF mapping uses table-per-hierarchy in table `Rule` with discriminator `RuleTypeId`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; SQL Server HiLo sequence `rule_hilo`; H2 identity |
| `CampaignId` | int | no | FK to `Campaign.Id` |
| `Description` | varchar(255) | no | Required |
| `RuleTypeId` | int | no | Discriminator |
| `LocationId` | int | yes | Required only for `UserLocationRule` |

Discriminator values:

- `1`: `UserProfileRule`
- `2`: `PurchaseHistoryRule`
- `3`: `UserLocationRule`

Indexes:

- `IX_Rule_CampaignId` on `CampaignId`.

## Marketing Mongo Read Model

Context: `MarketingReadDataContext`.

Database name comes from `MarketingSettings.MongoDatabase`.

### Collection `MarketingReadDataModel`

Entity: `MarketingData`.

| Field | Type | Null | Notes |
| --- | --- | --- | --- |
| `_id` / `Id` | ObjectId string | yes | Ignored if default |
| `UserId` | string | yes | User identity |
| `Locations` | array of `Location` | yes | User-associated locations |
| `UpdateDate` | datetime | no | Last update timestamp |

Embedded `Location`:

| Field | Type |
| --- | --- |
| `LocationId` | int |
| `Code` | string |
| `Description` | string |

No indexes are defined in code for this collection.

## Locations Mongo Model

Context: `LocationsContext`.

Database name comes from `LocationSettings.Database`.

### Collection `Locations`

Entity: `Locations`.

| Field | Type | Null | Notes |
| --- | --- | --- | --- |
| `_id` / `Id` | ObjectId string | no | Mongo document id |
| `LocationId` | int | no | Business id used by APIs and marketing rules |
| `Code` | string | yes | Short code such as `NA`, `US`, `SEAT` |
| `Parent_Id` | ObjectId string | yes | Parent location document id |
| `Description` | string | yes | Human-readable location |
| `Latitude` | double | no | Stored scalar latitude |
| `Longitude` | double | no | Stored scalar longitude |
| `Location` | geo point object | yes | Set via `SetLocation(lon, lat)` |
| `Polygon` | geo polygon object | yes | Set via `SetArea(coordinates)` |

Indexes:

- 2dsphere index on `Location`.

Seed data:

- `1 NA` North America.
- `2 US` United States, child of North America.
- `3 WHT` Washington, child of United States.
- `4 SEAT` Seattle, child of Washington.
- `5 REDM` Redmond, child of Washington.
- `6 BCN` Barcelona.
- `7 SA` South America.
- `8 AFC` Africa.
- `9 EU` Europe.
- `10 AS` Asia.
- `11 AUS` Australia.

Each seed location stores a representative point and polygon coordinates.

### Collection `UserLocation`

Entity: `UserLocation`.

| Field | Type | Null | Notes |
| --- | --- | --- | --- |
| `_id` / `Id` | ObjectId string | yes | Ignored if default |
| `UserId` | string | yes | User identity |
| `LocationId` | int | no | Business location id |
| `UpdateDate` | datetime | no | Last update timestamp |

No indexes are defined in code for this collection.

## Webhooks Relational Model

Context: `WebhooksContext`.

### `Subscriptions`

Entity: `WebhookSubscription`.

| Column | Type | Null | Constraints |
| --- | --- | --- | --- |
| `Id` | int | no | Primary key; H2 identity |
| `Type` | int | no | `WebhookType` enum |
| `Date` | timestamp | no | Subscription creation time |
| `DestUrl` | varchar(255) | yes | Destination webhook URL |
| `Token` | varchar(255) | yes | Optional shared token |
| `UserId` | varchar(255) | yes | Owning user id |

Enum values:

- `1 CatalogItemPriceChange`
- `2 OrderShipped`
- `3 OrderPaid`

No explicit indexes are defined in code or generated H2 DDL.

## Generated H2 Schema and Seed Files

The repository already contains:

- `h2_schema.sql`: executable DDL for a Java/H2-friendly consolidated schema.
- `h2_data.sql`: executable DML for demo/test seed data.

Important differences from runtime SQL Server EF behavior:

- EF Core SQL Server uses HiLo sequences for several ids; H2 uses identity columns.
- H2 consolidates multiple service databases into one schema/file for Java convenience.
- H2 includes some extra test data beyond runtime default seeds.
- H2 has static dates in IdentityServer and Marketing seed rows; runtime seeds often use `DateTime.Now`.

## Relationship Summary

- `CatalogBrand 1 -> many Catalog`.
- `CatalogType 1 -> many Catalog`.
- `ordering.buyers 1 -> many ordering.paymentmethods`.
- `ordering.cardtypes 1 -> many ordering.paymentmethods`.
- `ordering.buyers 1 -> many ordering.orders` optional from order side.
- `ordering.paymentmethods 1 -> many ordering.orders` optional from order side.
- `ordering.orderstatus 1 -> many ordering.orders`.
- `ordering.orders 1 -> many ordering.orderItems`.
- `Campaign 1 -> many Rule`.
- Identity user/role/support tables use standard ASP.NET Identity relationships.
- IdentityServer child tables cascade from `Clients`, `ApiResources`, `ApiScopes`, and `IdentityResources`.

## Constraints To Preserve In Spring

- Preserve all primary keys, foreign keys, and indexes listed above.
- Preserve required fields and max lengths from EF configuration/H2 DDL.
- Preserve basket as Redis JSON keyed by buyer id unless intentionally changing architecture.
- Preserve catalog denormalization into basket and order items: product name, price, and picture URL are copied, not joined at read time.
- Preserve ordering idempotency through `ordering.requests`.
- Preserve `IntegrationEventLog` for reliable event publishing.
- Preserve IdentityServer configuration and persisted grant tables if implementing compatible OIDC behavior.
- Preserve Mongo geospatial `Locations.Location` index and seeded polygons if campaign-by-location behavior is required.

