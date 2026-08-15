# Decision Log

## [2026-08-09 01:18] Add H2 in-memory database + Spring Data JPA

**Context:** App only had an in-memory `ArrayList` inside `UserService` — no real persistence.
**Decision:** Added `spring-boot-starter-data-jpa` and `com.h2database:h2` (runtime scope), converted `User` to a `@Entity`, added `UserRepository extends JpaRepository`, and rewired `UserService` to use it instead of the list.
**Reason:** H2 in-memory gives a real JPA/Hibernate-backed persistence layer with zero external setup — fastest path to a working DB during active development.
**Alternatives considered:** Postgres/MySQL via Docker — deferred as unnecessary overhead for local dev at this stage.
**Files touched:** pom.xml, User.java, UserRepository.java, UserService.java, application.properties

## [2026-08-09 01:23] Fix H2 startup failures

**Context:** App failed to boot: `CREATE TABLE user` threw a SQL syntax error, and Hibernate logged a deprecation warning for an explicit dialect setting.
**Decision:** Added `@Table(name = "users")` to `User`; removed `spring.jpa.database-platform` from `application.properties`.
**Reason:** `USER` is a reserved word in H2 (collides with a built-in function), so the entity needed an explicit non-reserved table name. The dialect property is unnecessary — Hibernate auto-detects it from the JDBC connection — and setting it explicitly is deprecated.
**Alternatives considered:** None — both are correctness fixes, not design choices.
**Files touched:** User.java, application.properties

## [2026-08-09 01:25] Move H2 console to its own dependency

**Context:** `/h2-console` returned a whitelabel 404 despite `spring.h2.console.enabled=true`. Investigation found no `H2Console` auto-configuration class anywhere in Spring Boot 4.1.0's `spring-boot-autoconfigure` jar or any of its split modules.
**Decision:** Added `org.springframework.boot:spring-boot-h2console` as an explicit dependency.
**Reason:** Spring Boot 4 modularized what used to be one `spring-boot-autoconfigure` jar into many small ones (`spring-boot-jdbc`, `spring-boot-jpa`, `spring-boot-webmvc`, etc.); the H2 console auto-configuration moved into its own opt-in module rather than being pulled in implicitly by the JDBC/H2 starters.
**Alternatives considered:** Manually registering `org.h2.server.web.JakartaWebServlet` via a `ServletRegistrationBean` — would have worked but reinvents what the dedicated starter already provides once found.
**Files touched:** pom.xml

## [2026-08-09 17:44] Model User–Address as @OneToOne

**Context:** Needed a User–Address relationship as a one-to-one join.
**Decision:** `User` owns the FK (`address_id`, `unique = true`), `fetch = FetchType.LAZY`, `cascade = CascadeType.ALL`, `orphanRemoval = true`. Bidirectional, with `Address.user` as the inverse (`mappedBy`) side. Added `@ToString.Exclude`/`@EqualsAndHashCode.Exclude` on both sides of the association.
**Reason:** `User` owning the FK matches the natural "user has an address" direction. `unique = true` enforces true 1:1 at the DB level (JPA doesn't add this automatically). `LAZY` avoids `@OneToOne`'s `EAGER`-by-default footgun. `ALL` + `orphanRemoval` is correct because an `Address` has no lifecycle independent of its `User`. The Lombok exclusions prevent `@Data`'s generated `toString`/`equals`/`hashCode` from recursing infinitely across the bidirectional link (and from forcing lazy-proxy initialization).
**Alternatives considered:** Shared primary key via `@MapsId` — rejected; the user explicitly asked for a join-column-based relation, not a shared-PK design.
**Files touched:** User.java, Address.java, AddressRepository.java

## [2026-08-09 17:43] Introduce a DTO layer for User/Address; stop serializing entities directly

**Context:** Serializing `User`/`Address` entities directly required `@JsonManagedReference`/`@JsonBackReference` to prevent infinite recursion over the bidirectional association, and risked `LazyInitializationException` if the lazy `address` field was touched outside a transaction.
**Decision:** Added `UserDto`/`UserRequestDto`/`AddressDto` and `UserMapper`/`AddressMapper`. Controller now speaks only DTOs; `UserService` is `@Transactional` and does entity↔DTO mapping internally (so the lazy `address` field is still loadable during mapping).
**Reason:** Removes the Jackson-annotation workaround entirely, decouples API shape from persistence structure, and lets request/response shapes diverge (`UserRequestDto` has no `id` field — structurally not client-settable).
**Alternatives considered:** Mapping in the controller — rejected because the lazy `address` association would already be outside its Hibernate session by the time the controller touched it.
**Files touched:** dto/UserDto.java, dto/UserRequestDto.java, dto/AddressDto.java, mapper/UserMapper.java, mapper/AddressMapper.java, UserService.java, UserController.java, User.java, Address.java

## [2026-08-09 18:33] Convert mappers from static utilities to Spring-managed beans

**Context:** `UserMapper` called `AddressMapper`'s static methods directly — tight coupling, not mockable/testable, and required hand-written private constructors just to block instantiation of the static-method holder classes.
**Decision:** `AddressMapper` and `UserMapper` are now `@Component` beans with instance methods; `UserMapper` gets `AddressMapper` constructor-injected via `@RequiredArgsConstructor` (same DI pattern already used by `UserService`/`UserController`). `UserService` now holds a `UserMapper` field instead of calling static methods.
**Reason:** Removes the hardcoded compile-time dependency between mapper classes in favor of DI, making each mapper mockable/substitutable in isolation, and eliminates the need for hand-written boilerplate constructors.
**Alternatives considered:** MapStruct — would auto-generate this wiring via annotations, but adds a new annotation-processor dependency for what's currently trivial hand-written mapping logic; deferred until mapping complexity actually justifies it.
**Files touched:** mapper/UserMapper.java, mapper/AddressMapper.java, UserService.java

## [2026-08-15 01:25] Add Product catalog domain

**Context:** Needed a Product entity + full CRUD API, explicitly requested "as per production grade design."
**Decision:** Full vertical slice following the existing layering (`Controller → Mapper → Service → Repository`), plus production-specific additions not yet present anywhere else in the codebase:

- Bean Validation (`spring-boot-starter-validation`) on `ProductRequestDto`, enforced via `@Valid`.
- Soft delete: `Product.active` flag; `DELETE` sets it false rather than removing the row; default listing queries filter to `active = true`; direct `GET /{id}` still returns inactive products (admin/detail use case).
- A dedicated `PATCH /api/products/{id}/stock` endpoint taking a relative `quantityChange`, separate from the general `PUT` update — inventory changes (order placed/cancelled) are a different trigger than an admin editing product details, and needed a natural place to add concurrency protection.
- `@Version` optimistic locking on `Product`, since concurrent stock adjustments (two simultaneous orders) are a real race condition.
- Centralized `@RestControllerAdvice` (`GlobalExceptionHandler`) mapping `ResourceNotFoundException`→404, `DuplicateResourceException`→409 (proactive SKU-uniqueness check before insert, rather than surfacing a raw DB constraint violation), `InsufficientStockException`→409, `OptimisticLockingFailureException`→409, and `MethodArgumentNotValidException`→400 with per-field messages.
- Pagination on the list endpoint (`Pageable`, `@PageableDefault`) rather than returning the full table unpaginated.
  **Reason:** Each addition maps to a concrete failure mode a real catalog API hits (data loss on delete, lost updates on concurrent stock changes, raw stack traces on bad input, unbounded list responses) — see the two explicit design confirmations captured via AskUserQuestion (soft delete over hard delete; dedicated stock endpoint over folding into `PUT`).
  **Alternatives considered:** Hard delete (rejected — irreversible, risky once other entities reference `Product` by FK later); folding stock changes into the general update DTO (rejected — conflates two different triggers and loses the natural home for concurrency protection).
  **Files touched:** Product.java, ProductCategory.java, ProductRepository.java, ProductService.java, ProductController.java, dto/ProductDto.java, dto/ProductRequestDto.java, dto/StockAdjustmentRequestDto.java, mapper/ProductMapper.java, exception/ResourceNotFoundException.java, exception/DuplicateResourceException.java, exception/InsufficientStockException.java, exception/ErrorResponse.java, exception/GlobalExceptionHandler.java, pom.xml

## [2026-08-15 01:30] Stabilize paginated JSON response shape

**Context:** Live smoke-test of `GET /api/products` logged a Spring Data warning: raw `Page` serialization has no guaranteed JSON structure.
**Decision:** Added `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` on `EcomApplication`.
**Reason:** `VIA_DTO` serializes `Page<T>` through Spring Data's documented `PagedModel` shape (`content` + `page: {size, number, totalElements, totalPages}`) instead of relying on `PageImpl`'s undocumented internal structure, which Spring explicitly warns isn't stable across versions.
**Alternatives considered:** Spring HATEOAS's `PagedResourcesAssembler` — heavier, adds a new dependency for a problem `VIA_DTO` already solves with a one-line annotation.
**Files touched:** EcomApplication.java

## [2026-08-15 02:10] Identify "current user" for cart/order endpoints via path variable

**Context:** Cart and Order are the first "belongs to a user" resources in an app with no authentication layer (no Spring Security, no session, no JWT). Every prior endpoint took an explicit resource `{id}`; cart/order needed a way to know *which user's* cart/orders are being acted on.
**Decision:** `/api/users/{userId}/cart/...` and `/api/users/{userId}/orders/...` — `userId` as an explicit path variable, threaded from controller through service as a plain `Long` parameter. Confirmed via AskUserQuestion over the alternative (a custom `X-User-Id` header).
**Reason:** Matches the existing `/api/users/{id}` convention already used everywhere else in this codebase, and is no less secure than any other endpoint today (none are secured). Threading `userId` as a plain method parameter through the service layer, rather than pulling it from request state inside the service, means swapping in real authentication later only touches the controller layer (extracting `userId` from an `Authentication`/`SecurityContext` instead of `@PathVariable`) — the service signatures don't change.
**Alternatives considered:** `X-User-Id` header on top-level `/api/cart`, `/api/orders` — closer to how a real gateway/security-filter might forward a resolved identity, but not chosen; both are equally spoofable today, and the path-variable form stays consistent with the rest of the app.
**Files touched:** CartController.java, OrderController.java, CartService.java, OrderService.java

## [2026-08-15 02:10] Model Cart/CartItem and Order/OrderItem

**Context:** Needed a cart system (add/remove/fetch) and an Order entity/repo backing a "place order" checkout flow.
**Decision:**
- `Cart` — one per user, `@OneToOne` owning the `user_id` FK (unique), created lazily on first add-to-cart rather than eagerly at user-creation time. Holds `items` (`@OneToMany`, cascade `ALL` + `orphanRemoval`).
- `CartItem` — `@ManyToOne` to `Cart` and `Product`; DB-level `UNIQUE(cart_id, product_id)` so a product can only have one line item per cart (adding an already-present product increments its `quantity` instead of inserting a duplicate row).
- `Order` — `@ManyToOne` to `User` (not `@OneToOne` — a user places many orders over time), `items` (`@OneToMany` cascade `ALL`), `status` (`OrderStatus`: `PENDING`/`CONFIRMED`/`CANCELLED`), `totalAmount` (persisted, not derived — see reasoning below), `shippingAddress` (`@Embeddable ShippingAddress`, copied at order-placement time), `@Version` for optimistic locking.
- `OrderItem` — snapshots `productName`/`unitPrice` at order time (so later price/name changes on `Product` don't retroactively rewrite historical orders) while *also* keeping a nullable `@ManyToOne Product` reference (for live navigation back to the product, e.g. "buy again").
- **No `@Version` on `Cart`/`CartItem`**, unlike `Product`/`Order` — deliberately. `@Version` only protects concurrent `UPDATE`s to the entity's *own* row; adding/removing a `CartItem` is an `INSERT`/`DELETE` on the `cart_items` table, not an `UPDATE` on `carts`, so a `Cart`-level version would never actually increment on the operations that matter here. The real protection against a duplicate-insert race is the DB `UNIQUE(cart_id, product_id)` constraint.
**Reason:** `totalAmount` is persisted (not derived like `Product.inStock` or the DTO-level `lineTotal` fields) because it represents the committed amount for a completed transaction — it must stay fixed even if `Product.price` changes afterward, so it can't be recomputed live. The reference-plus-snapshot split on `OrderItem` is the standard trade-off: snapshot what must stay historically accurate, keep a live reference for what's fine to be current.
**Alternatives considered:** `@OneToOne` for `Order.user` — rejected, a user legitimately has many orders. `@Version` on `Cart` for the "double-click add to cart" race — rejected once traced through (see above); the unique constraint is the actual guard, and `@Version` there would be theater, not protection.
**Files touched:** Cart.java, CartItem.java, Order.java, OrderItem.java, OrderStatus.java, ShippingAddress.java, CartRepository.java, OrderRepository.java

## [2026-08-15 02:10] Reuse `ProductService.adjustStock` for checkout stock changes, don't duplicate the rule

**Context:** Placing an order must decrement stock (and fail cleanly if insufficient); cancelling an order must restore it. `ProductService.adjustStock(id, delta)` already implements exactly this — validated, optimistically-locked, delta-based stock mutation — for the `PATCH /api/products/{id}/stock` endpoint.
**Decision:** `OrderService` depends on `ProductService` (not `ProductRepository`) specifically for stock mutation, calling `productService.adjustStock(productId, -quantity)` on placement and `productService.adjustStock(productId, +quantity)` on cancellation. `CartService`, by contrast, depends on `ProductRepository` directly, since it needs an actual `Product` entity reference for the `CartItem.product` field, not a business operation — that split (call the service for a business rule, call the repository for a plain entity reference) is the general rule for when to reach for which.
**Reason:** "Stock cannot go negative" is a business invariant that must have exactly one implementation. Since `ProductService` is a Spring bean and `OrderService`'s own `@Transactional` method is already an active transaction when it calls `productService.adjustStock(...)`, Spring's default `Propagation.REQUIRED` means the call *joins* the existing transaction rather than starting a nested one — so the whole checkout (stock decrements across every cart item, `Order`/`OrderItem` inserts, cart clearing) commits or rolls back as one atomic unit. If item 3 of 4 has insufficient stock, the `InsufficientStockException` triggers a rollback that undoes items 1–2's already-applied decrements too — no manual compensating logic needed. This was verified live: an oversized order attempt left both stock and the cart completely untouched.
**Alternatives considered:** Duplicating the negative-stock check inside `OrderService` against `ProductRepository` directly — rejected, would mean two independent implementations of the same invariant that could drift.
**Files touched:** OrderService.java

## [2026-08-15 02:10] Cart fetch returns 200 with an empty cart, never 404

**Context:** `GET /api/users/{userId}/cart` for a user who has never added anything has no `Cart` row in the database.
**Decision:** `CartService.fetchCart` returns a representation of an empty cart (`items: []`, `totalAmount: 0`, `totalItems: 0`, `id: null`) rather than 404, without persisting anything.
**Reason:** A cart conceptually always exists for a user (it's just usually empty) — a client shouldn't need special-case 404 handling on its cart-page load. `removeItem`, by contrast, still 404s when the cart or item doesn't exist, since removing something that was never there is unambiguously a not-found case, not an empty-state case.
**Alternatives considered:** 404 for consistency with other "fetch by id" endpoints — rejected as worse API ergonomics for this specific resource shape.
**Files touched:** CartService.java, CartMapper.java

## [2026-08-15 02:10] Shipping address falls back to the user's saved Address

**Context:** `Order.shippingAddress` needs a value at checkout time. The existing `User.address` (added earlier in the User–Address `@OneToOne` work) had no consumer yet.
**Decision:** `PlaceOrderRequestDto.shippingAddress` is optional. If provided, it's used (mapped to the `ShippingAddress` embeddable); if omitted, falls back to the user's saved `Address`; if neither exists, throws `ShippingAddressRequiredException` (400).
**Reason:** Standard checkout UX ("ship to my saved address, or enter a new one this time"), and it gives the existing `Address`/`User` relationship an actual consumer instead of remaining a feature nothing used.
**Alternatives considered:** Requiring `shippingAddress` on every order request (simpler, rejected as unrealistic UX) — requiring a saved address to exist before ordering (rejected, blocks first-time checkout unnecessarily).
**Files touched:** OrderService.java, OrderMapper.java, dto/PlaceOrderRequestDto.java, exception/ShippingAddressRequiredException.java

## [2026-08-15 02:10] Order cancellation restores stock; double-cancel guarded

**Context:** "Full flow of place order" naturally implies being able to view and cancel an order, not just create one — otherwise a failed/changed-mind checkout permanently locks up inventory.
**Decision:** `POST /api/users/{userId}/orders/{orderId}/cancel` — rejects (`InvalidOrderStateException`, 409) if the order is already `CANCELLED`; otherwise restores stock for every line item via `productService.adjustStock(productId, +quantity)` and sets `status = CANCELLED`.
**Reason:** Closes the loop the same way placement opened it — through the same single stock-mutation rule in `ProductService`, verified live (keyboard stock: 4 → 2 on order, → 4 again on cancel).
**Alternatives considered:** Allowing cancellation of any order regardless of status — rejected, a re-cancel of an already-cancelled order would double-restore stock that was never actually taken.
**Files touched:** OrderService.java, exception/InvalidOrderStateException.java

## [2026-08-15 02:10] Add a stable sort tiebreaker to paginated listings

**Context:** While adding `OrderRepository.findByUserId(userId, pageable)`, revisited a gap flagged in `notes.md` during the Product feature: sorting by a single non-unique column (`name`) gives pagination no deterministic tiebreaker for rows with equal sort-key values.
**Decision:** `OrderController`'s default sort is `{"createdAt", "id"}` DESC. Also retroactively fixed `ProductController`'s default sort from `"name"` alone to `{"name", "id"}`.
**Reason:** `id` is guaranteed unique and monotonically increasing, so it's a correct tiebreaker for any primary sort key that isn't itself unique — closes a previously-documented, easily-fixable gap while building the same pattern for a second endpoint.
**Alternatives considered:** Leaving Product's sort as-is since it wasn't the current task — rejected; the fix was small, safe, and directly connected to what was already flagged.
**Files touched:** OrderController.java, ProductController.java
