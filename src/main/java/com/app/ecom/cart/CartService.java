package com.app.ecom.cart;

import com.app.ecom.cart.dto.AddToCartRequestDto;
import com.app.ecom.cart.dto.CartDto;
import com.app.ecom.cart.mapper.CartMapper;
import com.app.ecom.common.exception.ResourceNotFoundException;
import com.app.ecom.product.Product;
import com.app.ecom.product.ProductRepository;
import com.app.ecom.user.User;
import com.app.ecom.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final CartMapper cartMapper;

    @Transactional(readOnly = true)
    public CartDto fetchCart(Long userId) {
        requireUser(userId);
        return cartRepository.findByUserId(userId)
                .map(cartMapper::toDto)
                .orElseGet(cartMapper::emptyCart);
    }

    public CartDto addItem(Long userId, AddToCartRequestDto request) {
        Product product = productRepository.findById(request.getProductId())
                .filter(Product::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found or unavailable: " + request.getProductId()));

        Cart cart = getOrCreateCart(userId);

        cart.getItems().stream()
                .filter(item -> item.getProduct().getId().equals(product.getId()))
                .findFirst()
                .ifPresentOrElse(
                        existing -> existing.setQuantity(existing.getQuantity() + request.getQuantity()),
                        () -> {
                            CartItem item = new CartItem();
                            item.setCart(cart);
                            item.setProduct(product);
                            item.setQuantity(request.getQuantity());
                            cart.getItems().add(item);
                        });

        return cartMapper.toDto(cartRepository.save(cart));
    }

    public CartDto removeItem(Long userId, Long productId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart is empty for user: " + userId));

        boolean removed = cart.getItems().removeIf(item -> item.getProduct().getId().equals(productId));
        if (!removed) {
            throw new ResourceNotFoundException("Product " + productId + " is not in the cart");
        }

        return cartMapper.toDto(cartRepository.save(cart));
    }

    private Cart getOrCreateCart(Long userId) {
        Optional<Cart> existing = cartRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        User user = requireUser(userId);
        Cart cart = new Cart();
        cart.setUser(user);
        return cartRepository.save(cart);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
    }
}
