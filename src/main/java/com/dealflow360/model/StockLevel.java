package com.dealflow360.model;

import jakarta.persistence.*;

/**
 * Stock of one product at one warehouse (PDF A4 - "Configure stock levels
 * and replenishment rules per warehouse"). Drives the fulfillment /
 * warehouse-split algorithm.
 */
@Entity
@Table(name = "stock_level", uniqueConstraints = @UniqueConstraint(columnNames = {"warehouse_id", "product_id"}))
public class StockLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private int quantityAvailable;

    @Column(nullable = false)
    private int replenishmentThreshold = 10;

    public StockLevel() {
    }

    public StockLevel(Warehouse warehouse, Product product, int quantityAvailable, int replenishmentThreshold) {
        this.warehouse = warehouse;
        this.product = product;
        this.quantityAvailable = quantityAvailable;
        this.replenishmentThreshold = replenishmentThreshold;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public int getQuantityAvailable() {
        return quantityAvailable;
    }

    public void setQuantityAvailable(int quantityAvailable) {
        this.quantityAvailable = quantityAvailable;
    }

    public int getReplenishmentThreshold() {
        return replenishmentThreshold;
    }

    public void setReplenishmentThreshold(int replenishmentThreshold) {
        this.replenishmentThreshold = replenishmentThreshold;
    }
}
