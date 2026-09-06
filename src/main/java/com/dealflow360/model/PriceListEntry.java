package com.dealflow360.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Customer-tier price list (PDF A2 - "Price Lists: Customer tier based pricing").
 * One row = "for this tier, this product costs X instead of its catalog price". When a
 * line is added to a quotation, {@code QuotationService} looks the customer's tier up here
 * first and only falls back to {@link Product#getPrice()} when no row exists - so Gold
 * customers can carry a negotiated list price without anyone touching discounts (which
 * are governed separately by the discount ceilings / approval chain).
 */
@Entity
@Table(name = "price_list_entry", uniqueConstraints = @UniqueConstraint(columnNames = {"tier", "product_id"}))
public class PriceListEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerTier tier;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Optional currency label (bonus in the PDF) - purely informational, all maths stays in one currency. */
    private String currency = "INR";

    public PriceListEntry() {
    }

    public PriceListEntry(CustomerTier tier, Product product, BigDecimal price) {
        this.tier = tier;
        this.product = product;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CustomerTier getTier() {
        return tier;
    }

    public void setTier(CustomerTier tier) {
        this.tier = tier;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    /** Convenience for the admin screen - avoids serialising the whole product graph. */
    @JsonProperty("productId")
    public Long productId() {
        return product != null ? product.getId() : null;
    }

    @JsonProperty("productName")
    public String productName() {
        return product != null ? product.getName() : null;
    }

    @JsonProperty("catalogPrice")
    public BigDecimal catalogPrice() {
        return product != null ? product.getPrice() : null;
    }
}
