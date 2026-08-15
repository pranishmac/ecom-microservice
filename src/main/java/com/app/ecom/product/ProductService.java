package com.app.ecom.product;

import com.app.ecom.product.dto.ProductDto;
import com.app.ecom.product.dto.ProductRequestDto;
import com.app.ecom.common.exception.DuplicateResourceException;
import com.app.ecom.common.exception.InsufficientStockException;
import com.app.ecom.common.exception.ResourceNotFoundException;
import com.app.ecom.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductDto createProduct(ProductRequestDto request) {
        if (productRepository.existsBySkuIgnoreCase(request.getSku())) {
            throw new DuplicateResourceException("A product with SKU '" + request.getSku() + "' already exists");
        }
        Product product = productMapper.toEntity(request);
        return productMapper.toDto(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public ProductDto fetchProduct(Long id) {
        return productMapper.toDto(getProductOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ProductDto> searchProducts(ProductCategory category, String search, Pageable pageable) {
        boolean hasCategory = category != null;
        boolean hasSearch = search != null && !search.isBlank();

        Page<Product> page;
        if (hasCategory && hasSearch) {
            page = productRepository.findByActiveTrueAndCategoryAndNameContainingIgnoreCase(category, search, pageable);
        } else if (hasCategory) {
            page = productRepository.findByActiveTrueAndCategory(category, pageable);
        } else if (hasSearch) {
            page = productRepository.findByActiveTrueAndNameContainingIgnoreCase(search, pageable);
        } else {
            page = productRepository.findByActiveTrue(pageable);
        }
        return page.map(productMapper::toDto);
    }

    public ProductDto updateProduct(Long id, ProductRequestDto request) {
        Product product = getProductOrThrow(id);

        productRepository.findBySkuIgnoreCase(request.getSku())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("A product with SKU '" + request.getSku() + "' already exists");
                });

        productMapper.updateEntity(product, request);
        return productMapper.toDto(productRepository.save(product));
    }

    public void deleteProduct(Long id) {
        Product product = getProductOrThrow(id);
        product.setActive(false);
        productRepository.save(product);
    }

    public ProductDto adjustStock(Long id, int quantityChange) {
        Product product = getProductOrThrow(id);
        int newQuantity = product.getStockQuantity() + quantityChange;
        if (newQuantity < 0) {
            throw new InsufficientStockException(
                    "Insufficient stock for product '" + product.getSku() + "': available "
                            + product.getStockQuantity() + ", requested " + Math.abs(quantityChange));
        }
        product.setStockQuantity(newQuantity);
        return productMapper.toDto(productRepository.save(product));
    }

    private Product getProductOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
    }
}
