package com.app.ecom.product.mapper;

import com.app.ecom.product.Product;
import com.app.ecom.product.dto.ProductDto;
import com.app.ecom.product.dto.ProductRequestDto;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public ProductDto toDto(Product product) {
        if (product == null) {
            return null;
        }
        ProductDto dto = new ProductDto();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        dto.setSku(product.getSku());
        dto.setPrice(product.getPrice());
        dto.setStockQuantity(product.getStockQuantity());
        dto.setCategory(product.getCategory());
        dto.setActive(product.isActive());
        dto.setInStock(product.getStockQuantity() != null && product.getStockQuantity() > 0);
        dto.setCreatedAt(product.getCreatedAt());
        dto.setUpdatedAt(product.getUpdatedAt());
        return dto;
    }

    public Product toEntity(ProductRequestDto dto) {
        if (dto == null) {
            return null;
        }
        Product product = new Product();
        applyRequest(product, dto);
        return product;
    }

    public void updateEntity(Product product, ProductRequestDto dto) {
        applyRequest(product, dto);
    }

    private void applyRequest(Product product, ProductRequestDto dto) {
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setSku(dto.getSku());
        product.setPrice(dto.getPrice());
        product.setStockQuantity(dto.getStockQuantity());
        product.setCategory(dto.getCategory());
    }
}
