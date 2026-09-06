package com.dealflow360.controller;

import com.dealflow360.dto.QuotationDtos.PriceListEntryRequest;
import com.dealflow360.model.PriceListEntry;
import com.dealflow360.model.Product;
import com.dealflow360.repository.PriceListEntryRepository;
import com.dealflow360.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * PDF A2 - "Price Lists: Customer tier based pricing". Admin/Sales Manager maintain the
 * per-tier prices; every internal role can read them (the Quotation Builder shows which
 * price a customer's tier will get before a line is even added).
 */
@RestController
@RequestMapping("/api/config/price-lists")
@PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','SALES_REP','FINANCE')")
public class PriceListController {

    private final PriceListEntryRepository priceListEntryRepository;
    private final ProductRepository productRepository;

    public PriceListController(PriceListEntryRepository priceListEntryRepository, ProductRepository productRepository) {
        this.priceListEntryRepository = priceListEntryRepository;
        this.productRepository = productRepository;
    }

    @GetMapping
    public List<PriceListEntry> list() {
        return priceListEntryRepository.findAll();
    }

    /** Creates or updates the (tier, product) row - the pair is unique, so re-posting the same pair edits the price. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public PriceListEntry save(@RequestBody PriceListEntryRequest request) {
        if (request.tier == null || request.productId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier and productId are required");
        }
        if (request.price == null || request.price.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price must be zero or more");
        }
        Product product = productRepository.findById(request.productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        PriceListEntry entry = priceListEntryRepository.findByTierAndProductId(request.tier, product.getId())
                .orElseGet(() -> new PriceListEntry(request.tier, product, request.price));
        entry.setPrice(request.price);
        if (request.currency != null && !request.currency.isBlank()) entry.setCurrency(request.currency.trim());
        return priceListEntryRepository.save(entry);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public void delete(@PathVariable Long id) {
        priceListEntryRepository.deleteById(id);
    }
}
