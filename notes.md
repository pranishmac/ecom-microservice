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
