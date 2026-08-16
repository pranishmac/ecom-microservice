# Notes

## H2 / JPA setup

- Spring Boot 4 modularized what used to be one `spring-boot-starter-web` /
  `spring-boot-autoconfigure` jar into many small ones (`spring-boot-starter-webmvc`,
  `spring-boot-jdbc`, `spring-boot-jpa`, `spring-boot-hibernate`, etc.). The H2
  console auto-configuration in particular now lives in its own dependency
  (`spring-boot-h2console`) rather than being pulled in implicitly — if it's
  missing, `/h2-console` 404s (whitelabel page) even with
  `spring.h2.console.enabled=true` set correctly.
- `USER` is a reserved word in H2 (built-in function/table). Naming the entity
  `User` without `@Table(name = "users")` breaks `CREATE TABLE` with a SQL
  syntax error at startup. Always check reserved-word collisions when naming
  entities after common nouns (`user`, `order`, `group`, `table`...).
- `spring.jpa.database-platform=...H2Dialect` is unnecessary and triggers a
  deprecation warning — Hibernate auto-detects the dialect from the JDBC
  connection. Don't set it explicitly.

## `@OneToOne` design decisions (User ↔ Address)

- **Owning side = the side with `@JoinColumn`.** Put it on `User` (the FK
  `address_id` lives on the `users` table) since "user has an address" is the
  natural direction; `Address` is the inverse side (`mappedBy = "address"`).
- **`@JoinColumn(unique = true)` is not automatic.** JPA's `@OneToOne` doesn't
  enforce a DB-level unique constraint on the FK column unless you say so —
  without it you effectively have a one-to-many with an accidental cardinality
  assumption in application code only.
- **`@OneToOne` defaults to `FetchType.EAGER`.** This is a well-known JPA
  footgun — every `User` fetch silently joins `Address` too. Always set
  `fetch = FetchType.LAZY` explicitly on `@OneToOne`/`@ManyToOne`.
- **`cascade = CascadeType.ALL, orphanRemoval = true`** is correct when the
  child (`Address`) has no independent lifecycle from the parent (`User`).
  Saving/deleting the user propagates; unlinking an address deletes the
  orphaned row.
- **Lombok `@Data` + bidirectional associations = infinite recursion.**
  Generated `toString()`/`equals()`/`hashCode()` walk the whole object graph —
  `User.toString()` → `Address.toString()` → `User.toString()` → stack
  overflow. Fix: `@ToString.Exclude` and `@EqualsAndHashCode.Exclude` on the
  association field on **both** sides. This is a general rule for any
  bidirectional JPA relationship using Lombok, not specific to this entity
  pair. (Also avoids `equals`/`hashCode` accidentally forcing a lazy-proxy
  initialization.)
- **Lazy `@OneToOne` loading requires an open Hibernate session.** If the
  entity (or anything derived from it) is touched after the transaction/session
  closes, accessing the lazy field throws `LazyInitializationException`. This
  is the reason entity→DTO mapping must happen *inside* a `@Transactional`
  service method, not in the controller (see below).

## DTO architecture — why not serialize entities directly

Serializing JPA entities straight to JSON was the original approach; moved
away from it because:

1. Bidirectional associations recurse infinitely without extra Jackson
   annotations (`@JsonManagedReference`/`@JsonBackReference`) — a patch for a
   modeling mismatch, and it doesn't scale as more relations are added.
2. Lazy fields throw if touched outside a transaction (see above).
3. Request vs. response shape can't diverge — e.g. nothing stops a client from
   sending `id` on create unless you separately validate/ignore it.
4. Persistence-layer changes (new column, renamed field, cascade tweak) become
   API-breaking changes by accident.
5. No allowlist for what's exposed — a future sensitive field on the entity
   leaks into responses unless someone remembers `@JsonIgnore`.

**Layering:** `Controller` (DTOs only) → `Mapper` (DTO ⇄ Entity) → `Service`
(entities, owns the `@Transactional` boundary) → `Repository` (entities, DB).
Jackson never sees an entity; Hibernate never sees a DTO.

- **Separate `UserRequestDto` / `UserDto`** even though they look almost
  identical right now: request answers "what can the client send me"
  (no `id` — structurally not settable by a client), response answers "what
  am I allowed to send back." They're expected to diverge over time
  (timestamps, computed fields, password-type fields belonging to only one
  side).

## Why mapping lives in a separate `Mapper` class

- **Not in the controller:** the service needs the entity anyway (to persist
  it), and lazy-field access (`user.getAddress()`) must happen while the
  transaction is still open — i.e. inside the service method, not after it
  returns to the controller.
- **Not inlined in the service:** keeps `UserService` focused on orchestration
  /business rules, not shape-conversion boilerplate. Same reasoning as having
  a `Repository` instead of writing SQL inline in the service.
- **Not a `toDto()` method on the entity itself:** would make the `@Entity`
  (persistence layer) depend on the DTO (API layer) — inverts the dependency
  direction. Entities should stay ignorant of how they're exposed over HTTP.

## Mappers as Spring `@Component` beans, not static utility classes

- Constructor-inject dependent mappers (`UserMapper` depends on
  `AddressMapper`) via `@RequiredArgsConstructor`, same DI pattern as
  `Service`/`Controller` — not manual private no-arg constructors blocking
  instantiation of a static-method holder class.
- Payoff: testable (swap in a mock `AddressMapper` when unit-testing
  `UserMapper`) and composable (mappers can be injected anywhere else that
  needs them via the same DI mechanism as everything else) — static methods
  can't be substituted or mocked without extra tooling.

## `@Transactional` + DTO mapping

- `UserService` is class-level `@Transactional`. This is what makes it safe
  for `userMapper.toDto(entity)` to read `entity.getAddress()` (a lazy field)
  — the Hibernate session is still open for the full duration of the service
  method, including the mapping call at the end.
- General rule: entity→DTO mapping that touches lazy associations must happen
  inside the transactional boundary, never after the service method returns.

## Money as `BigDecimal`, never `double`/`float`

- `Product.price` is `BigDecimal` with `@Column(precision = 10, scale = 2)`.
  `double`/`float` are binary floating point — they cannot represent most
  decimal fractions exactly (`0.1 + 0.2 != 0.3`), which is unacceptable for
  currency where rounding errors compound across transactions/reports. This
  isn't a style preference, it's a correctness requirement for money.
- Validation mirrors the column: `@Digits(integer = 8, fraction = 2)` on
  `ProductRequestDto.price` caps input to the same shape the column allows,
  so a malformed value fails fast with a clear 400 instead of surfacing as a
  DB-level truncation/precision error later.

## Soft delete via a boolean flag, not Hibernate magic

- `Product.active` (plain `boolean`, default `true`). `DELETE` sets it
  `false` instead of removing the row; `ProductRepository.findByActiveTrue*`
  is what the listing endpoint uses, so deleted products silently drop out
  of `GET /api/products` without any query-time filtering logic living in
  the service.
- **Deliberately not** Hibernate's `@SQLDelete` + `@Where(clause = "active
  = true")` (or the newer `@SoftDelete`), which intercept every `delete()`
  call and every `SELECT` against the entity transparently. That's more
  "magic" — it silently rewrites queries app-wide, including ones written
  later by someone who doesn't know the annotation is there, which is a
  common source of "why isn't my query returning that row" debugging
  sessions. An explicit `active` column + explicit `findByActiveTrue*`
  methods keep the filtering visible at the call site instead of hidden in
  entity metadata.
- **Direct `GET /{id}` intentionally does not filter on `active`** — it
  returns the product regardless. This is a deliberate split: the listing
  endpoint is the "browse the catalog" surface (deleted items shouldn't
  appear), while ID-based lookup is treated as the admin/detail surface
  (you already know the ID, e.g. from an order history — you shouldn't get
  a 404 for a product you legitimately need to look up). Same asymmetry
  exists in the `User`/`Address` design: fetch-by-ID is more permissive
  than list/browse.

## Optimistic locking (`@Version`) for concurrent stock updates

- `Product.version` (`@Version`, `Long`). Hibernate appends `and version =
  ?` to every `UPDATE` and increments it on write. If two requests read the
  same row, then both try to update it, the second one's `UPDATE` affects
  zero rows (its `WHERE version = ?` no longer matches) and Hibernate turns
  that into `OptimisticLockingFailureException`.
- **Why this matters specifically for stock**: `adjustStock` does
  read-modify-write (`newQuantity = current + delta`) — the textbook
  lost-update race. Two concurrent orders decrementing stock by reading the
  same starting quantity is a real scenario (two customers checking out the
  last unit at the same moment), not a hypothetical.
- **Why optimistic, not pessimistic (`SELECT ... FOR UPDATE`) locking**:
  optimistic locking assumes conflicts are rare and pays no cost when
  there's no contention — no row locks held, no blocked threads waiting on
  each other. That's the right assumption for typical e-commerce stock
  contention (occasional, not constant). Pessimistic locking would be the
  right call if the same row were being hit at high, sustained concurrency
  (e.g. a flash-sale hot item) where retry storms from optimistic failures
  would themselves become a bottleneck — not implemented here, but worth
  knowing as the next escalation if this endpoint ever becomes a hot path.
- `GlobalExceptionHandler` catches Spring's `OptimisticLockingFailureException`
  (the translated form of JPA's `OptimisticLockException`) and returns 409
  with a message telling the client to retry — conflicts are expected and
  recoverable, not a server error, so they're 409 rather than 500. The
  client is expected to retry the request; there's no server-side retry
  loop here.

## Derived fields belong in the mapper, not the entity

- `ProductDto.inStock` is computed in `ProductMapper.toDto()`
  (`stockQuantity > 0`), not stored as a column on `Product`. A tempting
  alternative is a persisted `ProductStatus` enum (`IN_STOCK`/`OUT_OF_STOCK`)
  — rejected because it's redundant state: two fields (`stockQuantity` and a
  stored status) that must always agree is a bug waiting to happen the
  moment one code path updates `stockQuantity` without also updating the
  status. Computing it at read time makes the inconsistency structurally
  impossible.
- General rule: if a field's value is a pure function of other persisted
  fields, don't persist it — derive it at the boundary where it's needed
  (mapper, for API responses).

## Centralized exception handling (`@RestControllerAdvice`)

- `ProductService` throws typed, unchecked exceptions
  (`ResourceNotFoundException`, `DuplicateResourceException`,
  `InsufficientStockException`) and never touches HTTP concerns — no
  `ResponseEntity` inside the service layer. `GlobalExceptionHandler` is the
  single place that knows "this exception type means this HTTP status."
- **Why not per-controller try/catch**: that scatters the same
  exception→status mapping across every controller, and it's easy for one
  controller to forget a case (which is effectively what happened already —
  see below). A `@RestControllerAdvice` guarantees the mapping is applied
  uniformly to every controller in the app, including ones added later.
- **This exposed an inconsistency with the existing `User` endpoints**:
  `UserController` still returns bare `ResponseEntity.notFound().build()`
  (empty body, no error detail) and a raw string
  (`"User added successfully"`) on update, rather than a structured
  `ErrorResponse`/`UserDto`. `GlobalExceptionHandler` doesn't fix this
  retroactively — it only fires for exceptions that are actually thrown,
  and `UserService` returns `null`/`false` sentinels instead of throwing.
  Worth retrofitting `UserService` to throw `ResourceNotFoundException` too,
  so both domains share one error contract — not done yet, flagged here as
  a follow-up rather than done silently as part of the Product work.
- `MethodArgumentNotValidException` (thrown by `@Valid` before a controller
  method body even runs) is handled the same way, producing
  `{"status":400,"fieldErrors":{"price":"...","name":"..."}}` — one
  consistent error shape (`ErrorResponse`) for both validation failures and
  business-rule failures, so API consumers parse errors one way, not two.
- **Known limitation of the `fieldErrors` map**: it's built with
  `fieldErrors.put(error.getField(), error.getDefaultMessage())` inside a
  `forEach` — if a single field fails *two* constraints (e.g. `@NotBlank`
  and `@Size` both violated), only the last one survives, since the map key
  is the field name. Acceptable for now (messages are usually
  self-explanatory enough either way), but a `Map<String, List<String>>`
  would be the fix if multi-violation-per-field reporting ever matters.

## Proactive uniqueness check vs. DB constraint — a real race condition

- `ProductService.createProduct` calls `existsBySkuIgnoreCase` before
  `save()` specifically so a duplicate SKU comes back as a clean 409 with a
  message, instead of a raw `DataIntegrityViolationException` from the
  DB-level `unique` constraint on `products.sku`.
- **This has a genuine TOCTOU (time-of-check-to-time-of-use) gap**: two
  concurrent `POST` requests with the same SKU can both pass the
  `existsBySkuIgnoreCase` check (neither has committed yet), then both
  attempt `save()` — one succeeds, the other hits the DB unique constraint
  and throws `DataIntegrityViolationException`, which
  `GlobalExceptionHandler` has **no handler for**, so it falls through to
  Spring Boot's default error handling (500). The DB constraint is what
  actually guarantees uniqueness; the service-level check only improves the
  common-case error message. Not fixed here — would need either an
  `@ExceptionHandler(DataIntegrityViolationException.class)` translating to
  409 as a backstop, or accepting that this race is rare enough in practice
  to defer.
- **A second, subtler mismatch**: `existsBySkuIgnoreCase`/`findBySkuIgnoreCase`
  enforce uniqueness *case-insensitively* at the application layer, but the
  DB `unique` constraint on the `sku` column is case-sensitive by default.
  So `"WM-1001"` and `"wm-1001"` would both be rejected by the service
  layer, but nothing stops them from coexisting if a row were ever inserted
  outside this service (a script, a future second write path). The
  application enforces a stricter rule than the database actually backs up.

## Bean Validation at the DTO boundary, not the entity

- `@NotBlank`/`@NotNull`/`@DecimalMin`/`@PositiveOrZero`/`@Digits` live on
  `ProductRequestDto`, validated via `@Valid` in the controller — not on
  `Product` the entity. Validating the entity would mean validation rules
  run (or don't) depending on JPA lifecycle events, and would apply the
  same constraints to every code path that touches the entity, including
  internal ones that may legitimately construct partial objects. Validating
  the DTO means the rule is exactly "what must be true of an incoming HTTP
  request," which is what it actually is.
- `@NotBlank` vs `@NotNull`: used `@NotBlank` on `String` fields (`name`,
  `sku`) because it also rejects empty/whitespace-only strings, not just
  `null` — `@NotNull` alone would let `""` through. Used `@NotNull` for
  non-`String` required fields (`price`, `stockQuantity`, `category`) since
  blank-ness doesn't apply to them.

## Read-only vs. read-write `@Transactional`

- `ProductService` methods that only read
  (`fetchProduct`/`searchProducts`) are explicitly
  `@Transactional(readOnly = true)`, overriding the class-level
  `@Transactional` (read-write) that the writing methods use. Two concrete
  effects: it's a correctness signal (a bug that accidentally mutates state
  in a "read" method would still persist without this — `readOnly` doesn't
  strictly forbid writes at the JPA level, but it's the documented contract
  a reviewer relies on), and it lets Hibernate skip some dirty-checking
  overhead since it knows no flush is needed. In a real multi-datasource
  setup (e.g. read replicas), `readOnly = true` is also what a routing
  `DataSource` typically keys off to send the query to a replica instead of
  the primary — not relevant with a single H2 instance here, but the reason
  to get the habit right regardless.
- `UserService` (from the earlier work) does **not** have this split — it's
  blanket `@Transactional` on every method. Not fixed here, flagged as the
  same kind of follow-up as the exception-handling inconsistency above.

## Pagination: `Pageable`, and why `Page` needed `VIA_DTO`

- `GET /api/products` takes a Spring Data `Pageable` (`?page=&size=&sort=`)
  resolved automatically from query params, with
  `@PageableDefault(size = 20, sort = "name")` as the default when the
  client sends none. Returning the whole table unpaginated is a correctness
  problem, not just a performance one, once the catalog is large enough
  that "all products" stops being a reasonable response size.
- Returning `Page<ProductDto>` directly logged a Spring Data warning at
  runtime: raw `PageImpl` JSON serialization has no structural stability
  guarantee across versions. Fixed with
  `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` on
  `EcomApplication`, which routes serialization through Spring Data's
  documented `PagedModel` shape (`content` + `page: {size, number,
  totalElements, totalPages}`) instead.
- **Gap fixed during the Cart/Order work**: `sort = "name"` alone wasn't a
  stable sort key — `name` isn't unique, so two products with the same name
  had no deterministic relative order across pages, which can skip/repeat
  rows across page boundaries under concurrent inserts/deletes. Fixed to
  `sort = {"name", "id"}` — `id` is unique and monotonic, so it's a correct
  tiebreaker for any non-unique primary sort key. Applied the same pattern
  to `OrderController`'s listing (`{"createdAt", "id"}` DESC) from the
  start, having learned the lesson here first.

## Repository method explosion vs. JPA Specifications

- `ProductRepository` has four derived query methods to cover the
  `category`/`search` filter combinations (neither / category only /
  search only / both) — `ProductService.searchProducts` branches on which
  are present and picks the matching method. This is fine at 2 optional
  filters (2² = 4 combinations) but doesn't scale: a third optional filter
  (e.g. price range) would mean 2³ = 8 methods.
- The point where derived query methods stop being the right tool and JPA
  `Specification<T>` (or Querydsl) becomes worth its added complexity is
  roughly "more than 2-3 independently-optional filters." Noted here as the
  reason this wasn't reached for yet — not a decision that this will never
  be needed, just that it isn't justified by the current filter count.

## Mutating managed entities in place, then calling `save()` anyway

- `updateProduct`, `deleteProduct`, and `adjustStock` all fetch the entity
  via `findById` (which returns it *managed*, attached to the current
  persistence context), mutate a field on it directly, then call
  `productRepository.save(product)`.
- The `save()` call is technically redundant for an already-managed entity
  inside an open transaction — Hibernate's dirty checking would flush the
  change at transaction commit regardless, with or without an explicit
  `save()`. It's kept anyway for two reasons: it matches the existing
  `UserService.updateUser` convention already in the codebase, and it makes
  the write intent explicit at the call site rather than relying on a
  reader knowing dirty-checking semantics implicitly.

# Cart & Order deep dive

Cart (add/remove/fetch) and Order (place/list/get/cancel), built on top of
the same layering as User/Product. This section is the reasoning that's
specific to *these* two domains — where they reuse established patterns is
covered above, where they needed new judgment calls is here.

## Aggregate root pattern: no `CartItemRepository`/`OrderItemRepository`

- `CartItem` and `OrderItem` have entities and DB tables, but deliberately
  no standalone Spring Data repository. All mutation goes through `Cart`
  and `Order` respectively — `CartService` loads a `Cart`, mutates its
  `items` collection in memory, and calls `cartRepository.save(cart)`;
  `orphanRemoval = true` translates an in-memory `list.remove(...)` into a
  `DELETE` on the child row at flush time.
- This is the DDD "aggregate root" idea applied practically: `Cart` is the
  consistency boundary. If `CartItemRepository` existed alongside it, any
  code path could call `cartItemRepository.save(orphanCartItem)` or
  `deleteById(...)` directly, bypassing whatever invariants `Cart` is
  supposed to enforce (right now: the increment-on-duplicate-add rule) —
  the invariant would only hold "as long as everyone remembers to go
  through Cart," which isn't a guarantee, it's a convention someone will
  eventually break. Not exposing the repository makes it structurally
  impossible to bypass, not just discouraged.
- Same reasoning applies to `Order`/`OrderItem`: an order's line items
  don't have an independent lifecycle or their own business rules to
  enforce, so there's nothing a separate repository would be *for*.

## Why `@Version` protects `Order` but would be theater on `Cart`

- This looked like the same problem at first ("concurrent modification of
  a parent+children structure — add `@Version` to the parent") but the two
  cases are actually different once you trace the generated SQL.
- `@Version` works by having Hibernate append `AND version = ?` to the
  `UPDATE` statement for the entity's own row, and increment the value in
  the same statement. It's a compare-and-swap on that one row. `Order`'s
  `cancelOrder` does exactly this: load the order, flip `status`, `save()`
  — that `save()` is an `UPDATE orders SET status = ?, version = ? WHERE id
  = ? AND version = ?`. Two concurrent cancel requests: the second one's
  `UPDATE` matches zero rows (the version it read is now stale), Hibernate
  raises `OptimisticLockingFailureException`, `GlobalExceptionHandler`
  turns it into 409. This is real protection.
- `Cart.addItem`'s race is different: adding a *new* product to the cart is
  an `INSERT INTO cart_items`, not an `UPDATE carts`. A `@Version` field on
  `Cart` would only increment when the `carts` row itself is updated — it
  is not touched at all when a child row is inserted via a `mappedBy`
  collection (the FK lives on `cart_items`, so nothing about `carts` itself
  changes). Putting `@Version` on `Cart` would compile, look correct, and
  do nothing for the actual race between two concurrent "add product X"
  requests. That's why it isn't there — a decision that's easy to get
  backwards without tracing through to the actual SQL each mapping
  produces.
- The real guard for the `CartItem` race is the DB-level
  `UNIQUE(cart_id, product_id)` constraint, which is the same category of
  protection — and the same category of *gap* — as the `Product.sku`
  situation already documented above: `CartService.addItem` checks
  "does a `CartItem` for this product already exist in `cart.items`"
  in-memory before deciding to `INSERT` a new one. Two concurrent
  first-time-adds of the same product can both pass that check before
  either commits, then both attempt an `INSERT` — one succeeds, the other
  hits the unique constraint and throws `DataIntegrityViolationException`,
  which `GlobalExceptionHandler` has no handler for (falls through to a
  500). Same shape of TOCTOU race, same unresolved gap, now in a second
  place — which is itself useful signal: if this pattern shows up a third
  time, it's worth building one shared way to handle
  `DataIntegrityViolationException` → 409 generically instead of
  special-casing it per resource.

## Transaction propagation is what makes checkout atomic

- `OrderService.placeOrder` is a single `@Transactional` method that, per
  cart item, calls `productService.adjustStock(...)` — a method on a
  *different* Spring bean, which is itself annotated `@Transactional`.
- The mechanism that makes this safe: Spring's `@Transactional` defaults to
  `Propagation.REQUIRED` — "run in the current transaction if one is
  already active, otherwise start a new one." Since `OrderService`'s own
  proxy already opened a transaction before `productService.adjustStock`
  is called, the nested call *joins* that same physical transaction rather
  than opening a second one. Concretely: one JDBC connection, one commit
  point, for the entire `placeOrder` call — every stock decrement, the
  `Order`/`OrderItem` inserts, and the cart-clearing all commit together or
  roll back together.
- This is *not* the classic Spring `@Transactional` self-invocation pitfall
  (where calling a `@Transactional` method from another method *in the same
  class* silently skips the proxy and the annotation does nothing). This
  works correctly specifically because it's a **cross-bean** call —
  `OrderService` holds an injected `ProductService` reference, so the call
  goes through `ProductService`'s Spring-generated proxy, which is what
  makes propagation-checking happen at all. Worth knowing precisely because
  the failure mode (self-invocation) and the success mode (cross-bean
  injection) look superficially similar in code and behave completely
  differently.
- Verified live: an order with an oversized quantity for one line item
  threw `InsufficientStockException` partway through the loop, and neither
  the already-processed line item's stock decrement nor the cart itself
  showed any change afterward — confirming the rollback covered everything
  the loop had done up to that point, not just the failing line.

## Persisted `totalAmount` vs. everywhere else's "don't persist derived state" rule

- Earlier in this document: `Product.inStock` isn't persisted because it's
  a pure function of `stockQuantity`, and two fields that must always agree
  is a bug waiting to happen. `Order.totalAmount` looks like the same
  shape of problem (`sum(unitPrice * quantity)` across `OrderItem`s) but is
  handled the opposite way — persisted, computed once at placement time.
- The difference is what the field *means*. `Product.inStock` describes
  *current* state — recomputing it is not just safe but required (if it
  were persisted and stock changed, the stored value would immediately be
  wrong). `Order.totalAmount` describes the amount the customer was
  actually charged *at that moment* — a committed fact about a completed
  transaction, not a live view over current data. If `Product.price`
  changes next week, an order placed today must still show what was
  actually paid, not a recalculated total using the new price. Persisting
  it isn't redundant state here; it's the only correct place for that
  value to live once the transaction is committed. (The per-line
  `lineTotal` in `OrderItemDto`, by contrast, *is* computed at DTO-mapping
  time from persisted `unitPrice * quantity` — those are just arithmetic
  on values that are already frozen snapshots, not a live product lookup,
  so recomputing them is free and safe the same way `inStock` is.)
- Same reasoning is why `OrderItem` stores `productName`/`unitPrice`
  snapshots rather than always reading live from `Product` through the
  `@ManyToOne` reference it also keeps: the reference is for *navigation*
  ("show me this product's current page"), the snapshot is for *the
  historical record* ("this is what they were called/cost when this order
  was placed"). Keeping both isn't redundancy, it's two different
  questions with two different correct answers.

## Threading `userId` as a plain parameter, not pulling it from request context

- `CartController`/`OrderController` take `userId` as an explicit
  `@PathVariable Long`, passed straight through to
  `CartService`/`OrderService` as a plain method parameter — not resolved
  from any kind of request-scoped "current user" context, because no such
  context exists yet (no Spring Security, no session).
- This was a genuine fork with no clearly-correct default (captured via
  AskUserQuestion): path variable (`/api/users/{userId}/cart`) vs. a
  `X-User-Id` header on top-level `/api/cart`. Path variable was chosen for
  consistency with the existing `/api/users/{id}` convention already used
  everywhere else in this app.
- Threading it as an explicit parameter through the service layer (rather
  than, say, having `CartService` reach into some ambient
  `RequestContextHolder`-style state) is deliberate: it means the *only*
  thing that changes when real authentication eventually arrives is the
  controller layer — swap `@PathVariable Long userId` for `userId` pulled
  off an `Authentication`/`Principal` (and probably keep the path variable
  too, but assert it matches the authenticated identity rather than trust
  it blindly). `CartService`/`OrderService`'s method signatures, and
  everything below them, don't need to change at all. Designing the
  service-layer boundary to not know or care *how* the caller identity was
  established — only that it receives one — is what keeps that migration
  small later instead of a rewrite.
- **This is a known, explicit gap today, not a hidden one**: any client can
  currently pass any `userId` and act on that user's cart/orders. Worth
  stating plainly rather than leaving implicit, since it's the kind of gap
  that's easy to forget was ever a placeholder once the code has been
  sitting there for a while.

## Cart fetch's 200-vs-404 choice, and why it doesn't generalize

- `GET .../cart` returns 200 with an empty representation when no `Cart`
  row exists; `GET .../orders/{id}` and `removeItem` both still 404 when
  the row doesn't exist. This isn't an inconsistency — it's two different
  resource shapes. A cart is conceptually singular-per-user and
  always-logically-present (just usually empty); an order is one of
  potentially many, individually created, individually addressable — there
  is no sensible "empty order" to hand back for an ID that was never
  created. The rule isn't "prefer 200 over 404," it's "return 404 exactly
  when the client asked for something identifiable that doesn't exist, and
  200 when the resource's *absence itself* is a valid, meaningful state of
  that resource." Applying "friendly empty state" reasoning to
  `getOrder(id)` would be wrong — a nonexistent order ID isn't "empty," it's
  invalid.

## What the optimistic-lock 409 does *not* do: no server-side retry

- Both the Product PATCH stock endpoint and `OrderService.cancelOrder` can
  throw `OptimisticLockingFailureException` under concurrent conflicting
  writes, which `GlobalExceptionHandler` turns into 409 with a "please
  retry" message. Worth being explicit that this is entirely the *client's*
  responsibility — nothing in this codebase automatically retries a failed
  optimistic-lock write. A caller that doesn't handle 409 by retrying (with
  the now-current state) will simply see the operation fail. This is the
  correct default (silent server-side retry loops hide real conflicts and
  can mask bugs), but it's a contract the API consumer needs to know about,
  not something implicit in "409 means try again eventually on its own."

# Actuator deep dive

Spring Boot Actuator adds an operational surface — health, metrics, logs,
runtime introspection — separate from the business API. This section is
about the reasoning specific to *that* surface: why it's isolated the way
it is, what each endpoint actually does under the hood, and where the
protection genuinely ends.

## Why a separate port, and precisely what it does and doesn't guarantee

- `management.server.port=8081` starts a **second, independent embedded
  Tomcat connector** in the same JVM — not a path prefix on the same
  connector. Verified live: `curl :8080/actuator/health` 404s, and
  `curl :8081/api/products` 404s — genuinely two separate HTTP surfaces,
  each blind to the other's routes.
- What this buys: an actuator request literally cannot reach `/api/**`
  handlers, and vice versa — there's no routing-rule mistake that could
  cross the boundary, because there's no shared router.
- What this does **not** buy: network reachability. If port `8081` is
  published the same way `8080` is (e.g. a naive `docker run -p 8081:8081`
  alongside `-p 8080:8080`, or a Kubernetes `Service` exposing both), the
  isolation is purely cosmetic — anyone who can reach the container can
  still call `/actuator/shutdown`. The actual security boundary is "port
  8081 is not on any network path reachable from outside the deployment" —
  a firewall rule, a container port mapping that only publishes `8080`, a
  k8s `NetworkPolicy` restricting which pods can reach port `8081`. Spring
  configuration cannot express or enforce any of that; it only makes the
  boundary *possible* to enforce elsewhere. This is the load-bearing
  caveat of the whole design — port separation is necessary, not
  sufficient, and it's an infrastructure team's job to finish the job this
  app started.

## The `Access` model, and why `shutdown` needed a version-specific check

- Spring Boot 3.4 replaced the old per-endpoint `management.endpoint.<id>.enabled`
  boolean flags with a three-level `Access` enum: `NONE` (endpoint doesn't
  respond at all), `READ_ONLY` (GET works, write operations don't),
  `UNRESTRICTED` (full access). Each `@Endpoint`-annotated class declares
  its own `defaultAccess` — confirmed by inspecting the actual `.class`
  bytecode for this exact dependency version rather than assuming: the
  `@Endpoint` annotation's own framework-wide default is `UNRESTRICTED`,
  and every endpoint here (`health`, `info`, `metrics`, `loggers`, `beans`,
  `env`, `mappings`) just inherits that — meaning simply adding an
  endpoint's ID to `management.endpoints.web.exposure.include` is
  sufficient to make it fully readable *and*, for endpoints with write
  operations like `loggers`, writable too.
- `ShutdownEndpoint` is the one exception in this codebase's endpoint set:
  its `@Endpoint` annotation explicitly overrides `defaultAccess` to
  `NONE`, so it stays completely disabled — 404, not even readable —
  regardless of the exposure list, until something explicitly raises it
  with `management.endpoint.shutdown.access=unrestricted`. This is a
  deliberate, hard-coded "you must opt in twice" design from the framework
  itself for exactly the operation that can take the whole app down.
- Why this needed verifying against the actual jar rather than recalling
  from memory: this behavior is version-specific. Older Spring Boot used a
  single boolean (`management.endpoint.shutdown.enabled=true`) for the same
  purpose — a plausible, wrong guess here would have produced a `shutdown`
  endpoint that silently 404s, discovered only when actually needed (i.e.,
  during an incident) rather than in testing. Verified instead by
  extracting `ShutdownEndpoint.class` from the resolved
  `spring-boot-actuator-4.1.0.jar` and reading the annotation's bytecode
  directly, then confirmed live with a real `POST` that returned
  `{"message":"Shutting down, bye..."}` and an actually-terminated process.
- There's also a global ceiling worth knowing about even though it isn't
  used here: `management.endpoints.access.max-permitted` (default
  `unrestricted`) caps every endpoint's effective access regardless of
  individual settings — a single knob to lock everything down at once if
  ever needed (e.g. temporarily, during an incident), without touching each
  endpoint's own configuration.

## Health: composite status, and why `show-details=always` is fine *here specifically*

- `/actuator/health`'s top-level `status` is an aggregate: Spring Boot
  auto-registers a `HealthIndicator` per relevant auto-configured component
  — here, `db` (checks the `DataSource` can actually get a connection,
  not just that it exists) and `diskSpace` — and rolls them up into one
  overall `UP`/`DOWN`. Adding, say, a Redis or external-API dependency
  later would add another named component to this same aggregate
  automatically, no wiring required beyond adding that dependency.
- `show-details=always` returns each component's full detail (e.g. `db`'s
  database type and validation query). This is *not* a safe default for a
  publicly reachable health endpoint in general — detail can leak internal
  topology (what's this app actually connected to). It's set to `always`
  here specifically *because* health is only reachable on the isolated
  management port; the same setting on a publicly-exposed health endpoint
  would be a real information-disclosure choice, not a neutral one.
- **Liveness vs. readiness are not the same question**, and conflating them
  is a genuine, common production incident pattern: *liveness*
  (`/actuator/health/liveness`) answers "is this process broken and should
  be killed and restarted" — a deadlock, an unrecoverable internal state.
  *Readiness* (`/actuator/health/readiness`) answers "should traffic be
  routed to this instance right now" — e.g. still warming up, or the
  database is temporarily unreachable but the process itself is fine. If
  an orchestrator's liveness probe were wired to the same check as
  readiness, a transient DB blip would cause Kubernetes to *kill and
  restart* healthy application instances that just happen to be unable to
  reach the database at that moment — restarting the app does nothing to
  fix a database outage, and adds restart-storm load on top of an already
  degraded dependency. `management.endpoint.health.probes.enabled=true`
  exposes both groups separately specifically so an orchestrator's
  liveness and readiness probes can (and should) point at different URLs
  with different consequences.

## `/actuator/info`: two different data sources, one endpoint

- The `app.*` section comes from plain `info.*` properties in
  `application.properties` (`info.app.name`, `info.app.description`) —
  static values, known at deploy-config time.
- The `build.*` section comes from `target/classes/META-INF/build-info.properties`,
  which does not exist until `spring-boot-maven-plugin`'s `build-info` goal
  actually runs (bound to the `prepare-package` phase) — verified this
  distinction concretely: `mvn compile` alone does not produce it,
  `mvn package` (or `spring-boot:run`, which triggers the full lifecycle up
  through packaging) does. If `/actuator/info` ever shows an empty `build`
  section, the build metadata was never generated for that run, not a
  configuration bug in the endpoint itself.
- `management.info.env.enabled` is `false` by default in Spring Boot, and
  had to be explicitly turned on here to get the `info.*` properties
  showing at all under `management.info.env.enabled=true` — a deliberate
  framework default, not an oversight: arbitrary environment/config
  properties can contain secrets, so "show config values on an operational
  endpoint" is opt-in, not opt-out.

## Metrics: two endpoints for two different jobs

- `/actuator/metrics` (and `/actuator/metrics/{name}`) is a live,
  point-in-time JSON snapshot — good for "what is this one number right
  now" during manual debugging, bad for anything needing history (there's
  no storage; ask again in five minutes and the previous value is gone).
- `/actuator/prometheus` exposes the same underlying Micrometer registry in
  Prometheus's text exposition format — meant to be *scraped* on an
  interval by a Prometheus server (or compatible agent), which is what
  actually turns point-in-time numbers into the time series a dashboard or
  alert rule needs. Added `micrometer-registry-prometheus` specifically for
  this: without a registry implementation on the classpath, Micrometer has
  metrics internally but nothing to format them for scraping. The two
  endpoints aren't redundant — `/metrics` is for a human looking right now,
  `/prometheus` is for a scraper building history.

## Loggers: runtime log-level changes, no redeploy

- `/actuator/loggers/{name}` reads/writes the underlying Logback logging
  system's level for that logger name through Spring Boot's
  `LoggingSystem` abstraction — verified live: `POST` with
  `{"configuredLevel":"DEBUG"}` against `com.app.ecom` took effect
  immediately (`effectiveLevel` changed on the next `GET`), no restart.
  Real operational value: turning on `DEBUG` for one noisy area (or
  `org.hibernate.SQL` to see live queries) during an active incident,
  without a redeploy — and turning it back off just as fast once done.
- It's still a write operation reachable by anyone who can reach the
  management port, same as `shutdown` in kind if not in severity — flooding
  logs with `TRACE` across the whole app, or silencing a logger an operator
  is relying on, are both real (if less catastrophic than `shutdown`)
  things an unauthorized caller could do. Same isolation boundary applies;
  there's no separate, lighter-weight protection for this one.

## `beans` and `env`: why they're excluded from "safe to make public" even in spirit

- `/actuator/beans` (375 beans in this app) dumps the entire Spring
  application context — every bean's class, scope, and dependency graph.
  Not a secret in the sense of credentials, but a complete map of the
  app's internal structure that a real attacker would otherwise have to
  infer from behavior — handed over directly.
- `/actuator/env` dumps every resolved configuration property from every
  property source. In *this* app, that's relatively harmless (H2 in
  memory, no real credentials in `application.properties`). In any
  deployment with a real external database, message queue, or third-party
  API key configured via environment variables or a properties file, this
  endpoint would hand over exactly those secrets in plaintext. It behaves
  identically regardless of whether the underlying config happens to be
  sensitive — the endpoint doesn't know the difference, so the protection
  has to come from where it's reachable, not from the endpoint itself.

## Why not Spring Security here, and what changes if it's added later

- Declined via AskUserQuestion in favor of port isolation, for reasons
  worth restating precisely: this app has **zero** existing authentication
  anywhere (every one of the 20+ `/api/**` endpoints is already
  unauthenticated by design, documented repeatedly throughout this file).
  Adding `spring-boot-starter-security` changes that landscape the moment
  it's on the classpath — Spring Security's auto-configuration secures
  *everything* by default and generates a random login password at
  startup, unless a `SecurityFilterChain` explicitly opts routes back out.
  Getting that filter chain's route-matching even slightly wrong (a
  mistyped ant-pattern, an ordering mistake between multiple chains) risks
  either leaving `/actuator/shutdown` open anyway or accidentally locking
  down the public API this whole project has been building — a
  strictly worse failure mode than what port isolation risks.
- Port isolation's honest weakness (repeated from above because it's the
  crux of the whole decision): it depends entirely on the deployment
  environment actually keeping port 8081 off any public path. It is a
  real, load-bearing dependency on infrastructure that this codebase
  cannot verify or enforce for itself.
- **What changes when auth is eventually added**: unlike the `userId`
  path-variable design in Cart/Order (deliberately threaded as a plain
  parameter so only the controller layer changes later), actuator's
  security boundary is *entirely* infrastructure/configuration — no
  application code currently branches on "is this an authenticated
  request." Adding Spring Security scoped to `/actuator/**` later is a
  net-new, additive `SecurityFilterChain` bean plus the new dependency; it
  doesn't require refactoring anything written here. The two features
  (business-API auth, actuator auth) are independent problems that happen
  to share one underlying tool.
