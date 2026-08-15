package com.app.ecom.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CartDto {
    private Long id;
    private List<CartItemDto> items;
    private int totalItems;
    private BigDecimal totalAmount;
}
