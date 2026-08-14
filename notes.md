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
- **Known gap**: `sort = "name"` alone is not a stable sort key — `name` is
  not unique, so two products with the same name don't have a deterministic
  relative order across pages. In the general case this can cause an item
  to be skipped or repeated across page boundaries if rows are
  inserted/deleted between requests. The standard fix is a secondary
  tiebreaker on a unique column, e.g. sort by `name, id` — not done here
  since it wasn't reached in testing, but worth knowing before relying on
  pagination stability at scale.

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
