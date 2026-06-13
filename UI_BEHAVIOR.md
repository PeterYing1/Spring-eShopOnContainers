# UI Behavior

Source of truth: WebMVC controllers, Razor views, view models, Identity views, WebhookClient Razor Pages, and client-side scripts under `src/`.

This document describes the browser-facing behavior to preserve in a Java Spring Boot rewrite.

## Applications Covered

- WebMVC storefront: catalog, cart, checkout, orders, campaigns, account links, SignalR notifications.
- Identity.API browser UI: login, register, logout, consent, identity home/error pages.
- WebhookClient: order-management sample UI for webhook registration and received webhook display.

## WebMVC Global Layout

Default route:

- `/{controller=Catalog}/{action=Index}/{id?}`

Header:

- Left side shows brand image `images/brand.png`.
- Brand links to `Catalog/Index`.
- If user is authenticated and has claim `preferred_username`:
  - Shows username.
  - Shows dropdown menu with:
    - `My orders` -> `Order/Index`
    - `Campaigns` -> `Campaigns/Index`
    - `Log Out` -> submits hidden form to `Account/SignOut`
  - Shows cart badge component.
- If user is anonymous:
  - Shows `Login` link -> `Account/SignIn`.
  - Does not show cart badge.

Footer:

- Shows dark brand image and footer text image.

Authenticated SignalR behavior:

- On page load, authenticated users connect to `{SignalrHubUrl}/hub/notificationhub`.
- Access token is supplied from `Context.GetTokenAsync("access_token")`.
- The client listens for `UpdatedOrderState`.
- On event, shows toastr success message:
  - Title: `Order Id: {orderId}`
  - Body: `Updated to status: {status}`
- If current URL path ends with `Order`, page reloads after 1 second.

## WebMVC Catalog

Page:

- `GET /Catalog/Index`

Purpose:

- Main landing page and anonymous catalog browsing page.

Hero:

- Shows catalog hero image text `images/main_banner_text.png`.

Filters:

- Form posts to `Catalog/Index`.
- Controls:
  - Brand select bound to `BrandFilterApplied`.
  - Type select bound to `TypesFilterApplied`.
  - Image submit button using `images/arrow-right.svg`.
- Controller still accepts filters through model binding; pagination links pass only `page`.

Catalog results:

- If results exist:
  - Renders pagination above and below the product grid.
  - Renders each item as product image, `[ ADD TO CART ]` button, name, and price.
- If no results:
  - Displays `THERE ARE NO RESULTS THAT MATCH YOUR SEARCH`.

Product card:

- Form action: `Cart/AddToCart`.
- Hidden input: `id` = product id.
- Submit button text: `[ ADD TO CART ]`.
- If anonymous, button receives CSS class `is-disabled`; it is still rendered as a submit input, but the protected cart action triggers authentication flow.

Pagination:

- Previous link id `Previous`, route `page = ActualPage - 1`.
- Next link id `Next`, route `page = ActualPage + 1`.
- Disabled state is represented by CSS class `is-disabled`.
- Text:
  - `Showing {ItemsPerPage} of {TotalItems} products - Page {ActualPage + 1} - {TotalPages}`.

Basket service failure:

- If query `errorMsg` is present, catalog shows warning alert with the message.
- `Cart/AddToCart` redirects here with `errorMsg` when basket service fails.

## WebMVC Cart Flow

Page:

- `GET /Cart/Index`
- Requires OpenID Connect authentication.

Header:

- Shows back link `Back to catalog` -> `Catalog/Index`.

Cart content:

- Renders product image, product name, unit price, quantity input, and cost.
- Quantity input:
  - Type `number`.
  - `min="1"`.
  - Name pattern: `quantities[i].Value`.
  - Hidden key input name: `quantities[i].Key`, value = basket item id.
- Total is shown at bottom.

Buttons:

- `[ Update ]` button:
  - Submits cart form without `action="[ Checkout ]"`.
  - Controller calls basket BFF `PUT /api/v1/basket/items`.
  - Returns cart view.
- `[ Checkout ]` submit:
  - Name `action`, value `[ Checkout ]`.
  - Controller updates quantities first.
  - Redirects to `Order/Create`.

Price-change warning:

- If basket item `OldUnitPrice != 0`, shows:
  - `Note that the price of this article changed in our Catalog. The old price when you originally added it to the basket was $ {OldUnitPrice}`

Basket inoperative behavior:

- If basket service throws while loading cart, `ViewBag.BasketInoperativeMsg` is set.
- Cart list shows warning alert with the message.
- Message format from controller:
  - `Basket Service is inoperative {ExceptionType} - {ExceptionMessage}`
- Cart badge can show `cart-inoperative.png` and an `X` badge.

Add to cart:

- Triggered by product card form.
- Adds one unit of catalog item to current user's basket through BFF `POST /api/v1/basket/items`.
- On success, redirects to `Catalog/Index`.
- On failure, redirects to `Catalog/Index` with basket inoperative message.

## WebMVC Checkout Flow

Page:

- `GET /Order/Create`
- Requires OpenID Connect authentication.

Prepopulation:

- Loads current user's basket as an order draft.
- Copies user profile into the order:
  - City, Street, State, Country, ZipCode.
  - CardNumber, CardHolderName, CardExpiration, CardSecurityNumber.
- Formats card expiration as `MM/yy`.

Header:

- Shows `Back to cart` -> `Cart/Index`.

Form:

- Method: POST.
- Action: `Order/Checkout`.

Shipping fields:

- `Street`, label `Address`, placeholder `Street`, required.
- `City`, label `City`, placeholder `City`, required.
- `State`, label `State`, placeholder `State`, required.
- `Country`, label `Country`, placeholder `Country`, required.
- `ZipCode` is hidden and preserved from user profile.

Payment fields:

- `CardNumber`, label `Card number`, placeholder `000000000000000`, required.
- `CardHolderName`, label `Cardholder name`, placeholder `Cardholder`, required.
- `CardExpirationShort`, label `Expiration date`, placeholder `MM/YY`, required.
- `CardSecurityNumber`, label `Security code`, placeholder `000`, required.
- `CardTypeId` is effectively fixed to `1` when mapping to checkout DTO.

Hidden fields:

- `ZipCode`.
- `RequestId`, generated as a new GUID in the view on every render.
- Order item hidden fields for each item:
  - `orderitems[i].PictureUrl`
  - `orderitems[i].ProductName`
  - `orderitems[i].UnitPrice`
  - `orderitems[i].Units`
- `Total`.

Order detail section:

- Shows product image, name, unit price, units, line cost.
- Shows total.

Submit button:

- Text: `[ Place Order ]`.
- Name: `action`.

Validation:

- Uses unobtrusive validation partial.
- Model errors are displayed as warning alerts at top of form.
- Field validation spans use alert-danger styling.
- Required fields use default ASP.NET required messages unless custom message applies.
- `CardExpirationShort` must match regex `(0[1-9]|1[0-2])/[0-9]{2}`.
- Regex error:
  - `Expiration should match a valid MM/YY value`
- Expiration must be in the future.
- Custom expiration error:
  - `The card is expired`

Checkout POST:

- If `ModelState.IsValid`:
  - Converts `CardExpirationShort` to first day of target month.
  - Maps order to basket checkout DTO.
  - Calls basket checkout API.
  - Redirects to `Order/Index`.
- If checkout throws:
  - Adds model error:
    - `It was not possible to create a new order, please try later on ({ExceptionType} - {ExceptionMessage})`
  - Returns `Create` view with submitted model.
- If model invalid:
  - Returns `Create` view with validation errors.

## WebMVC Orders

### My Orders

Page:

- `GET /Order/Index`
- Requires OpenID Connect authentication.

Header links:

- `Back to catalog` -> `Catalog/Index`.
- `/`
- `Orders Management` -> `OrderManagement/Index`.

Table columns:

- Order number.
- Date.
- Total.
- Status.
- Actions.

Actions:

- `Detail` link -> `Order/Detail?orderId={OrderNumber}`.
- `Cancel` link -> `Order/Cancel?orderId={OrderNumber}`.
- Cancel is shown only when `Status.ToLower() == "submitted"`.

Cancel behavior:

- Calls ordering API cancel endpoint.
- Redirects to `Order/Index`.
- If ordering service returns internal server error, service throws `Error cancelling order, try later.`

### Order Detail

Page:

- `GET /Order/Detail?orderId={id}`

Header:

- `Back to catalog` -> `Catalog/Index`.

Sections:

- Summary: order number, date, total, status.
- Description.
- Shipping address: street, city, country.
- Order details: item image, product name, unit price, units, line cost.
- Total.

Note:

- View title text has typo `Shiping address`; preserve only if exact UI copy is required.

### Order Management

Page:

- `GET /OrderManagement/Index`
- Requires OpenID Connect authentication.

Header:

- `Back to catalog` -> `Catalog/Index`.

Table:

- Order number, date, total, status, action select.

Action select:

- Form posts to `OrderManagement/OrderProcess`.
- Hidden `orderId`.
- Select name `actionCode`.
- Disabled unless status is exactly `paid`.
- Options:
  - `Select Action`
  - separator `------------------`
  - available action from `Order.ActionCodeSelectList`.
- For `paid`, available action is Ship.
- On change, JavaScript submits that row's form.

POST behavior:

- If `actionCode == OrderProcessAction.Ship.Code`, calls ordering ship API.
- Redirects to `OrderManagement/Index`.
- If ordering service returns internal server error, service throws `Error in ship order process, try later.`

## WebMVC Campaigns

Page:

- `GET /Campaigns/Index`
- Requires OpenID Connect authentication.

Hero:

- Shows campaign hero text image `images/main_banner_text.png`.

Header:

- `Back to catalog` -> `Catalog/Index`.

Location update form:

- Title text: `UPDATE USER LOCATION`.
- Form action: `Campaigns/CreateNewUserLocation`.
- Method: POST.
- Fields:
  - `Lat`, placeholder `Latitude`, default `47.604610`.
  - `Lon`, placeholder `Longitude`, default `-122.315752`.
- Submit button text: `Update`.

Validation:

- `Lat` required and must be between `-90` and `90`.
- Latitude error:
  - `Latitude must be between -90 and 90 degrees inclusive.`
- `Lon` required and must be between `-180` and `180`.
- Longitude error:
  - `Longitude must be between -180 and 180 degrees inclusive.`
- If model invalid, validation summary appears in warning alert.

Successful location update:

- Posts current coordinates to Locations API.
- Redirects to `Campaigns/Index`.

Campaign listing:

- If campaigns exist:
  - Shows cards in a row.
  - Shows pagination.
- If no campaigns:
  - Displays `THERE ARE NO CAMPAIGNS`.

Campaign card:

- Shows image, name, date range.
- Button behavior:
  - If `ActivateCampaignDetailFunction` is true, button text `More Details` opens `DetailsUri` in a new window.
  - Otherwise submits to local `Campaigns/Details/{id}` with button text `More details`.

Campaign details:

- Page `GET /Campaigns/Details?id={id}` or route id.
- Header links:
  - `Back to catalog`.
  - `Back to Campaigns`.
- Shows large image, name, description, and date range:
  - `From {Month dd, yyyy} until {Month dd, yyyy}`.
- If campaign service returns null, controller returns `404`.

Pagination:

- Previous/Next links route to `Campaigns/Index?page={n}`.
- Text says `products` even for campaigns:
  - `Showing {ItemsPerPage} of {TotalItems} products - Page {ActualPage + 1} - {TotalPages}`.

## WebMVC Login and Logout Behavior

Login link:

- Anonymous header link points to WebMVC `Account/SignIn`.
- `Account/SignIn` requires OpenID Connect and triggers OIDC challenge when unauthenticated.
- After authentication:
  - Logs user.
  - Stores access token in `ViewData["access_token"]` when present.
  - Redirects to `Catalog/Index`.

Logout:

- Authenticated dropdown `Log Out` submits `logoutForm` to WebMVC `Account/SignOut`.
- `Account/SignOut`:
  - Signs out cookie auth scheme.
  - Signs out OpenID Connect scheme.
  - Returns OpenID Connect sign-out result with redirect URI pointing to catalog home.

## Identity.API Login/Register/Logout

### Login Page

Page:

- `GET /Account/Login?returnUrl={url}`

Top tab header:

- `REGISTER` link -> `Account/Register?returnUrl={ReturnUrl}`.
- Active `LOGIN` tab.

Form:

- POST `Account/Login`.
- Hidden `ReturnUrl`.
- Heading: `ARE YOU REGISTERED?`
- Fields:
  - `Email`, required, email address.
  - `Password`, required, password input.
  - `RememberMe`, checkbox, label `Remember me?`.
- Submit button: `LOG IN`.
- Link: `Register as a new user?`

Demo copy:

- Shows demo credentials:
  - User: `demouser@microsoft.com`
  - Password: `Pass@word1`

Validation and failure:

- Validation summary displays all errors.
- Invalid credentials error:
  - `Invalid username or password.`
- If authorization context contains external IdP, controller throws:
  - `External login is not implemented!`

Successful login:

- Signs in local user.
- If `RememberMe` is checked, persistent token lifetime uses `PermanentTokenLifetimeDays`; otherwise uses `TokenLifetimeMinutes`.
- Redirects to valid `ReturnUrl`.
- If return URL is not valid, redirects to `~/`.

### Register Page

Page:

- `GET /Account/Register?returnUrl={url}`

Top tab header:

- Active `REGISTER`.
- `LOGIN` link -> `Account/Login?returnUrl={ReturnUrl}`.

Form:

- POST `Account/Register`.
- Heading: `CREATE NEW ACCOUNT`.
- Validation summary displays all errors.

Profile fields:

- `User.Name`, label `NAME`, required.
- `User.LastName`, label `LAST NAME`, required.
- `User.Street`, label `ADDRESS`, required.
- `User.City`, required.
- `User.State`, required.
- `User.Country`, required.
- `User.ZipCode`, label `POSTCODE`, required.
- `User.PhoneNumber`, label `PHONE NUMBER`.

Payment fields:

- `User.CardNumber`, label `Card Number`, required.
- `User.CardHolderName`, label `Cardholder Name`, required.
- `User.Expiration`, label `Expiration Date`, placeholder `MM/YY`, required, regex `MM/YY`.
- `User.SecurityNumber`, label `Security Code`, required.

Account fields:

- `Email`, required, email address.
- `Password`, required, length 6-100.
- `ConfirmPassword`, must match password.

Custom validation messages:

- Password length:
  - `The Password must be at least 6 and at max 100 characters long.`
- Confirm password:
  - `The password and confirmation password do not match.`
- Expiration regex:
  - `Expiration should match a valid MM/YY value`
- Additional Identity errors from `UserManager.CreateAsync` are displayed in summary.

Submit button:

- `Register`.

Successful registration:

- Creates `ApplicationUser`.
- If `returnUrl` is present and user is already authenticated, redirects to `returnUrl`.
- If `returnUrl` is present and user is not authenticated, redirects to `Account/Login?returnUrl={returnUrl}`.
- If no `returnUrl`, redirects to `Home/Index`.

### Logout Page

Page:

- `GET /Account/Logout?logoutId={id}`

Behavior:

- If not authenticated, immediately completes logout flow.
- If sign-out prompt is disabled by IdentityServer context, completes logout flow.
- Otherwise shows confirmation page.

Confirmation UI:

- Heading: `Logout`.
- Text: `Would you like to logout of IdentityServer?`
- Hidden `logoutId`.
- Button: `Yes`.

POST behavior:

- Signs out external IdP when needed.
- Signs out local cookie and application scheme.
- Redirects to IdentityServer post-logout redirect URI.

### Consent Page

Page:

- `GET /Consent/Index?returnUrl={url}`

UI:

- Shows client logo when available.
- Heading:
  - `{ClientName} is requesting your permission`
- Text:
  - `Uncheck the permissions you do not wish to grant.`

Sections:

- Personal Information for identity scopes.
- Application Access for API scopes.
- Each scope row has checkbox, display name, required marker, description.
- Required scopes are disabled and mirrored with hidden input.
- Emphasized scopes show warning glyph.
- If client allows remembering consent:
  - Checkbox `Remember My Decision`.

Buttons:

- `Yes, Allow`, name `button`, value `yes`.
- `No, Do Not Allow`, name `button`, value `no`.
- If client URL exists, external info button with client name.

Validation:

- If yes and no scopes selected:
  - `You must pick at least one permission.`
- If unknown button:
  - `Invalid Selection`

Successful consent:

- Grants or denies consent through IdentityServer.
- Redirects to `ReturnUrl`.

## WebhookClient UI

Layout:

- Navbar brand: `Order Management`.
- Links:
  - `Home` -> `/Index`
  - `Register webhook` -> `/RegisterWebhook`
  - `Webhooks registered (in API)` -> `/WebhooksList`
- Footer repeats links.

### Home Page

Page:

- `/` or `/Index`.

Content:

- Shows brand image and heading `eShopOnContainers - Order Management`.
- Explains that the app shows paid-order webhooks.
- If anonymous:
  - Shows `Login` button -> `Account/SignIn`.
  - Shows text:
    - `Why I need to login? You only need to login to setup a new webhook.`

Received webhooks table:

- Heading: `Current webhooks received (orders paid)`.
- Note: `Data since last time web started up`. Must manually refresh.
- Rows show:
  - Received time.
  - Payload in `<pre>`.
  - Token or `--None--`.

### Register Webhook Page

Page:

- `/RegisterWebhook`
- Requires authentication.

Content:

- Heading: `Register a new webhook`.
- Explains it registers the `OrderPaid` webhook.
- Form method POST.
- Field:
  - `Token`, text input, prefilled from settings.
- Submit input value:
  - `send`.

POST behavior:

- Builds self URL.
- Grant URL: `{selfUrl}/check`.
- Webhook URL: `{selfUrl}/webhook-received`.
- Sends Webhooks API subscription:
  - Event `OrderPaid`.
  - Token from form.
- On success, redirects to `WebhooksList`.
- On failure, redisplays page with:
  - `Error {ResponseCode} ({ResponseMessage}) when calling the Webhooks API ({RequestUrl}) with GrantUrl: {GrantUrl})`
  - `Data sent to the webhooks API was {RequestBodyJson}`

### Webhooks List Page

Page:

- `/WebhooksList`

Content:

- Heading:
  - `List of Webhooks registered by user {User.Identity.Name}`
- Table columns:
  - Date.
  - Destination Url.
  - Validation token.

### Webhook Receiver

Endpoint:

- `POST /webhook-received`

Behavior:

- Validates `X-eshop-whtoken` when configured.
- If accepted, stores webhook in in-memory/repository backing shown on home page.
- If rejected, returns bad request and does not show it in UI.

## Navigation Summary

Primary storefront journey:

1. Anonymous user lands on `Catalog/Index`.
2. User clicks Login.
3. WebMVC starts OIDC sign-in and Identity.API shows login.
4. User logs in and returns to catalog.
5. User clicks `[ ADD TO CART ]`.
6. User opens cart from cart badge.
7. User changes quantities and clicks `[ Update ]`, or clicks `[ Checkout ]`.
8. Checkout page shows order draft and prefilled user profile/payment data.
9. User clicks `[ Place Order ]`.
10. User lands on My Orders.
11. SignalR toast updates order state as backend events progress.

Order management journey:

1. User opens `My orders`.
2. User clicks `Orders Management`.
3. Paid orders expose action dropdown.
4. Selecting Ship posts the form and redirects back to order management.

Campaign journey:

1. Authenticated user opens `Campaigns`.
2. User updates latitude/longitude.
3. Marketing read model is refreshed through backend event flow.
4. User browses campaigns and opens local or external campaign details.

Webhook journey:

1. User opens WebhookClient.
2. User logs in if they need to register a webhook.
3. User opens Register Webhook, optionally changes token, clicks `send`.
4. User can view registered webhooks.
5. Paid-order webhooks appear on home page after manual refresh.

