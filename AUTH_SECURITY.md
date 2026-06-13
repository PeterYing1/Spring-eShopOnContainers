# Auth And Security

This document describes the identity, authentication, authorization, cookie/token, CORS, and CSRF behavior that a Spring Boot rewrite must preserve for eShopOnContainers.

Use this together with `API_CONTRACTS.md`, `DATA_MODEL.md`, `EVENT_CONTRACTS.md`, `UI_BEHAVIOR.md`, `TEST_SCENARIOS.md`, `DOTNET_TO_SPRING_MAPPING.md`, and `BUSINESS_RULES.md`.

## Security Architecture

The .NET application uses IdentityServer4 as the central identity provider and authorization server.

Main security boundaries:

- Browser-based MVC apps authenticate with OpenID Connect and keep a local application cookie.
- APIs, BFF aggregators, and SignalR validate bearer access tokens issued by IdentityServer.
- The durable user identifier is the `sub` claim.
- Service ownership checks are mostly implemented by filtering data with the authenticated user's `sub`.
- The sample app does not implement fine-grained business roles for normal workflows.
- Several local/test modes bypass authentication by injecting a fake principal.

Spring Boot equivalent:

- Prefer Keycloak or Spring Authorization Server for the IdentityServer replacement.
- Use Spring Security OAuth2 Client for MVC/web clients.
- Use Spring Security OAuth2 Resource Server for APIs, BFFs, and WebSocket/STOMP endpoints.
- Preserve the same clients, scopes/audiences, claim names, cookie lifetimes, redirects, and protected routes unless intentionally modernizing with a documented migration.

## Identity Provider

### AUTH-ID-001 Identity Service

.NET source:

- `Services/Identity/Identity.API`
- ASP.NET Core Identity for local users.
- IdentityServer4 for OIDC/OAuth2 clients, API resources, signing credentials, persisted grants, and configuration store.
- SQL Server stores users, identity configuration, persisted grants, and operational data.

Spring equivalent:

- `identity-service` using Spring Authorization Server, or Keycloak realm/config.
- Must issue compatible access tokens containing `sub` and accepted audiences/scopes.
- Must support local username/password login for the seeded demo user.

### AUTH-ID-002 Demo User

Seeded default user:

| Field | Value |
| --- | --- |
| Email/UserName | `demouser@microsoft.com` |
| Password | `Pass@word1` |
| Name | `DemoUser` |
| Last name | `DemoLastName` |
| Phone | `1234567890` |
| Street | `15703 NE 61st Ct` |
| City | `Redmond` |
| State | `WA` |
| Country | `U.S.` |
| Zip | `98052` |
| Card holder | `DemoUser` |
| Card number | `4012888888881881` |
| Card type | `1` |
| Expiration | `12/21` |
| Security number | `535` |

The Spring rewrite should seed an equivalent user for acceptance tests. If current-date validation makes `12/21` unusable for checkout, keep the identity claim for parity but use a future expiration in checkout tests.

### AUTH-ID-003 Login Flow

When a user signs in:

1. The MVC app challenges with OpenID Connect.
2. Identity redirects to `/Account/Login`.
3. Login GET builds a login page from the OIDC authorization context.
4. Login POST validates antiforgery token.
5. Identity looks up the submitted email.
6. Identity validates the submitted password.
7. On success, Identity signs in the local user and redirects to the validated return URL.
8. On failure, Identity redisplays the page with `Invalid username or password.`

Login token lifetimes:

- Normal login uses `TokenLifetimeMinutes`, default `120`.
- Remember-me login uses `PermanentTokenLifetimeDays`, default `365`, and persistent cookie/session properties.
- IdentityServer authentication cookie lifetime is 2 hours.

### AUTH-ID-004 Logout Flow

When a user signs out:

- MVC signs out both the local cookie scheme and the OpenID Connect scheme.
- Identity `/Account/Logout` may show a confirmation prompt unless the OIDC logout context says prompt is not required.
- Logout POST validates antiforgery token.
- Identity deletes its authentication cookie.
- Identity clears the current principal.
- Identity redirects to the client's post-logout redirect URI.

WebhookClient uses the same cookie plus OIDC pattern with its own client id.

### AUTH-ID-005 Registration

Identity supports local registration:

- `GET /Account/Register` is anonymous.
- `POST /Account/Register` is anonymous and validates antiforgery token.
- Registration stores username/email plus profile, address, and card fields.
- On success with a return URL, unauthenticated users are sent to login.
- On identity errors, the registration form is redisplayed with model errors.

### AUTH-ID-006 External Authentication

External login is not implemented.

If an authorization context specifies an external identity provider, the .NET login action throws `NotImplementedException("External login is not implemented!")`.

Spring rewrite assumption:

- Do not implement Google/Microsoft/social login unless a later requirement asks for it.
- If an external IdP hint is received, fail with a clear unsupported-external-login response or ignore external providers entirely.

## Clients, Scopes, And Audiences

### AUTH-CL-001 API Resources

Identity defines these API resources/scopes:

| Resource/scope | Protected component |
| --- | --- |
| `orders` | Ordering API |
| `basket` | Basket API |
| `marketing` | Marketing API |
| `locations` | Locations API |
| `mobileshoppingagg` | Mobile shopping aggregator |
| `webshoppingagg` | Web shopping aggregator |
| `orders.signalrhub` | Ordering SignalR hub |
| `webhooks` | Webhooks service |

### AUTH-CL-002 Identity Resources

Identity resources:

- `openid`
- `profile`

### AUTH-CL-003 OIDC/OAuth Clients

| Client id | Client type | Grant type | Secret | Browser tokens | Offline access | Main scopes |
| --- | --- | --- | --- | --- | --- | --- |
| `js` | SPA | Implicit | None | Yes | No | `openid`, `profile`, `orders`, `basket`, `locations`, `marketing`, `webshoppingagg`, `orders.signalrhub`, `webhooks` |
| `xamarin` | Mobile | Hybrid + PKCE | `secret` | Yes | Yes | `openid`, `profile`, `offline_access`, `orders`, `basket`, `locations`, `marketing`, `mobileshoppingagg`, `webhooks` |
| `mvc` | Web MVC | Hybrid | `secret` | No | Yes | `openid`, `profile`, `offline_access`, `orders`, `basket`, `locations`, `marketing`, `webshoppingagg`, `orders.signalrhub`, `webhooks` |
| `webhooksclient` | Webhook client MVC | Hybrid | `secret` | No | Yes | `openid`, `profile`, `offline_access`, `webhooks` |
| `mvctest` | Load-test MVC client | Hybrid | `secret` | Yes | Yes | `openid`, `profile`, `offline_access`, `orders`, `basket`, `locations`, `marketing`, `webshoppingagg`, `webhooks` |
| `locationsswaggerui` | Swagger UI | Implicit | None | Yes | No | `locations` |
| `marketingswaggerui` | Swagger UI | Implicit | None | Yes | No | `marketing` |
| `basketswaggerui` | Swagger UI | Implicit | None | Yes | No | `basket` |
| `orderingswaggerui` | Swagger UI | Implicit | None | Yes | No | `orders` |
| `mobileshoppingaggswaggerui` | Swagger UI | Implicit | None | Yes | No | `mobileshoppingagg` |
| `webshoppingaggswaggerui` | Swagger UI | Implicit | None | Yes | No | `webshoppingagg`, `basket` |
| `webhooksswaggerui` | Swagger UI | Implicit | None | Yes | No | `webhooks` |

Notes:

- MVC and WebhookClient include user claims in the id token.
- MVC access and identity tokens have 2-hour lifetimes in the IdentityServer client config.
- The sample uses hybrid/implicit flows because it targets older IdentityServer4-era browser patterns. A Spring rewrite may use authorization code + PKCE, but must preserve equivalent issued scopes, redirects, and token audiences.

## Claims

### AUTH-CLAIM-001 Required Claims

The most important claim is:

| Claim | Purpose |
| --- | --- |
| `sub` | Stable user id used for basket ids, order ownership, locations, marketing user data, webhook subscription ownership, and downstream service identity. |

All services that need user ownership read `sub` directly. The .NET code removes or clears default inbound JWT claim mapping so `sub` remains named `sub` instead of being mapped to a framework-specific name identifier.

### AUTH-CLAIM-002 Profile Claims

Identity profile service emits these claims when values are present:

| Claim | Source field | Used by |
| --- | --- | --- |
| `sub` | user id | All protected user workflows |
| `preferred_username` | username | UI/profile display |
| `unique_name` | username | UI/profile display |
| `name` | first/display name | MVC checkout profile |
| `last_name` | last name | MVC checkout profile |
| `email` | email | MVC user model |
| `email_verified` | email confirmation | informational |
| `phone_number` | phone | MVC user model |
| `phone_number_verified` | phone confirmation | informational |
| `card_number` | card number | MVC checkout prefill |
| `card_holder` | card holder name | MVC checkout prefill |
| `card_security_number` | card security number | MVC checkout prefill |
| `card_expiration` | card expiration | MVC checkout prefill |
| `address_city` | city | MVC checkout prefill |
| `address_country` | country | MVC checkout prefill |
| `address_state` | state | MVC checkout prefill |
| `address_street` | street | MVC checkout prefill |
| `address_zip_code` | zip code | MVC checkout prefill |

Security note:

- The sample places demo card data in identity claims. Preserve this only for behavioral parity. A production rewrite should not put card numbers or security codes in identity tokens.

### AUTH-CLAIM-003 Roles

The sample configures ASP.NET Identity roles but does not seed or enforce meaningful application roles in the inspected workflows.

Observed behavior:

- Most protected routes require only authenticated access plus correct token audience.
- Ownership is enforced by `sub`, not by role.
- Order shipping is a management action in UI/business terms, but the sample endpoint itself is protected by authentication, not a specific role policy.

Spring rewrite:

- For exact parity, require authentication and `sub` ownership checks.
- If adding roles for safer implementation, document the divergence and make tests aware of it.

## Cookies And Tokens

### AUTH-TOKEN-001 MVC Cookie And OIDC Token Storage

WebMVC:

- Default local scheme: cookie authentication.
- Challenge flow: OpenID Connect.
- Cookie expiration defaults to `SessionCookieLifetimeMinutes`, value `60`.
- OIDC authority is `IdentityUrl`.
- Client id is `mvc`, or `mvctest` when `UseLoadTest=true`.
- Client secret is `secret`.
- Response type is `code id_token`; load-test mode uses `code id_token token`.
- `SaveTokens=true`, so access tokens are available from the authentication session.
- `GetClaimsFromUserInfoEndpoint=true`.
- HTTPS metadata validation is disabled in local/sample config.
- SameSite cookie policy is minimum `Lax`.

WebhookClient:

- Same cookie plus OpenID Connect pattern.
- Client id is `webhooksclient`.
- Cookie expiration is 2 hours.
- Session cookie name is `.eShopWebhooks.Session`.

### AUTH-TOKEN-002 Access Token Forwarding

MVC and aggregators forward bearer tokens to downstream APIs.

Rules:

- If the current HTTP request already has an `Authorization` header, copy it to the downstream request.
- Then read the saved `access_token` from the current authentication session.
- If an access token exists, set `Authorization: Bearer {token}` on the downstream request.
- Downstream APIs validate the bearer token against Identity authority and expected audience.

### AUTH-TOKEN-003 API Resource Server Validation

Protected APIs use JWT bearer authentication.

| Component | Expected audience |
| --- | --- |
| Basket API | `basket` |
| Ordering API | `orders` |
| Marketing API | `marketing` |
| Locations API | `locations` |
| Webhooks API | `webhooks` |
| Web shopping aggregator | `webshoppingagg` |
| Mobile shopping aggregator | `mobileshoppingagg` |
| Ordering SignalR hub | `orders.signalrhub` |

Common settings:

- Authority is `IdentityUrl`.
- `RequireHttpsMetadata=false` in the sample/local config.
- Default authenticate and challenge scheme is JWT bearer.

### AUTH-TOKEN-004 SignalR Token Handling

Ordering SignalR hub:

- Hub route: `/hub/notificationhub`.
- Expected audience: `orders.signalrhub`.
- The hub accepts bearer tokens in the normal `Authorization` header.
- It also accepts `access_token` query string value when the request path starts with `/hub/notificationhub`.
- This query-string token behavior exists for browser SignalR/WebSocket compatibility.

Spring rewrite:

- For WebSocket/STOMP, accept the access token during handshake.
- Preserve equivalent authenticated-user association for order notifications.

## Protected Routes

### AUTH-ROUTE-001 Public Routes

Routes with no observed `[Authorize]` requirement:

| Component | Routes |
| --- | --- |
| Catalog API | `/api/v1/catalog/**`, `/api/v1/catalog/items/{id}/pic`, `/hc`, `/liveness`, home |
| Identity API | login, logout prompt, register GET/POST, OIDC discovery/authorize/token/userinfo/end-session, `/hc`, `/liveness` |
| WebMVC | catalog browsing, error page, static assets, health endpoints |
| WebSPA host | static SPA files, fallback to `index.html`, health endpoints |
| WebhookClient receiver | `/webhook-received` POST receiver, `/check` OPTIONS grant check, static assets |
| Service home/health | `/`, `/hc`, `/liveness` where implemented |

Note: Some routes are public at the app level but may still require valid OIDC protocol parameters, antiforgery tokens, or webhook grant tokens.

### AUTH-ROUTE-002 WebMVC Protected Routes

Protected by OpenID Connect authentication scheme:

| Controller | Protected behavior |
| --- | --- |
| `AccountController` | `SignIn`, `Signout` |
| `CartController` | cart view, add to cart, update quantities, checkout action |
| `OrderController` | checkout page, checkout post, order history, order detail, cancel |
| `CampaignsController` | campaign page and location update flow |
| `OrderManagementController` | order management and ship flow |
| `TestController` | test route |

### AUTH-ROUTE-003 API Protected Routes

Protected by JWT bearer:

| Component | Protected routes |
| --- | --- |
| Basket API | all `/api/v1/basket/**` routes |
| Ordering API | all `/api/v1/orders/**` routes |
| Locations API | all `/api/v1/locations/**` routes |
| Marketing API | all `/api/v1/campaigns/**` and campaign-location rule routes because controllers are `[Authorize]` |
| Webhooks API | `GET /api/v1/webhooks`, `GET /api/v1/webhooks/{id}`, `POST /api/v1/webhooks`, `DELETE /api/v1/webhooks/{id}` |
| Web shopping aggregator | `/api/v1/basket/**`, `/api/v1/order/**` |
| Mobile shopping aggregator | `/api/v1/basket/**`, `/api/v1/order/**` |
| Ordering SignalR hub | `/hub/notificationhub` handshake/connection |

### AUTH-ROUTE-004 Ownership Rules

Protected routes are not enough; they also enforce ownership by `sub`.

Examples:

- Basket checkout loads the basket for authenticated `sub`.
- Ordering list returns orders for authenticated `sub`.
- Locations POST updates the location for authenticated `sub`.
- Marketing user campaigns query marketing data for authenticated `sub`.
- Webhooks list/detail/delete filter subscriptions by authenticated `sub`.
- Webhook detail/delete for another user's subscription returns not found.

## CORS

### AUTH-CORS-001 API CORS Policy

Most APIs, BFFs, and the SignalR hub define `CorsPolicy` as:

- allow any method,
- allow any header,
- allow credentials,
- accept any origin through `SetIsOriginAllowed((host) => true)`.

Applied components include:

- Basket API
- Ordering API
- Marketing API
- Locations API
- Webhooks API
- Web shopping aggregator
- Mobile shopping aggregator
- Ordering SignalR hub

Spring rewrite:

- For behavior parity, configure the same permissive CORS policy on these components.
- Because credentials are allowed, do not use literal wildcard `*`; use an origin predicate/pattern equivalent.
- For production hardening, restrict origins to the configured clients, but document the intentional difference.

### AUTH-CORS-002 Identity SPA Origins

Identity config allows the SPA client origin:

- SPA redirect URI: `{SpaClient}/`
- SPA post-logout redirect URI: `{SpaClient}/`
- SPA CORS origin: `{SpaClient}`

Swagger UI clients use their service-specific Swagger OAuth redirect URLs.

## CSRF And Antiforgery

### AUTH-CSRF-001 Identity Forms

Identity protects these POST forms with antiforgery validation:

- `POST /Account/Login`
- `POST /Account/Logout`
- `POST /Account/Register`

Spring rewrite:

- Enable CSRF protection for identity/login/logout/register forms.
- Ensure rendered forms include CSRF tokens.

### AUTH-CSRF-002 WebMVC Forms

Observed MVC controllers do not explicitly decorate cart/order/campaign POST actions with `[ValidateAntiForgeryToken]`.

Spring rewrite parity:

- If preserving exact behavior, do not require CSRF tokens for these MVC POSTs unless adding a documented hardening change.
- If enabling Spring Security CSRF globally, add compatibility handling for existing forms and tests so cart, checkout, cancellation, and location update flows continue to work.

### AUTH-CSRF-003 WebSPA XSRF

WebSPA registers antiforgery with header name `X-XSRF-TOKEN`.

The middleware that would issue an `XSRF-TOKEN` cookie on `/` is present but commented out in the .NET source.

Observed behavior:

- Antiforgery service is configured.
- Automatic SPA token-cookie emission is not active.

Spring rewrite:

- Preserve this if matching behavior exactly.
- If implementing active SPA CSRF protection, expose an `XSRF-TOKEN` cookie and require `X-XSRF-TOKEN` for unsafe SPA API calls, documenting the hardening difference.

### AUTH-CSRF-004 APIs With Bearer Tokens

JSON APIs protected by bearer tokens do not use CSRF tokens in the sample.

Spring rewrite:

- Disable CSRF for stateless bearer-token API routes.
- Keep CSRF enabled for browser form login/session routes where appropriate.

## Webhook Grant Security

WebhookClient exposes a grant-check endpoint:

- Path: `/check`
- Method: `OPTIONS`
- Header: configured webhook check header name.
- If `ValidateToken` is false, the check passes.
- If `ValidateToken` is true, the request header must equal configured `Token`.
- If a token is configured, the response echoes the check header.
- Invalid token returns `400 Bad Request` with `Invalid token`.
- Non-OPTIONS requests return `400 Bad Request`.

Webhook subscription creation in Webhooks API:

- Requires authentication.
- Validates the subscriber's grant URL before storing the subscription.
- Returns HTTP `418` when the grant URL cannot be validated.

## Test And Load-Test Bypass

Several projects include bypass middleware for test/load modes.

Observed bypass claims commonly include:

- `emails`
- `name`
- `nonce`
- identity provider claim set to `ByPassAuthMiddleware`
- `sub`
- surname/givenname schema claims
- some MVC bypass profiles also include `card_expiration`

Specific behavior:

- WebMVC uses `ByPassAuthMiddleware` when `UseLoadTest=true`.
- Basket, Marketing, Locations, and Ordering test setups include auto-authorize/bypass middleware for functional tests.
- Location functional tests inject `sub = 4611ce3f-380d-4db5-8d76-87a8689058ed`.
- Marketing functional tests inject `sub = 1234`.

Spring rewrite:

- Provide test-only security profiles or test fixtures that inject equivalent authenticated principals.
- Never enable bypass middleware in production profiles.

## Security Compatibility Checklist

The Spring Boot rewrite should preserve:

- `sub` claim as the user id everywhere.
- API resource/audience names: `orders`, `basket`, `marketing`, `locations`, `webshoppingagg`, `mobileshoppingagg`, `orders.signalrhub`, `webhooks`.
- OIDC client ids and redirect URI behavior for MVC, WebhookClient, SPA, mobile, and Swagger UI clients.
- MVC cookie plus saved-token behavior.
- Downstream bearer-token forwarding from MVC and aggregators.
- JWT bearer validation on APIs/BFFs/hub.
- SignalR/WebSocket query-string token support for the notification hub.
- Permissive sample CORS policy with credentials.
- Antiforgery on Identity forms.
- WebSPA antiforgery registration with `X-XSRF-TOKEN`, while noting token emission is commented out.
- Ownership checks based on authenticated `sub`.
- Local/demo user seed and profile claims.
- Unsupported external login behavior.

## Production Hardening Notes

These are not exact-parity requirements, but they are important if the Spring rewrite is intended for production:

- Replace implicit/hybrid browser flows with authorization code + PKCE.
- Do not put card number or card security number in identity claims or tokens.
- Require HTTPS metadata and HTTPS redirect URIs.
- Restrict CORS origins to known client URLs.
- Add explicit roles/policies for admin catalog mutation, marketing mutation, and order shipping.
- Enable CSRF protection for all cookie-backed unsafe browser requests.
- Store secrets outside appsettings/config files.
- Use short-lived access tokens with refresh-token rotation where appropriate.
- Add token revocation/session invalidation behavior.
- Add Content Security Policy without `unsafe-inline`.

