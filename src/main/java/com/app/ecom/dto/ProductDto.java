package com.app.ecom.dto;

import com.app.ecom.ProductCategory;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class ProductDto {
    private Long id;
    private String name;
    private String description;
    private String sku;
    private BigDecimal price;
    private Integer stockQuantity;
    private ProductCategory category;
    private boolean active;
    private boolean inStock;
    private Instant createdAt;
    private Instant updatedAt;
}
