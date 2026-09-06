package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Recurring plan attached to a product (PDF A5 - "Define recurring plans
 * (monthly, quarterly, yearly) that can be attached to specific products
 * or services", "Configure proration rules for mid cycle quantity or plan
 * changes", "Configure cancellation and partial refund rules").
 */
@Entity
@Table(name = "subscription_plan")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingCycle billingCycle;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal pricePerCycle;

    /** If true, mid-cycle quantity/plan changes are prorated instead of billed in full. */
    @Column(nullable = false)
    private boolean prorationEnabled = true;

    /** If true, cancelling mid-cycle triggers an automatic partial refund / credit note. */
    @Column(nullable = false)
    private boolean partialRefundOnCancel = true;

    public SubscriptionPlan() {
    }

    public SubscriptionPlan(String name, Product product, BillingCycle billingCycle, BigDecimal pricePerCycle, boolean prorationEnabled, boolean partialRefundOnCancel) {
        this.name = name;
        this.product = product;
        this.billingCycle = billingCycle;
        this.pricePerCycle = pricePerCycle;
        this.prorationEnabled = prorationEnabled;
        this.partialRefundOnCancel = partialRefundOnCancel;
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

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public BillingCycle getBillingCycle() {
        return billingCycle;
    }

    public void setBillingCycle(BillingCycle billingCycle) {
        this.billingCycle = billingCycle;
    }

    public BigDecimal getPricePerCycle() {
        return pricePerCycle;
    }

    public void setPricePerCycle(BigDecimal pricePerCycle) {
        this.pricePerCycle = pricePerCycle;
    }

    public boolean isProrationEnabled() {
        return prorationEnabled;
    }

    public void setProrationEnabled(boolean prorationEnabled) {
        this.prorationEnabled = prorationEnabled;
    }

    public boolean isPartialRefundOnCancel() {
        return partialRefundOnCancel;
    }

    public void setPartialRefundOnCancel(boolean partialRefundOnCancel) {
        this.partialRefundOnCancel = partialRefundOnCancel;
    }
}
