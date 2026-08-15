package com.app.ecom.order.dto;

import com.app.ecom.order.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
public class OrderDto {
    private Long id;
    private OrderStatus status;
    private List<OrderItemDto> items;
    private BigDecimal totalAmount;
    private ShippingAddressDto shippingAddress;
    private Instant createdAt;
}
