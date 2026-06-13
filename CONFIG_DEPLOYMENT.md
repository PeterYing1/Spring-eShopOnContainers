# Configuration And Deployment

This document captures the service topology, ports, environment variables, infrastructure dependencies, Docker Compose layout, event bus options, and health checks for recreating eShopOnContainers in Java Spring Boot.

Use this with `DOTNET_TO_SPRING_MAPPING.md`, `AUTH_SECURITY.md`, `API_CONTRACTS.md`, `DATA_MODEL.md`, `EVENT_CONTRACTS.md`, and `TEST_SCENARIOS.md`.

## Docker Compose Topology

The local .NET deployment is defined mainly by:

- `src/docker-compose.yml`
- `src/docker-compose.override.yml`
- `src/.env`

The stack contains:

- SQL Server for Identity, Catalog, Ordering, Marketing, and Webhooks relational data.
- MongoDB for Locations and Marketing user-location projection data.
- Redis for Basket data.
- RabbitMQ for integration events by default.
- Optional Azure Service Bus as an alternative event bus.
- Seq for log aggregation.
- API microservices.
- Ordering background worker.
- Ordering SignalR notification hub.
- Web and mobile BFF aggregators.
- Envoy API gateways.
- MVC, SPA, WebStatus, and WebhookClient web apps.

## Infrastructure Services

| Compose service | Image | External port | Internal port | Persistent volume | Purpose |
| --- | --- | ---: | ---: | --- | --- |
| `sqldata` | `mcr.microsoft.com/mssql/server:2017-latest` | `5433` | `1433` | `eshop-sqldata:/var/opt/mssql` | SQL Server databases |
| `nosqldata` | `mongo` | `27017` | `27017` | `eshop-nosqldata:/data/db` | MongoDB documents |
| `basketdata` | `redis:alpine` | `6379` | `6379` | `eshop-basketdata:/data` | Basket Redis store |
| `rabbitmq` | `rabbitmq:3-management-alpine` | `5672`, `15672` | `5672`, `15672` | none | RabbitMQ broker and management UI |
| `seq` | `datalust/seq:latest` | `5340` | `80` | none | Local structured log UI |

SQL Server local credentials:

- User: `sa`
- Password: `Pass@word`
- `ACCEPT_EULA=Y`

## Application Services And Ports

| Compose service | .NET project | Spring target | External ports | Internal ports | Main dependencies |
| --- | --- | --- | --- | --- | --- |
| `identity-api` | `Services/Identity/Identity.API` | `identity-service` | `5105:80` | `80` | SQL Server |
| `catalog-api` | `Services/Catalog/Catalog.API` | `catalog-service` | `5101:80`, `9101:81` | `80`, `81` gRPC | SQL Server, RabbitMQ |
| `basket-api` | `Services/Basket/Basket.API` | `basket-service` | `5103:80`, `9103:81` | `80`, `81` gRPC | Redis, Identity, RabbitMQ |
| `ordering-api` | `Services/Ordering/Ordering.API` | `ordering-service` | `5102:80`, `9102:81` | `80`, `81` gRPC | SQL Server, Identity, RabbitMQ |
| `ordering-backgroundtasks` | `Services/Ordering/Ordering.BackgroundTasks` | `ordering-background-service` | `5111:80` | `80` | SQL Server, RabbitMQ |
| `ordering-signalrhub` | `Services/Ordering/Ordering.SignalrHub` | `ordering-notification-service` | `5112:80` | `80` | Identity, RabbitMQ |
| `marketing-api` | `Services/Marketing/Marketing.API` | `marketing-service` | `5110:80` | `80` | SQL Server, MongoDB, Identity, RabbitMQ |
| `locations-api` | `Services/Location/Locations.API` | `location-service` | `5109:80` | `80` | MongoDB, Identity, RabbitMQ |
| `payment-api` | `Services/Payment/Payment.API` | `payment-service` | `5108:80` | `80` | RabbitMQ |
| `webhooks-api` | `Services/Webhooks/Webhooks.API` | `webhooks-service` | `5113:80` | `80` | SQL Server, Identity, RabbitMQ |
| `webshoppingagg` | `ApiGateways/Web.Bff.Shopping/aggregator` | `web-shopping-aggregator` | `5121:80` | `80` | Catalog, Basket, Ordering, Identity |
| `mobileshoppingagg` | `ApiGateways/Mobile.Bff.Shopping/aggregator` | `mobile-shopping-aggregator` | `5120:80` | `80` | Catalog, Basket, Ordering, Identity |
| `webmvc` | `Web/WebMVC` | `webmvc-app` | `5100:80` | `80` | Web shopping gateway, web marketing gateway, Identity |
| `webspa` | `Web/WebSPA` | `webspa-host` | `5104:80` | `80` | Identity, gateways |
| `webstatus` | `Web/WebStatus` | `webstatus-app` | `5107:80` | `80` | Health endpoints |
| `webhooks-client` | `Web/WebhookClient` | `webhook-client-app` | `5114:80` | `80` | Webhooks API, Identity |

## Envoy Gateways

| Compose service | External ports | Envoy admin port | Config folder | Route purpose |
| --- | --- | --- | --- | --- |
| `webshoppingapigw` | `5202:80` | `15202:8001` | `ApiGateways/Envoy/config/webshopping` | Web shopping routes |
| `webmarketingapigw` | `5203:80` | `15203:8001` | `ApiGateways/Envoy/config/webmarketing` | Web marketing/location routes |
| `mobileshoppingapigw` | `5200:80` | `15200:8001` | `ApiGateways/Envoy/config/mobilemarketing` in compose, despite service name | Mobile marketing gateway |
| `mobilemarketingapigw` | `5201:80` | `15201:8001` | `ApiGateways/Envoy/config/mobileshopping` in compose, despite service name | Mobile shopping gateway |

Important web shopping routes:

| Public prefix | Rewritten prefix | Upstream |
| --- | --- | --- |
| `/c/` | `/catalog-api/` | `catalog-api` |
| `/catalog-api/` | unchanged | `catalog-api` |
| `/b/` | `/basket-api/` | `basket-api` |
| `/basket-api/` | unchanged | `basket-api` |
| `/o/` | `/ordering-api/` | `ordering-api` |
| `/ordering-api/` | unchanged | `ordering-api` |
| `/hub/notificationhub` | unchanged | `ordering-signalrhub` |
| `/` | `/` | `webshoppingagg` |

Important web marketing routes:

| Public prefix | Rewritten prefix | Upstream |
| --- | --- | --- |
| `/m/` | `/marketing-api/` | `marketing-api` |
| `/marketing-api/` | unchanged | `marketing-api` |
| `/l/` | `/locations-api/` | `locations-api` |
| `/locations-api/` | unchanged | `locations-api` |

## Default `.env` Variables

| Variable | Default/value | Purpose |
| --- | --- | --- |
| `ESHOP_EXTERNAL_DNS_NAME_OR_IP` | `host.docker.internal` | External host used in browser/mobile callback URLs |
| `ESHOP_STORAGE_CATALOG_URL` | `http://host.docker.internal:5202/c/api/v1/catalog/items/[0]/pic/` | Catalog picture URL template |
| `ESHOP_STORAGE_MARKETING_URL` | `http://host.docker.internal:5110/api/v1/campaigns/[0]/pic/` | Marketing picture URL template |
| `ESHOP_PROD_EXTERNAL_DNS_NAME_OR_IP` | `10.121.122.162` | Production/mobile callback host sample |
| `ESHOP_AZURE_REDIS_BASKET_DB` | unset | Optional Azure Redis override |
| `ESHOP_AZURE_SERVICE_BUS` | unset | Optional Azure Service Bus connection override |
| `ESHOP_AZURE_COSMOSDB` | unset | Optional Azure Cosmos DB/Mongo override |
| `ESHOP_AZURE_CATALOG_DB` | unset | Optional Azure SQL catalog DB |
| `ESHOP_AZURE_IDENTITY_DB` | unset | Optional Azure SQL identity DB |
| `ESHOP_AZURE_ORDERING_DB` | unset | Optional Azure SQL ordering DB |
| `ESHOP_AZURE_MARKETING_DB` | unset | Optional Azure SQL marketing DB |
| `ESHOP_AZUREFUNC_CAMPAIGN_DETAILS_URI` | unset | Optional campaign detail Azure Function URI |
| `ESHOP_AZURE_STORAGE_CATALOG_NAME` | unset | Optional catalog Azure Storage account |
| `ESHOP_AZURE_STORAGE_CATALOG_KEY` | unset | Optional catalog Azure Storage key |
| `ESHOP_AZURE_STORAGE_MARKETING_NAME` | unset | Optional marketing Azure Storage account |
| `ESHOP_AZURE_STORAGE_MARKETING_KEY` | unset | Optional marketing Azure Storage key |
| `ESHOP_SERVICE_BUS_USERNAME` | unset | RabbitMQ username override, used mainly under Windows/custom brokers |
| `ESHOP_SERVICE_BUS_PASSWORD` | unset | RabbitMQ password override |
| `INSTRUMENTATION_KEY` | unset | Application Insights instrumentation key |
| `USE_LOADTEST` | unset/false | Enables load-test auth bypass where supported |

Environment variables present in the shell override values in `.env`.

## Common Service Environment Variables

Most HTTP services use:

| Variable | Meaning |
| --- | --- |
| `ASPNETCORE_ENVIRONMENT=Development` | Development runtime profile |
| `ASPNETCORE_URLS=http://0.0.0.0:80` | Bind container HTTP endpoint |
| `ApplicationInsights__InstrumentationKey` | Telemetry key |
| `OrchestratorType` | Deployment/orchestrator metadata |
| `PATH_BASE` | Path base used when routed through gateways, such as `/basket-api` |
| `UseLoadTest` | Enables test bypass auth for some services |
| `UseCustomizationData=True` | Seed customization data/images where supported |

Spring equivalent:

- Use `SPRING_PROFILES_ACTIVE=dev` or equivalent.
- Bind services to container port `80` or map to Spring port `8080` with compose rewrites.
- Preserve external host and route paths for browser-facing clients.
- Preserve `PATH_BASE` behavior through `server.servlet.context-path` or gateway prefix rewrites.

## Service-Specific Configuration

### Identity

| Variable | Value in Compose | Purpose |
| --- | --- | --- |
| `ConnectionString` | `Server=sqldata;Database=Microsoft.eShopOnContainers.Service.IdentityDb;User Id=sa;Password=Pass@word` | Identity SQL DB |
| `SpaClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5104` | SPA redirect/CORS client |
| `MvcClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5100` | MVC redirect client |
| `XamarinCallback` | `http://${ESHOP_PROD_EXTERNAL_DNS_NAME_OR_IP}:5105/xamarincallback` | Mobile callback |
| `LocationApiClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5109` | Locations Swagger client |
| `MarketingApiClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5110` | Marketing Swagger client |
| `BasketApiClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5103` | Basket Swagger client |
| `OrderingApiClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5102` | Ordering Swagger client |
| `MobileShoppingAggClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5120` | Mobile aggregator Swagger client |
| `WebShoppingAggClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5121` | Web aggregator Swagger client |
| `WebhooksApiClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5113` | Webhooks Swagger client |
| `WebhooksWebClient` | `http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5114` | WebhookClient OIDC client |

Identity also supports `TokenLifetimeMinutes=120` and `PermanentTokenLifetimeDays=365` in appsettings.

### Catalog

| Variable | Value/purpose |
| --- | --- |
| `ConnectionString` | Catalog SQL DB connection |
| `PicBaseUrl` | Catalog picture URL template |
| `EventBusConnection` | RabbitMQ host or Azure Service Bus connection |
| `EventBusUserName`, `EventBusPassword` | Optional broker credentials |
| `AzureServiceBusEnabled=False` | Use RabbitMQ by default |
| `AzureStorageEnabled=False` | Use local picture route by default |
| `AzureStorageAccountName`, `AzureStorageAccountKey` | Optional Azure Storage |
| `PATH_BASE=/catalog-api` | Gateway path base |
| `GRPC_PORT=81`, `PORT=80` | gRPC and HTTP ports |

Default DB: `Microsoft.eShopOnContainers.Services.CatalogDb`.

### Basket

| Variable | Value/purpose |
| --- | --- |
| `ConnectionString` | Redis host, default `basketdata` |
| `identityUrl` | Internal identity URL, default `http://identity-api` |
| `IdentityUrlExternal` | Browser-facing Identity URL |
| `EventBusConnection` | RabbitMQ/Azure Service Bus |
| `AzureServiceBusEnabled=False` | Use RabbitMQ by default |
| `PATH_BASE=/basket-api` | Gateway path base |
| `GRPC_PORT=81`, `PORT=80` | gRPC and HTTP ports |

### Ordering API

| Variable | Value/purpose |
| --- | --- |
| `ConnectionString` | Ordering SQL DB connection |
| `identityUrl` | Internal identity URL |
| `IdentityUrlExternal` | Browser-facing Identity URL |
| `EventBusConnection` | RabbitMQ/Azure Service Bus |
| `AzureServiceBusEnabled=False` | Use RabbitMQ by default |
| `UseCustomizationData=True` | Seed ordering data |
| `CheckUpdateTime=30000` | Order status polling/check interval in ms |
| `PATH_BASE=/ordering-api` | Gateway path base |
| `GRPC_PORT=81`, `PORT=80` | gRPC and HTTP ports |

Default DB: `Microsoft.eShopOnContainers.Services.OrderingDb`.

### Ordering Background Tasks

| Variable | Value/purpose |
| --- | --- |
| `ConnectionString` | Ordering SQL DB connection |
| `EventBusConnection` | RabbitMQ/Azure Service Bus |
| `AzureServiceBusEnabled=False` | Use RabbitMQ by default |
| `GracePeriodTime=1` | Grace period before order validation |
| `CheckUpdateTime=30000` in Compose, `1000` in appsettings | Poll/check interval |
| `UseCustomizationData=True` | Seed background task data if needed |

### Ordering SignalR Hub

| Variable | Value/purpose |
| --- | --- |
| `EventBusConnection` | RabbitMQ/Azure Service Bus |
| `AzureServiceBusEnabled=False` | Use RabbitMQ by default |
| `identityUrl=http://identity-api` | Token authority |
| `SignalrStoreConnectionString` | Optional Redis backplane when clustered |
| `SubscriptionClientName=Ordering.signalrhub` | Event bus subscription name |

Endpoint:

- `/hub/notificationhub`

### Marketing

| Variable | Value/purpose |
| --- | --- |
| `ConnectionString` | Marketing SQL DB connection |
| `MongoConnectionString` | MongoDB connection, default `mongodb://nosqldata` |
| `MongoDatabase=MarketingDb` | Marketing Mongo database |
| `identityUrl` | Internal Identity URL |
| `IdentityUrlExternal` | External Identity URL |
| `CampaignDetailFunctionUri` | Optional Azure Function detail URI |
| `PicBaseUrl` | Marketing picture URL template |
| `AzureStorageEnabled=False` | Use local picture route by default |
| `PATH_BASE=/marketing-api` | Gateway path base |

Default SQL DB: `Microsoft.eShopOnContainers.Services.MarketingDb`.

### Locations

| Variable | Value/purpose |
| --- | --- |
| `ConnectionString` | Mongo connection, default `mongodb://nosqldata` |
| `Database=LocationsDb` | Locations Mongo database |
| `identityUrl` | Internal Identity URL |
| `IdentityUrlExternal` | External Identity URL |
| `EventBusConnection` | RabbitMQ/Azure Service Bus |
| `AzureServiceBusEnabled=False` | Use RabbitMQ by default |
| `PATH_BASE=/locations-api` | Gateway path base |

### Payment

| Variable | Value/purpose |
| --- | --- |
| `EventBusConnection` | RabbitMQ/Azure Service Bus |
| `AzureServiceBusEnabled=False` | Use RabbitMQ by default |
| `PaymentSucceeded=true` in appsettings | Simulated payment result |
| `SubscriptionClientName=Payment` | Event bus subscription name |
| `EventBusRetryCount=5` | Broker retry count |

### Webhooks API

| Variable | Value/purpose |
| --- | --- |
| `ConnectionString` | Webhooks SQL DB connection |
| `EventBusConnection` | RabbitMQ/Azure Service Bus |
| `IdentityUrl=http://identity-api` | Internal token authority |
| `IdentityUrlExternal` | External Identity URL |
| `SubscriptionClientName=Webhooks` | Event bus subscription name |

Default DB: `Microsoft.eShopOnContainers.Services.WebhooksDb`.

### BFF Aggregators

Both `webshoppingagg` and `mobileshoppingagg` use:

| Variable | Purpose |
| --- | --- |
| `urls__basket=http://basket-api` | Basket HTTP upstream |
| `urls__catalog=http://catalog-api` | Catalog HTTP upstream |
| `urls__orders=http://ordering-api` | Ordering HTTP upstream |
| `urls__identity=http://identity-api` | Identity upstream |
| `urls__grpcBasket=http://basket-api:81` | Basket gRPC upstream |
| `urls__grpcCatalog=http://catalog-api:81` | Catalog gRPC upstream |
| `urls__grpcOrdering=http://ordering-api:81` | Ordering gRPC upstream |
| `CatalogUrlHC`, `OrderingUrlHC`, `IdentityUrlHC`, `BasketUrlHC`, `MarketingUrlHC`, `PaymentUrlHC`, `LocationUrlHC` | Downstream health URLs |
| `IdentityUrlExternal` | Browser-facing Identity URL for Swagger/auth |

### WebMVC

| Variable | Value/purpose |
| --- | --- |
| `PurchaseUrl=http://webshoppingapigw` | Shopping gateway |
| `IdentityUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5105` | Browser-facing Identity |
| `MarketingUrl=http://webmarketingapigw` | Marketing gateway |
| `SignalrHubUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5202` | Notification hub through shopping gateway |
| `IdentityUrlHC=http://identity-api/hc` | Health check |
| `UseCustomizationData=True` | Seed MVC customization data |
| `UseLoadTest` | Optional bypass auth |

Appsettings also has `SessionCookieLifetimeMinutes=60`.

### WebSPA

| Variable | Value/purpose |
| --- | --- |
| `IdentityUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5105` | SPA OIDC authority |
| `PurchaseUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5202` | Shopping gateway |
| `MarketingUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5203` | Marketing gateway |
| `SignalrHubUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5202` | SignalR gateway |
| `IdentityUrlHC=http://identity-api/hc` | Health check |
| `UseCustomizationData=True` | Seed SPA customization data |

### WebhookClient

| Variable | Value/purpose |
| --- | --- |
| `Token=6168DB8D-DC58-4094-AF24-483278923590` | Webhook grant/check token |
| `IdentityUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5105` | Browser-facing Identity |
| `CallBackUrl=http://${ESHOP_EXTERNAL_DNS_NAME_OR_IP}:5114` | OIDC callback base |
| `WebhooksUrl=http://webhooks-api` | Webhooks API upstream |
| `SelfUrl=http://webhooks-client/` | Internal webhook receiver URL |

## Databases And Stores

| Store | Database/key space | Used by |
| --- | --- | --- |
| SQL Server | `Microsoft.eShopOnContainers.Service.IdentityDb` | Identity |
| SQL Server | `Microsoft.eShopOnContainers.Services.CatalogDb` | Catalog |
| SQL Server | `Microsoft.eShopOnContainers.Services.OrderingDb` | Ordering API and background tasks |
| SQL Server | `Microsoft.eShopOnContainers.Services.MarketingDb` | Marketing campaigns and rules |
| SQL Server | `Microsoft.eShopOnContainers.Services.WebhooksDb` | Webhook subscriptions |
| MongoDB | `LocationsDb` | Locations user location/history |
| MongoDB | `MarketingDb` | Marketing user-location projection |
| Redis | buyer id keys | Basket data |
| Redis | `DataProtection-Keys` | Optional clustered data-protection keys |
| Redis | SignalR backplane | Optional clustered SignalR hub |

## Event Bus

Default local event bus:

- RabbitMQ service name: `rabbitmq`
- AMQP port: `5672`
- Management UI: `15672`
- Default retry count: `5`
- Subscription client names include `Catalog`, `Basket`, `Ordering`, `BackgroundTasks`, `Marketing`, `Locations`, `Payment`, `Webhooks`, and `Ordering.signalrhub`.

Azure Service Bus option:

- Set `AzureServiceBusEnabled=True`.
- Set `EventBusConnection` to the Azure Service Bus connection.
- Compose uses `${ESHOP_AZURE_SERVICE_BUS:-rabbitmq}`, so unset value falls back to RabbitMQ.
- Health checks switch from RabbitMQ checks to Azure Service Bus topic checks when enabled.
- The event bus topic name used in health checks is `eshop_event_bus`.

## Health Checks

Most services expose:

- `/hc`: readiness/full health with dependency checks.
- `/liveness`: self-only liveness check.

Dependency checks by component:

| Component | `/hc` checks |
| --- | --- |
| Identity | self, SQL Server Identity DB |
| Catalog | self, SQL Server Catalog DB, RabbitMQ or Azure Service Bus |
| Basket | self, Redis, RabbitMQ or Azure Service Bus |
| Ordering API | self, SQL Server Ordering DB, RabbitMQ or Azure Service Bus |
| Ordering BackgroundTasks | self, SQL Server/event bus as configured |
| Ordering SignalRHub | self, RabbitMQ or Azure Service Bus |
| Marketing | self, SQL Server, MongoDB, RabbitMQ or Azure Service Bus |
| Locations | self, MongoDB, RabbitMQ or Azure Service Bus |
| Payment | self, RabbitMQ or Azure Service Bus |
| Webhooks | self, SQL Server |
| WebMVC | self, Identity `/hc` |
| WebSPA | self, Identity `/hc` |
| BFF aggregators | self plus Catalog, Ordering, Basket, Identity, Marketing, Payment, Locations URL checks |
| WebStatus | health UI over configured service `/hc` endpoints |

WebStatus configuration in Compose watches:

- WebMVC
- WebSPA
- Web Shopping Aggregator
- Mobile Shopping Aggregator
- Ordering API
- Basket API
- Catalog API
- Identity API
- Marketing API
- Locations API
- Payment API
- Ordering SignalRHub
- Ordering BackgroundTasks

WebStatus UI path:

- `/hc-ui`

## Spring Boot Deployment Compatibility

For a Spring Boot rewrite:

- Keep Compose service names stable where possible, even if image/artifact names change.
- Preserve external ports for browser and test compatibility.
- Preserve internal DNS names such as `catalog-api`, `basket-api`, `ordering-api`, and `identity-api`.
- Preserve gateway route prefixes such as `/c/`, `/b/`, `/o/`, `/m/`, `/l/`, and `/hub/notificationhub`.
- Expose `/hc` and `/liveness` in every service, mapped to Spring Actuator readiness/liveness checks.
- Preserve RabbitMQ as the default local event bus and Azure Service Bus as an optional deployment mode.
- Preserve SQL/Mongo/Redis persistence boundaries from `DATA_MODEL.md`.
- Preserve `PATH_BASE` semantics through context paths or gateway rewrites.
- Use environment variables rather than hard-coded URLs for every inter-service endpoint.
- Treat Application Insights, Azure Storage, Azure SQL, Azure Redis, Cosmos DB, and Azure Service Bus as optional deployment overrides.

