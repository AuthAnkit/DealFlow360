package com.dealflow360.dto;

import com.dealflow360.model.UpsellRule.RecommendationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Request/response shapes for the Live Product Recommendation Engine (rules + live panel). */
public class RecommendationDtos {

    // ------------------------------------------------------------ admin: rule configuration (A6)

    public static class RecommendationRuleRequest {
        public Long baseProductId;        // source product ("when the quotation contains ...")
        public Long suggestedProductId;   // recommended product
        public RecommendationType recommendationType = RecommendationType.CROSS_SELL;
        public Integer priority = 50;     // 0-100, higher ranks first
        public Boolean active = Boolean.TRUE;
        public String promotionTag;
        public BigDecimal minMarginThreshold = BigDecimal.ZERO; // suggested product's margin % must be >= this
        public Boolean promoted = Boolean.FALSE;
        public String reason;
    }

    public static class RecommendationRuleResponse {
        public Long id;
        public Long baseProductId;
        public String baseProductName;
        public String baseProductCategory;
        public BigDecimal baseProductPrice;
        public Long suggestedProductId;
        public String suggestedProductName;
        public String suggestedProductCategory;
        public BigDecimal suggestedProductPrice;
        public BigDecimal suggestedProductMarginPercent;
        public RecommendationType recommendationType;
        public int priority;
        public boolean active;
        public String promotionTag;
        public BigDecimal minMarginThreshold;
        public boolean promoted;
        public String reason;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        /** Rule-level sanity warning (e.g. an upgrade whose target is cheaper than the source). */
        public String warning;
    }

    // ------------------------------------------------------------ live panel (B5)

    public static class RecommendationResponse {
        /** Stable id for this recommendation on this quotation: "R{ruleId}" or "R{ruleId}-L{sourceLineId}" for an upgrade. */
        public String recommendationId;
        public Long ruleId;
        public RecommendationType type;
        public Long sourceLineId;            // the cart line that triggered it (always set for PRODUCT_UPGRADE)
        public Long sourceProductId;
        public String sourceProduct;
        public Long recommendedProductId;
        public String recommendedProduct;    // "productName" in the spec
        public String productName;
        public String category;
        public String productImageUrl;
        public BigDecimal currentPrice;      // unit price of the source line (upgrade) - null otherwise
        public BigDecimal price;             // unit price of the recommended product for THIS customer (tier price)
        public Integer quantitySuggestion;   // 1 for an add; the source line's quantity for an upgrade
        public BigDecimal priceImpact;       // change in quotation total if accepted
        public BigDecimal marginImpact;      // change in quotation margin if accepted
        public BigDecimal marginPercent;     // recommended product's own margin %
        public String promotionTag;
        public boolean promoted;
        public BigDecimal priorityScore;     // the ranking score (higher = shown first)
        public String reason;
        public String scoreBreakdown;        // how the score was built - keeps the ranking honest and explainable
        public List<String> actions;         // ADD, UPGRADE, ADD_BOTH - what the rep can do with this card
        public boolean wouldNeedApproval;    // accepting it (at the source line's discount) would push the deal past a ceiling
    }

    public static class RecommendationPanelResponse {
        public List<RecommendationResponse> recommendations;
        public int crossSellCount;
        public int upsellCount;
        public int upgradeCount;
        public int dismissedCount;
        public BigDecimal currentTotal;
        public BigDecimal currentMargin;
    }

    /** Accept / dismiss a recommendation on a quotation. */
    public static class RecommendationActionRequest {
        public Long ruleId;
        public Long sourceLineId;   // required for UPGRADE; optional otherwise
        /** ADD (add the recommended product), UPGRADE (replace the source line, keep quantity), ADD_BOTH (add the upgrade alongside). */
        public String mode = "ADD";
        public Integer quantity;    // optional override for ADD / ADD_BOTH
    }
}
