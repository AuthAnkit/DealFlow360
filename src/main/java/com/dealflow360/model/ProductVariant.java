package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Product variant (PDF A2 - "Variants: Attribute (example: Size or Pack),
 * Values, Extra prices"). Kept intentionally simple: one row per
 * attribute/value combination with its own extra price on top of the
 * base product price.
 */
@Entity
@Table(name = "product_variant")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private String attributeName;

    @Column(nullable = false)
    private String attributeValue;

    @Column(precision = 12, scale = 2)
    private BigDecimal extraPrice = BigDecimal.ZERO;

    public ProductVariant() {
    }

    public ProductVariant(Product product, String attributeName, String attributeValue, BigDecimal extraPrice) {
        this.product = product;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
        this.extraPrice = extraPrice;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getAttributeName() {
        return attributeName;
    }

    public void setAttributeName(String attributeName) {
        this.attributeName = attributeName;
    }

    public String getAttributeValue() {
        return attributeValue;
    }

    public void setAttributeValue(String attributeValue) {
        this.attributeValue = attributeValue;
    }

    public BigDecimal getExtraPrice() {
        return extraPrice;
    }

    public void setExtraPrice(BigDecimal extraPrice) {
        this.extraPrice = extraPrice;
    }
}
