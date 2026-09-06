package com.dealflow360.controller;

import com.dealflow360.model.StockLevel;
import com.dealflow360.model.Warehouse;
import com.dealflow360.repository.ProductRepository;
import com.dealflow360.repository.StockLevelRepository;
import com.dealflow360.repository.WarehouseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** PDF A4 - Warehouse & Fulfillment Setup. */
@RestController
@RequestMapping("/api/warehouses")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class WarehouseController {

    private final WarehouseRepository warehouseRepository;
    private final StockLevelRepository stockLevelRepository;
    private final ProductRepository productRepository;

    public WarehouseController(WarehouseRepository warehouseRepository, StockLevelRepository stockLevelRepository, ProductRepository productRepository) {
        this.warehouseRepository = warehouseRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.productRepository = productRepository;
    }

    @GetMapping
    public List<Warehouse> list() {
        return warehouseRepository.findAll();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Warehouse create(@RequestBody Warehouse warehouse) {
        warehouse.setId(null);
        return warehouseRepository.save(warehouse);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Warehouse update(@PathVariable Long id, @RequestBody Warehouse updated) {
        Warehouse existing = warehouseRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setName(updated.getName());
        existing.setLocation(updated.getLocation());
        existing.setShippingCostWeight(updated.getShippingCostWeight());
        return warehouseRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        warehouseRepository.deleteById(id);
    }

    // --------------------------------------------------- stock levels

    @GetMapping("/stock")
    public List<StockLevel> allStock() {
        return stockLevelRepository.findAll();
    }

    @PostMapping("/{warehouseId}/stock/{productId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public StockLevel setStock(@PathVariable Long warehouseId, @PathVariable Long productId,
                                @RequestParam int quantityAvailable, @RequestParam(defaultValue = "10") int replenishmentThreshold) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Warehouse not found"));
        var product = productRepository.findById(productId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        StockLevel stock = stockLevelRepository.findByWarehouseIdAndProductId(warehouseId, productId)
                .orElseGet(() -> new StockLevel(warehouse, product, 0, replenishmentThreshold));
        stock.setQuantityAvailable(quantityAvailable);
        stock.setReplenishmentThreshold(replenishmentThreshold);
        return stockLevelRepository.save(stock);
    }
}
