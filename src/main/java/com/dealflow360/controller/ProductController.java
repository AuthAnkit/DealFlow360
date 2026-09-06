package com.dealflow360.controller;

import com.dealflow360.model.Product;
import com.dealflow360.model.ProductVariant;
import com.dealflow360.repository.ProductRepository;
import com.dealflow360.repository.ProductVariantRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** PDF A2 - Product & Price List Management. Reading the catalog is open to any signed-in internal role; editing it is Admin-only. */
@RestController
@RequestMapping("/api/products")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class ProductController {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    public ProductController(ProductRepository productRepository, ProductVariantRepository productVariantRepository) {
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
    }

    @GetMapping
    public List<Product> list() {
        return productRepository.findAll();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return productRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Product create(@Valid @RequestBody Product product) {
        product.setId(null);
        return productRepository.save(product);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Product update(@PathVariable Long id, @Valid @RequestBody Product updated) {
        Product existing = get(id);
        existing.setName(updated.getName());
        existing.setCategory(updated.getCategory());
        existing.setPrice(updated.getPrice());
        existing.setCost(updated.getCost());
        existing.setUnit(updated.getUnit());
        existing.setTaxPercent(updated.getTaxPercent());
        existing.setDescription(updated.getDescription());
        existing.setImageUrl(updated.getImageUrl());
        existing.setActive(updated.isActive());
        return productRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        productRepository.deleteById(id);
    }

    @GetMapping("/{id}/variants")
    public List<ProductVariant> variants(@PathVariable Long id) {
        return productVariantRepository.findByProductId(id);
    }

    @PostMapping("/{id}/variants")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductVariant addVariant(@PathVariable Long id, @RequestBody ProductVariant variant) {
        Product product = get(id);
        variant.setId(null);
        variant.setProduct(product);
        return productVariantRepository.save(variant);
    }

    @DeleteMapping("/variants/{variantId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteVariant(@PathVariable Long variantId) {
        productVariantRepository.deleteById(variantId);
    }
}
