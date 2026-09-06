package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Discount ceiling per (customer tier, product category) - PDF A3:
 * "Define discount ceilings per customer tier (Bronze up to 5%, Silver up
 * to 10%, Gold up to 15%)" and "Define category specific discount
 * ceilings (some product categories allow higher discretion than
 * others)".
 * <p>
 * Example from the PDF: a Gold customer is normally allowed 15%, but
 * within that same order Hardware may allow up to 15% while Service only
 * allows up to 10% because it has thinner margins. That is modelled here
 * as two rows: (GOLD, Hardware, 15) and (GOLD, Service, 10).
 * <p>
 * category = "DEFAULT" is used as the fallback ceiling for a tier when no
 * category-specific row exists for the product's category.
 */
@Entity
@Table(name = "discount_ceiling", uniqueConstraints = @UniqueConstraint(columnNames = {"tier", "category"}))
public class DiscountCeiling {

    public static final String DEFAULT_CATEGORY = "DEFAULT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerTier tier;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDiscountPercent;

    public DiscountCeiling() {
    }

    public DiscountCeiling(CustomerTier tier, String category, BigDecimal maxDiscountPercent) {
        this.tier = tier;
        this.category = category;
        this.maxDiscountPercent = maxDiscountPercent;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getMaxDiscountPercent() {
        return maxDiscountPercent;
    }

    public void setMaxDiscountPercent(BigDecimal maxDiscountPercent) {
        this.maxDiscountPercent = maxDiscountPercent;
    }
}
