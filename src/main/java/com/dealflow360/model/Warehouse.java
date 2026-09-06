package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Warehouse master (PDF A4 - "Create and manage warehouses", "Define
 * shipping cost weighting used by the auto split logic to minimize
 * number of shipments").
 */
@Entity
@Table(name = "warehouse")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String location;

    /** Lower = cheaper/preferred to ship from; used to rank warehouses during auto-split. */
    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal shippingCostWeight = BigDecimal.ONE;

    public Warehouse() {
    }

    public Warehouse(String name, String location, BigDecimal shippingCostWeight) {
        this.name = name;
        this.location = location;
        this.shippingCostWeight = shippingCostWeight;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public BigDecimal getShippingCostWeight() {
        return shippingCostWeight;
    }

    public void setShippingCostWeight(BigDecimal shippingCostWeight) {
        this.shippingCostWeight = shippingCostWeight;
    }
}
