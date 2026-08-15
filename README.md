# ecom-application

A Spring Boot e-commerce backend: users (with a saved address), a product catalog,
a per-user cart, and order placement/cancellation with inventory tracking.

- **Stack:** Java 17, Spring Boot 4.1.0 (Spring MVC + Spring Data JPA + Bean Validation), H2 (in-memory), Lombok, Maven
- **Architecture:** `Controller` (DTOs only) → `Mapper` (DTO ⇄ Entity) → `Service` (`@Transactional`, entities) → `Repository` (Spring Data JPA)
- For the reasoning behind specific design choices, see [`decision.md`](decision.md) (dated decision log), [`flow.md`](flow.md) (call-flow maps per module), and [`notes.md`](notes.md) (in-depth technical deep dive).

## Running the project

```bash
./mvnw spring-boot:run
```

The app starts on **http://localhost:8080**. The database is H2 **in-memory** — all data resets every restart. Schema is created automatically on startup (`spring.jpa.hibernate.ddl-auto=update`).

**H2 console** (inspect the live database): http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:ecomdb`
- User: `sa`, Password: *(empty)*

### Known issue: `mvn test` / `mvn install` currently fail

`pom.xml` is missing a test dependency (`spring-boot-starter-test` or `spring-boot-starter-webmvc-test`), so `EcomApplicationTests.java` fails to compile during the `test-compile` phase. This blocks the full Maven lifecycle, not just `spring-boot:run`. Workarounds:

```bash
./mvnw spring-boot:run -Dmaven.test.skip=true   # run the app
./mvnw compile                                   # compile main sources only
```

Restoring the test dependency to `pom.xml` fixes this properly; not done as part of this change since it wasn't in scope for whatever feature was being built when this was discovered.

## Data model quick reference

| Entity | Key fields | Notes |
|---|---|---|
| `User` | `firstName`, `lastName`, `email`, `phone`, `role`, `address` | `role` defaults to `CUSTOMER` (only value defined so far). `address` is optional, one-to-one. |
| `Address` | `street`, `city`, `state`, `zipCode`, `country` | Owned by `User`; deleted automatically if unlinked (`orphanRemoval`). |
| `Product` | `name`, `sku` (unique), `price`, `stockQuantity`, `category`, `active` | Soft-deleted (`active=false`), never physically removed. `@Version` for optimistic locking on stock changes. |
| `Cart` / `CartItem` | one `Cart` per user, created lazily on first add | Adding an already-present product increments its quantity instead of duplicating the line. |
| `Order` / `OrderItem` | `status`, `totalAmount`, `shippingAddress` (embedded snapshot) | Line items snapshot `productName`/`unitPrice` at order time; `totalAmount` is fixed at placement, not recalculated later. |

**Enums:**
- `UserRole`: `CUSTOMER`
- `ProductCategory`: `ELECTRONICS`, `FASHION`, `GROCERY`, `HOME_APPLIANCES`, `BOOKS`, `TOYS`, `BEAUTY`, `SPORTS`, `OTHER`
- `OrderStatus`: `PENDING`, `CONFIRMED`, `CANCELLED` (orders are currently placed directly as `CONFIRMED` — no separate payment-confirmation step exists)

## Error response format

Every 4xx/5xx from a handled exception returns this shape (`GlobalExceptionHandler`, `com.app.ecom.exception`):

```json
{
  "timestamp": "2026-08-15T02:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Product not found with id: 42",
  "fieldErrors": null
}
```

`fieldErrors` is only populated for request validation failures (400), e.g.:

```json
{
  "status": 400,
  "error": "Validation Failed",
  "message": "One or more fields are invalid",
  "fieldErrors": { "name": "Product name is required", "price": "Price must be greater than 0" }
}
```

| Exception | Status |
|---|---|
| `ResourceNotFoundException` | 404 |
| `DuplicateResourceException` | 409 |
| `InsufficientStockException` | 409 |
| `EmptyCartException` | 409 |
| `InvalidOrderStateException` | 409 |
| `ShippingAddressRequiredException` | 400 |
| `OptimisticLockingFailureException` (concurrent update conflict) | 409 |
| `MethodArgumentNotValidException` (`@Valid` failure) | 400 |

## API reference

There is currently **no authentication** — `{userId}` in cart/order paths is a plain, unvalidated path variable, not derived from a logged-in session. See `notes.md` for why and what changes when auth is added.

### Users — `/api/users`

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/api/users` | — | `200` `UserDto[]` |
| `GET` | `/api/users/{id}` | — | `200` `UserDto`, or `404` (empty body) |
| `POST` | `/api/users` | `UserRequestDto` | `201` `UserDto` + `Location` header |
| `PUT` | `/api/users/{id}` | `UserRequestDto` | `200` plain text message, or `404` (empty body) |

**`UserRequestDto`** (request body for create/update):
```json
{
  "firstName": "Ada",
  "lastName": "Lovelace",
  "email": "ada@example.com",
  "phone": "1234567890",
  "role": "CUSTOMER",
  "address": { "street": "1 Analytical Engine Way", "city": "London", "state": "LDN", "zipCode": "E1 6AN", "country": "UK" }
}
```
`address` is optional. `role` defaults to `CUSTOMER` if omitted.

**`UserDto`** (response): same shape, plus a server-assigned `id`.

> Note: unlike Products/Cart/Orders, the User endpoints predate the validation/exception-handling work and don't yet use `@Valid` or throw typed exceptions — `404`s here are empty-bodied, not the `ErrorResponse` JSON shape above, and `PUT` returns a plain string instead of the updated resource. Flagged as a known inconsistency in `notes.md`, not fixed as part of the features that came after.

### Products — `/api/products`

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/api/products?category=&search=&page=&size=&sort=` | — | `200` paginated `ProductDto[]` |
| `GET` | `/api/products/{id}` | — | `200` `ProductDto`, or `404` |
| `POST` | `/api/products` | `ProductRequestDto` | `201` `ProductDto` + `Location` header, or `409` (duplicate SKU) |
| `PUT` | `/api/products/{id}` | `ProductRequestDto` | `200` `ProductDto`, or `404`/`409` |
| `PATCH` | `/api/products/{id}/stock` | `{ "quantityChange": -3 }` | `200` `ProductDto`, or `409` (would go negative / concurrent conflict) |
| `DELETE` | `/api/products/{id}` | — | `204` (soft delete — sets `active=false`) |

`category`/`search` on the list endpoint are both optional and combinable. Default sort: `name, id` ascending, page size 20.

**`ProductRequestDto`**:
```json
{
  "name": "Wireless Mouse",
  "description": "Ergonomic wireless mouse",
  "sku": "WM-1001",
  "price": 25.99,
  "stockQuantity": 50,
  "category": "ELECTRONICS"
}
```
All fields required except `description`. `price` must be > 0 (max 8 integer digits, 2 decimal places); `stockQuantity` must be ≥ 0.

**`ProductDto`** (response): all request fields, plus `id`, `active`, `inStock` (computed: `stockQuantity > 0`), `createdAt`, `updatedAt`.

**Stock adjustment** (`PATCH .../stock`): `quantityChange` is a relative delta — negative to deduct, positive to restock. Rejected with `409` if it would take stock below zero, or if a concurrent update raced it (optimistic lock).

**Delete** is a soft delete: `GET /api/products/{id}` still returns the product afterward (with `active: false`); the paginated list endpoint excludes it.

### Cart — `/api/users/{userId}/cart`

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/api/users/{userId}/cart` | — | `200` `CartDto` (empty cart if none exists yet — never `404`) |
| `POST` | `/api/users/{userId}/cart/items` | `AddToCartRequestDto` | `200` `CartDto` (full updated cart) |
| `DELETE` | `/api/users/{userId}/cart/items/{productId}` | — | `200` `CartDto`, or `404` if not in the cart |

**`AddToCartRequestDto`**:
```json
{ "productId": 1, "quantity": 2 }
```
`quantity` must be ≥ 1. Adding a product already in the cart **increments** its quantity rather than creating a duplicate line. The product must exist and be `active` (`404` otherwise). No stock check happens here — availability is only enforced at order placement.

**`CartDto`** (response):
```json
{
  "id": 1,
  "items": [
    { "productId": 1, "productName": "Wireless Mouse", "unitPrice": 25.99, "quantity": 2, "lineTotal": 51.98 }
  ],
  "totalItems": 2,
  "totalAmount": 51.98
}
```
`totalItems` is the sum of quantities (not the number of distinct lines).

### Orders — `/api/users/{userId}/orders`

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/users/{userId}/orders` | `PlaceOrderRequestDto` | `201` `OrderDto` + `Location` header, or `409`/`400` |
| `GET` | `/api/users/{userId}/orders?page=&size=` | — | `200` paginated `OrderDto[]` |
| `GET` | `/api/users/{userId}/orders/{orderId}` | — | `200` `OrderDto`, or `404` |
| `POST` | `/api/users/{userId}/orders/{orderId}/cancel` | — | `200` `OrderDto`, or `409` (already cancelled) |

Default order listing sort: `createdAt, id` descending (newest first).

**`PlaceOrderRequestDto`**:
```json
{ "shippingAddress": { "street": "...", "city": "...", "state": "...", "zipCode": "...", "country": "..." } }
```
`shippingAddress` is **optional** — if omitted, falls back to the user's saved `Address`; if neither is present, `400 ShippingAddressRequiredException`. Placing an order with an empty cart returns `409 EmptyCartException`. Any line item that can't be fully stocked returns `409 InsufficientStockException` and **rolls back the entire order** (nothing is charged, no stock is touched, the cart is untouched) — verified behavior, not just intended.

Placing an order: decrements stock for every line item, snapshots `productName`/`unitPrice` at that moment into `OrderItem`, computes and persists `totalAmount`, clears the cart, sets `status = CONFIRMED`.

Cancelling an order: restores stock for every line item, sets `status = CANCELLED`. Cancelling an already-cancelled order returns `409` rather than double-restoring stock.

**`OrderDto`** (response):
```json
{
  "id": 1,
  "status": "CONFIRMED",
  "items": [
    { "productId": 1, "productName": "Wireless Mouse", "unitPrice": 25.99, "quantity": 2, "lineTotal": 51.98 }
  ],
  "totalAmount": 51.98,
  "shippingAddress": { "street": "...", "city": "...", "state": "...", "zipCode": "...", "country": "..." },
  "createdAt": "2026-08-15T02:00:00Z"
}
```

## End-to-end example

```bash
# 1. Create a user
curl -X POST localhost:8080/api/users -H "Content-Type: application/json" \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada@example.com","phone":"1234567890"}'
# => {"id":1, ...}

# 2. Create a product
curl -X POST localhost:8080/api/products -H "Content-Type: application/json" \
  -d '{"name":"Wireless Mouse","sku":"WM-1001","price":25.99,"stockQuantity":10,"category":"ELECTRONICS"}'
# => {"id":1, ...}

# 3. Add it to the cart
curl -X POST localhost:8080/api/users/1/cart/items -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}'

# 4. Check the cart
curl localhost:8080/api/users/1/cart

# 5. Place the order (with a one-time shipping address)
curl -X POST localhost:8080/api/users/1/orders -H "Content-Type: application/json" \
  -d '{"shippingAddress":{"street":"1 Analytical Engine Way","city":"London","state":"LDN","zipCode":"E1 6AN","country":"UK"}}'
# => {"id":1, "status":"CONFIRMED", ...} — cart is now empty, product stock reduced by 2

# 6. List orders
curl localhost:8080/api/users/1/orders

# 7. Cancel it (restores stock)
curl -X POST localhost:8080/api/users/1/orders/1/cancel
```
