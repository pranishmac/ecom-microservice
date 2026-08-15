package com.app.ecom.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySkuIgnoreCase(String sku);

    Optional<Product> findBySkuIgnoreCase(String sku);

    Page<Product> findByActiveTrue(Pageable pageable);

    Page<Product> findByActiveTrueAndCategory(ProductCategory category, Pageable pageable);

    Page<Product> findByActiveTrueAndNameContainingIgnoreCase(String name, Pageable pageable);

    Page<Product> findByActiveTrueAndCategoryAndNameContainingIgnoreCase(
            ProductCategory category, String name, Pageable pageable);
}
