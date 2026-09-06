package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Product master (PDF A2 - "General Info: Name, Category, Price, Unit,
 * Tax, Product Description"). {@code cost} is used to compute margin for
 * the upsell/cross-sell engine and the quotation margin indicator.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Free-text category, e.g. "Hardware", "Service", "Subscription". Used for discount ceilings. */
    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Cost price - used to compute margin (not shown to customers). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cost;

    private String unit = "unit";

    @Column(precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;

    @Column(length = 1000)
    private String description;

    /** Optional catalog photo (a plain image URL - no upload/storage service in this build). Shown in the
     *  Quotation Builder, the Customer Portal, and Upsell suggestions; falls back to a category icon when unset. */
    @Column(length = 1000)
    private String imageUrl;

    /**
     * Whether the product can currently be sold / recommended. Nullable on purpose (existing rows
     * migrate as active); an inactive product stays on historical quotations but is hidden from the
     * Quotation Builder, the customer catalog and the recommendation engine.
     */
    private Boolean active = Boolean.TRUE;

    public Product() {
    }

    public Product(String name, String category, BigDecimal price, BigDecimal cost, String unit, BigDecimal taxPercent, String description) {
        this.name = name;
        this.category = category;
        this.price = price;
        this.cost = cost;
        this.unit = unit;
        this.taxPercent = taxPercent;
        this.description = description;
    }

    public BigDecimal marginPercent() {
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return price.subtract(cost).divide(price, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public boolean isActive() {
        return active == null || active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getTaxPercent() {
        return taxPercent;
    }

    public void setTaxPercent(BigDecimal taxPercent) {
        this.taxPercent = taxPercent;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
