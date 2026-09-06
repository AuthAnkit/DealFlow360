package com.dealflow360.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Shapes for the "Deal Intelligence" feature set - Deal Copilot, the
 * What-If Simulator, explainable approval/warehouse/negotiation
 * decisions, the Deal Health Score, and the Deal Timeline. Every value in
 * every response here is computed from real quotation/pricing/discount/
 * risk/fulfillment data by the existing calculators
 * ({@code DiscountRiskService}, {@code ApprovalService}, {@code
 * FulfillmentService}, {@code UpsellService}); nothing here is a
 * fabricated or random "AI" message.
 */
public class IntelligenceDtos {

    // ---------------------------------------------------------------- Deal Copilot

    /** One Copilot insight, tagged so the UI can group/color them without re-deriving the category. */
    public static class Insight {
        public enum Category { WARNING, RISK, RECOMMENDATION, POSITIVE }

        public Category category;
        public String title;
        public String message; // the "why" - always a plain-English explanation, never a bare fact

        public Insight(Category category, String title, String message) {
            this.category = category;
            this.title = title;
            this.message = message;
        }
    }

    public static class DealIntelligenceResponse {
        public BigDecimal blendedRiskScore;
        public boolean requiresManager;
        public boolean requiresFinance;
        public String approvalLabel;
        public BigDecimal marginAmount;
        public BigDecimal marginPercent;
        public int estimatedShipments;
        public int backorderUnits;
        public List<Insight> insights;
    }

    // ---------------------------------------------------------------- Explainable approval decision

    public static class LineApprovalExplanation {
        public String productName;
        public String category;
        public BigDecimal allowedDiscount;
        public BigDecimal appliedDiscount;
        public BigDecimal overage; // 0 if within the allowed ceiling
    }

    public static class ApprovalExplanationResponse {
        public BigDecimal blendedRiskScore;
        public boolean requiresManager;
        public boolean requiresFinance;
        public String approvalLabel;
        public String narrative;
        public List<LineApprovalExplanation> lines;
    }

    // ---------------------------------------------------------------- What-If Deal Simulator

    /** One temporary change to try - either a new line, a modified existing line, or a removed line. */
    public static class ScenarioLineChange {
        public Long lineId;          // set to modify/remove an existing line; null to add a new one
        public Long productId;       // required when adding a new line, or to change an existing line's product-derived price
        public Integer quantity;     // null = keep current quantity (ignored when adding, where it defaults to 1)
        public BigDecimal discountPercent; // null = keep current discount (ignored when adding, where it defaults to 0)
        public boolean remove;       // true = drop this line entirely from the scenario
    }

    public static class ScenarioRequest {
        public List<ScenarioLineChange> changes;
    }

    /** The same set of headline numbers for either the real quotation or a hypothetical scenario. */
    public static class ScenarioSnapshot {
        public BigDecimal totalAmount;
        public BigDecimal totalDiscountAmount;
        public BigDecimal marginAmount;
        public BigDecimal marginPercent;
        public BigDecimal blendedRiskScore;
        public boolean requiresManager;
        public boolean requiresFinance;
        public String approvalLabel;
        public int estimatedShipments;
    }

    public static class ScenarioResponse {
        public ScenarioSnapshot current;
        public ScenarioSnapshot scenario;
    }

    // ---------------------------------------------------------------- Deal Health Score

    public static class ScoreFactor {
        public String label;
        public int points; // negative = deduction
    }

    public static class DealHealthScoreResponse {
        public Long quotationId;
        public int score;
        public String band; // HEALTHY / ATTENTION_NEEDED / AT_RISK
        public List<ScoreFactor> factors;
        public List<String> recommendedActions;
    }

    // ---------------------------------------------------------------- Deal Timeline

    public static class TimelineEventResponse {
        public String eventName;
        public String actor;
        public LocalDateTime timestamp;
        public String detail;
        public String status; // free-form status label at the time of the event, where known
    }

    // ---------------------------------------------------------------- Smart Warehouse Optimization

    public static class WarehouseAllocationLine {
        public String productName;
        public String warehouseName;
        public int quantity;
        public BigDecimal shippingCost;
    }

    public static class WarehouseOptionResponse {
        public String mode; // CHEAPEST / FASTEST / FEWEST_SHIPMENTS
        public List<WarehouseAllocationLine> allocations;
        public BigDecimal totalShippingCost;
        public int shipmentCount;
        public int backorderUnits;
        public String narrative; // "why this recommendation?"
    }

    // ---------------------------------------------------------------- Smart Negotiation Assistant

    public static class NegotiationAlternativesRequest {
        public Long quotationLineId;
        public BigDecimal requestedDiscountPercent;
    }

    public static class NegotiationOptionResponse {
        public String label;
        public BigDecimal discountPercent;
        public Long addOnProductId;      // set only for the "discount + add-on" option
        public String addOnProductName;
        public BigDecimal marginAmount;
        public BigDecimal marginPercent;
        public BigDecimal riskScore;
        public boolean requiresManager;
        public boolean requiresFinance;
        public String approvalLabel;
        public String narrative;
    }

    public static class NegotiationAlternativesResponse {
        public String customerRequestSummary;
        public List<NegotiationOptionResponse> options;
    }
}
