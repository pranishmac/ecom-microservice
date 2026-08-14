
# Code Flow Map

## User module

`UserController.getAllUsers()`
  → `UserService.fetchAllUsers()`
    → `UserRepository.findAll()`
    → `UserMapper.toDto()` (per element)

`UserController.getUserById(id)`
  → `UserService.fetchUser(id)`
    → `UserRepository.findById(id)`
    → `UserMapper.toDto()`

`UserController.createUser(UserRequestDto)`
  → `UserService.createUser(request)`
    → `UserMapper.toEntity(request)`
      → `AddressMapper.toEntity(request.address)` (if present)
    → `UserRepository.save(user)` (cascades to `Address`)
    → `UserMapper.toDto(saved)`
      → `AddressMapper.toDto(saved.address)`

`UserController.updateUser(id, UserRequestDto)`
  → `UserService.updateUser(id, request)`
    → `UserRepository.findById(id)`
    → (mutate `firstName`/`lastName` on the managed entity)
    → `UserRepository.save(existingUser)`

**Last changed:** 2026-08-09 — converted `UserMapper`/`AddressMapper` from static utility classes to injected `@Component` beans; `UserService` now calls `userMapper.toDto()`/`toEntity()` as instance methods instead of static ones.

## Product module

`ProductController.createProduct(ProductRequestDto)` — `@Valid`
  → `ProductService.createProduct(request)`
    → `ProductRepository.existsBySkuIgnoreCase(sku)` → throws `DuplicateResourceException` (409) if true
    → `ProductMapper.toEntity(request)`
    → `ProductRepository.save(product)`
    → `ProductMapper.toDto(saved)`

`ProductController.getProduct(id)`
  → `ProductService.fetchProduct(id)`
    → `ProductRepository.findById(id)` → throws `ResourceNotFoundException` (404) if empty
    → `ProductMapper.toDto()`

`ProductController.getProducts(category?, search?, Pageable)`
  → `ProductService.searchProducts(category, search, pageable)`
    → one of, depending on which filters are present:
      `ProductRepository.findByActiveTrue(pageable)`
      `ProductRepository.findByActiveTrueAndCategory(category, pageable)`
      `ProductRepository.findByActiveTrueAndNameContainingIgnoreCase(search, pageable)`
      `ProductRepository.findByActiveTrueAndCategoryAndNameContainingIgnoreCase(category, search, pageable)`
    → `Page.map(ProductMapper::toDto)`

`ProductController.updateProduct(id, ProductRequestDto)` — `@Valid`
  → `ProductService.updateProduct(id, request)`
    → `ProductRepository.findById(id)` → throws `ResourceNotFoundException` (404) if empty
    → `ProductRepository.findBySkuIgnoreCase(request.sku)` (dup check against other products) → throws `DuplicateResourceException` (409)
    → `ProductMapper.updateEntity(product, request)` (mutates managed entity in place)
    → `ProductRepository.save(product)`
    → `ProductMapper.toDto(saved)`

`ProductController.adjustStock(id, StockAdjustmentRequestDto)` — `@Valid`
  → `ProductService.adjustStock(id, quantityChange)`
    → `ProductRepository.findById(id)` → throws `ResourceNotFoundException` (404) if empty
    → validate `stockQuantity + quantityChange >= 0` → throws `InsufficientStockException` (409) if not
    → `ProductRepository.save(product)` (guarded by `@Version` — concurrent conflicting update throws `OptimisticLockingFailureException` → 409)
    → `ProductMapper.toDto(saved)`

`ProductController.deleteProduct(id)`
  → `ProductService.deleteProduct(id)`
    → `ProductRepository.findById(id)` → throws `ResourceNotFoundException` (404) if empty
    → set `product.active = false`
    → `ProductRepository.save(product)`

All `ProductService`/`UserService` exceptions are caught app-wide by `GlobalExceptionHandler` (`@RestControllerAdvice`) and converted to a JSON `ErrorResponse` body with the appropriate HTTP status — no controller-level try/catch.

**Last changed:** 2026-08-15 — initial implementation of the full Product module (entity, repository, mapper, service, controller, DTOs, exception handling).
