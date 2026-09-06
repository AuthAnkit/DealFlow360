package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Product recommendation rule (PDF A6 - "Define product pairings based on
 * historical co purchase data", "Mark products as currently promoted so
 * they rank higher in suggestions", "Set minimum margin thresholds so
 * only healthy margin suggestions surface").
 * <p>
 * This is the single rule table behind the Live Recommendation Engine. One row says
 * "when {@code baseProduct} is on a quotation, recommend {@code suggestedProduct}" and the
 * {@link RecommendationType} says how:
 * <ul>
 *   <li>{@code CROSS_SELL} - a complementary product (Laptop -> Mouse). Accepting ADDS it; the
 *       original line stays.</li>
 *   <li>{@code UPSELL} - a higher-value / premium offering alongside what is there (Cloud Suite ->
 *       Analytics Add-on). Accepting ADDS it (an upgrade-in-place is also offered).</li>
 *   <li>{@code PRODUCT_UPGRADE} - a premium version of the same thing (Laptop Basic -> Laptop
 *       Pro). Accepting REPLACES the source line with the upgrade, preserving quantity.</li>
 * </ul>
 * The columns added for the engine ({@code recommendationType}, {@code priority}, {@code active},
 * {@code promotionTag}, {@code reason}, timestamps) are all nullable on purpose so the table
 * migrates cleanly on a database that already holds rules (see the note on
 * {@code Quotation.approvedRiskScore}); the getters map a null to the sensible default
 * (CROSS_SELL, priority 50, active), so pre-existing rows keep behaving exactly as before.
 */
@Entity
@Table(name = "upsell_rule")
public class UpsellRule {

    public enum RecommendationType {
        CROSS_SELL,
        UPSELL,
        PRODUCT_UPGRADE
    }

    public static final int DEFAULT_PRIORITY = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "base_product_id")
    private Product baseProduct;

    @ManyToOne(optional = false)
    @JoinColumn(name = "suggested_product_id")
    private Product suggestedProduct;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal minMarginThreshold = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean promoted = false;

    @Enumerated(EnumType.STRING)
    private RecommendationType recommendationType = RecommendationType.CROSS_SELL;

    /** 0-100, higher ranks first. */
    private Integer priority = DEFAULT_PRIORITY;

    private Boolean active = Boolean.TRUE;

    /** Optional badge shown on the card, e.g. "Recommended Upgrade", "Bundle offer". */
    private String promotionTag;

    /** Why the rep should offer it - benefits of the upgrade, or the pairing rationale. */
    @Column(length = 500)
    private String reason;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    public UpsellRule() {
    }

    public UpsellRule(Product baseProduct, Product suggestedProduct, BigDecimal minMarginThreshold, boolean promoted) {
        this.baseProduct = baseProduct;
        this.suggestedProduct = suggestedProduct;
        this.minMarginThreshold = minMarginThreshold;
        this.promoted = promoted;
    }

    public UpsellRule(Product baseProduct, Product suggestedProduct, RecommendationType type, int priority,
                      BigDecimal minMarginThreshold, boolean promoted, String promotionTag, String reason) {
        this(baseProduct, suggestedProduct, minMarginThreshold, promoted);
        this.recommendationType = type;
        this.priority = priority;
        this.promotionTag = promotionTag;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Product getBaseProduct() {
        return baseProduct;
    }

    public void setBaseProduct(Product baseProduct) {
        this.baseProduct = baseProduct;
    }

    public Product getSuggestedProduct() {
        return suggestedProduct;
    }

    public void setSuggestedProduct(Product suggestedProduct) {
        this.suggestedProduct = suggestedProduct;
    }

    public BigDecimal getMinMarginThreshold() {
        return minMarginThreshold;
    }

    public void setMinMarginThreshold(BigDecimal minMarginThreshold) {
        this.minMarginThreshold = minMarginThreshold;
    }

    public boolean isPromoted() {
        return promoted;
    }

    public void setPromoted(boolean promoted) {
        this.promoted = promoted;
    }

    public RecommendationType getRecommendationType() {
        return recommendationType != null ? recommendationType : RecommendationType.CROSS_SELL;
    }

    public void setRecommendationType(RecommendationType recommendationType) {
        this.recommendationType = recommendationType;
    }

    public int getPriority() {
        return priority != null ? priority : DEFAULT_PRIORITY;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public boolean isActive() {
        return active == null || active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getPromotionTag() {
        return promotionTag;
    }

    public void setPromotionTag(String promotionTag) {
        this.promotionTag = promotionTag;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
