package com.app.ecom.mapper;

import com.app.ecom.Cart;
import com.app.ecom.CartItem;
import com.app.ecom.Product;
import com.app.ecom.dto.CartDto;
import com.app.ecom.dto.CartItemDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class CartMapper {

    public CartDto toDto(Cart cart) {
        if (cart == null) {
            return emptyCart();
        }

        List<CartItemDto> itemDtos = cart.getItems().stream()
                .map(this::toItemDto)
                .toList();

        BigDecimal totalAmount = itemDtos.stream()
                .map(CartItemDto::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalItems = itemDtos.stream()
                .mapToInt(CartItemDto::getQuantity)
                .sum();

        CartDto dto = new CartDto();
        dto.setId(cart.getId());
        dto.setItems(itemDtos);
        dto.setTotalItems(totalItems);
        dto.setTotalAmount(totalAmount);
        return dto;
    }

    public CartDto emptyCart() {
        CartDto dto = new CartDto();
        dto.setItems(List.of());
        dto.setTotalItems(0);
        dto.setTotalAmount(BigDecimal.ZERO);
        return dto;
    }

    private CartItemDto toItemDto(CartItem item) {
        Product product = item.getProduct();
        CartItemDto dto = new CartItemDto();
        dto.setProductId(product.getId());
        dto.setProductName(product.getName());
        dto.setUnitPrice(product.getPrice());
        dto.setQuantity(item.getQuantity());
        dto.setLineTotal(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        return dto;
    }
}
