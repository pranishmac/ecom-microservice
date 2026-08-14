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
