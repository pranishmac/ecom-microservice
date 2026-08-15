package com.app.ecom;

import com.app.ecom.dto.OrderDto;
import com.app.ecom.dto.PlaceOrderRequestDto;
import com.app.ecom.dto.ProductDto;
import com.app.ecom.exception.EmptyCartException;
import com.app.ecom.exception.InvalidOrderStateException;
import com.app.ecom.exception.ResourceNotFoundException;
import com.app.ecom.exception.ShippingAddressRequiredException;
import com.app.ecom.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final ProductService productService;
    private final OrderMapper orderMapper;

    public OrderDto placeOrder(Long userId, PlaceOrderRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        Cart cart = cartRepository.findByUserId(userId)
                .filter(c -> !c.getItems().isEmpty())
                .orElseThrow(() -> new EmptyCartException("Cannot place an order with an empty cart"));

        ShippingAddress shippingAddress = resolveShippingAddress(user, request);

        Order order = new Order();
        order.setUser(user);
        order.setShippingAddress(shippingAddress);

        BigDecimal total = BigDecimal.ZERO;
        for (CartItem cartItem : cart.getItems()) {
            // Reuses ProductService's stock-decrement rule (validates + decrements atomically,
            // joins this same transaction) rather than duplicating the insufficient-stock check here.
            ProductDto product = productService.adjustStock(cartItem.getProduct().getId(), -cartItem.getQuantity());

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(cartItem.getProduct());
            orderItem.setProductName(product.getName());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            order.getItems().add(orderItem);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);

        cart.getItems().clear();
        cartRepository.save(cart);

        return orderMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<OrderDto> listOrders(Long userId, Pageable pageable) {
        requireUser(userId);
        return orderRepository.findByUserId(userId, pageable).map(orderMapper::toDto);
    }

    @Transactional(readOnly = true)
    public OrderDto fetchOrder(Long userId, Long orderId) {
        return orderMapper.toDto(getOrderOrThrow(userId, orderId));
    }

    public OrderDto cancelOrder(Long userId, Long orderId) {
        Order order = getOrderOrThrow(userId, orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException("Order " + orderId + " is already cancelled");
        }

        for (OrderItem item : order.getItems()) {
            if (item.getProduct() != null) {
                productService.adjustStock(item.getProduct().getId(), item.getQuantity());
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        return orderMapper.toDto(orderRepository.save(order));
    }

    private ShippingAddress resolveShippingAddress(User user, PlaceOrderRequestDto request) {
        if (request.getShippingAddress() != null) {
            return orderMapper.toEmbeddable(request.getShippingAddress());
        }
        if (user.getAddress() != null) {
            return orderMapper.toEmbeddable(user.getAddress());
        }
        throw new ShippingAddressRequiredException(
                "Shipping address is required: provide one in the request or set a default address on your profile");
    }

    private Order getOrderOrThrow(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
    }

    private void requireUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
    }
}
