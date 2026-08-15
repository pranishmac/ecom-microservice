package com.app.ecom.mapper;

import com.app.ecom.Address;
import com.app.ecom.Order;
import com.app.ecom.OrderItem;
import com.app.ecom.ShippingAddress;
import com.app.ecom.dto.OrderDto;
import com.app.ecom.dto.OrderItemDto;
import com.app.ecom.dto.ShippingAddressDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class OrderMapper {

    public OrderDto toDto(Order order) {
        OrderDto dto = new OrderDto();
        dto.setId(order.getId());
        dto.setStatus(order.getStatus());
        dto.setItems(order.getItems().stream().map(this::toItemDto).toList());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setShippingAddress(toDto(order.getShippingAddress()));
        dto.setCreatedAt(order.getCreatedAt());
        return dto;
    }

    public ShippingAddress toEmbeddable(ShippingAddressDto dto) {
        if (dto == null) {
            return null;
        }
        return new ShippingAddress(dto.getStreet(), dto.getCity(), dto.getState(), dto.getZipCode(), dto.getCountry());
    }

    public ShippingAddress toEmbeddable(Address address) {
        if (address == null) {
            return null;
        }
        return new ShippingAddress(address.getStreet(), address.getCity(), address.getState(),
                address.getZipCode(), address.getCountry());
    }

    private OrderItemDto toItemDto(OrderItem item) {
        OrderItemDto dto = new OrderItemDto();
        dto.setProductId(item.getProduct() != null ? item.getProduct().getId() : null);
        dto.setProductName(item.getProductName());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setQuantity(item.getQuantity());
        dto.setLineTotal(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        return dto;
    }

    private ShippingAddressDto toDto(ShippingAddress address) {
        if (address == null) {
            return null;
        }
        ShippingAddressDto dto = new ShippingAddressDto();
        dto.setStreet(address.getStreet());
        dto.setCity(address.getCity());
        dto.setState(address.getState());
        dto.setZipCode(address.getZipCode());
        dto.setCountry(address.getCountry());
        return dto;
    }
}
