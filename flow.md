
# Code Flow Map

> Package structure is feature-based (`com.app.ecom.user`, `.product`, `.cart`,
> `.order`, `.common.exception`) — see decision.md, 2026-08-15. Class names
> below are unqualified; the call flows themselves are unaffected by the
> restructure since no logic changed, only file locations.

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

**Last changed:** 2026-08-15 — default list sort changed from `"name"` to `{"name", "id"}` (stable pagination tiebreaker; `id` is unique, `name` isn't).

## Cart module

`CartController.getCart(userId)`
  → `CartService.fetchCart(userId)`
    → `UserRepository.findById(userId)` → throws `ResourceNotFoundException` (404) if empty
    → `CartRepository.findByUserId(userId)`
    → `CartMapper.toDto()` if a `Cart` row exists, else `CartMapper.emptyCart()` (200 with an empty cart, never 404 — see decision.md)

`CartController.addItem(userId, AddToCartRequestDto)` — `@Valid`
  → `CartService.addItem(userId, request)`
    → `ProductRepository.findById(productId)` filtered to `active = true` → throws `ResourceNotFoundException` (404) if missing/inactive
    → get-or-create the user's `Cart` (`CartRepository.findByUserId`, else build+save a new one after `UserRepository.findById` validates the user)
    → scan `cart.items` for an existing line for this product: increment `quantity` if found, else append a new `CartItem`
    → `CartRepository.save(cart)` (cascades the new/updated `CartItem`)
    → `CartMapper.toDto(saved)`

`CartController.removeItem(userId, productId)`
  → `CartService.removeItem(userId, productId)`
    → `CartRepository.findByUserId(userId)` → throws `ResourceNotFoundException` (404) if no cart
    → `cart.items.removeIf(...)` matching `productId` → throws `ResourceNotFoundException` (404) if nothing removed (`orphanRemoval` deletes the row on save)
    → `CartRepository.save(cart)`
    → `CartMapper.toDto(saved)`

**Last changed:** 2026-08-15 — initial implementation (Cart, CartItem, CartRepository, CartMapper, CartService, CartController).

## Order module

`OrderController.placeOrder(userId, PlaceOrderRequestDto)` — `@Valid`
  → `OrderService.placeOrder(userId, request)`
    → `UserRepository.findById(userId)` → throws `ResourceNotFoundException` (404) if empty
    → `CartRepository.findByUserId(userId)` filtered to non-empty → throws `EmptyCartException` (409) if empty/missing
    → resolve shipping address: request DTO if present, else `user.getAddress()`, else throws `ShippingAddressRequiredException` (400)
    → for each `CartItem`: `ProductService.adjustStock(productId, -quantity)` (validates + decrements stock, joins this same transaction — throws `InsufficientStockException` (409) or `OptimisticLockingFailureException` (409) on failure, which rolls back everything already applied in this loop) → build a snapshot `OrderItem` (`productName`, `unitPrice` from the now-updated `Product`)
    → `OrderRepository.save(order)` (cascades `OrderItem`s)
    → clear `cart.items`, `CartRepository.save(cart)` (`orphanRemoval` deletes the old `CartItem` rows)
    → `OrderMapper.toDto(saved)`

`OrderController.getOrders(userId, Pageable)`
  → `OrderService.listOrders(userId, pageable)`
    → `UserRepository.existsById(userId)` → throws `ResourceNotFoundException` (404) if false
    → `OrderRepository.findByUserId(userId, pageable)` (default sort `{createdAt, id}` DESC)
    → `Page.map(OrderMapper::toDto)`

`OrderController.getOrder(userId, orderId)`
  → `OrderService.fetchOrder(userId, orderId)`
    → `OrderRepository.findByIdAndUserId(orderId, userId)` → throws `ResourceNotFoundException` (404) if empty (also prevents fetching another user's order by guessing an id)
    → `OrderMapper.toDto()`

`OrderController.cancelOrder(userId, orderId)`
  → `OrderService.cancelOrder(userId, orderId)`
    → `OrderRepository.findByIdAndUserId(orderId, userId)` → throws `ResourceNotFoundException` (404) if empty
    → throws `InvalidOrderStateException` (409) if already `CANCELLED`
    → for each `OrderItem`: `ProductService.adjustStock(productId, +quantity)` (restores stock, same joined-transaction mechanism as placement)
    → set `status = CANCELLED`, `OrderRepository.save(order)`
    → `OrderMapper.toDto(saved)`

All `OrderService`/`CartService` exceptions are caught by the same app-wide `GlobalExceptionHandler` as the User/Product modules — no controller-level try/catch here either.

**Last changed:** 2026-08-15 — initial implementation (Order, OrderItem, OrderStatus, ShippingAddress, OrderRepository, OrderMapper, OrderService, OrderController), plus the Cart module above.
