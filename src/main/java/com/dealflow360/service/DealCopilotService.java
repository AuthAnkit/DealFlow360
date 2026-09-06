package com.dealflow360.service;

import com.dealflow360.dto.IntelligenceDtos.ApprovalExplanationResponse;
import com.dealflow360.dto.IntelligenceDtos.DealIntelligenceResponse;
import com.dealflow360.dto.IntelligenceDtos.Insight;
import com.dealflow360.dto.IntelligenceDtos.LineApprovalExplanation;
import com.dealflow360.dto.IntelligenceDtos.WarehouseOptionResponse;
import com.dealflow360.model.CustomerTier;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static com.dealflow360.dto.IntelligenceDtos.Insight.Category.*;

/**
 * Deal Copilot: real-time insights while a rep is building/reviewing a
 * quotation. Every insight is derived from the actual quotation, the
 * existing discount ceiling / approval-chain configuration, current
 * warehouse stock, and the existing upsell rules - there is no fabricated
 * or randomly generated commentary here.
 */
@Service
public class DealCopilotService {

    /** Recommended minimum deal margin - matches the target used by the Deal Health Score. */
    private static final BigDecimal MARGIN_TARGET_PERCENT = BigDecimal.valueOf(20);

    private final DiscountRiskService discountRiskService;
    private final ApprovalService approvalService;
    private final FulfillmentOptimizationService fulfillmentOptimizationService;
    private final UpsellService upsellService;

    public DealCopilotService(DiscountRiskService discountRiskService, ApprovalService approvalService,
                               FulfillmentOptimizationService fulfillmentOptimizationService, UpsellService upsellService) {
        this.discountRiskService = discountRiskService;
        this.approvalService = approvalService;
        this.fulfillmentOptimizationService = fulfillmentOptimizationService;
        this.upsellService = upsellService;
    }

    public DealIntelligenceResponse analyze(Quotation quotation) {
        List<Insight> insights = new ArrayList<>();
        CustomerTier tier = quotation.getCustomer().getTier();

        BigDecimal riskScore = discountRiskService.blendedRiskScore(quotation);
        ApprovalService.ApprovalRequirement requirement = approvalService.describeRequirement(riskScore);

        BigDecimal totalAmount = quotation.totalAmount();
        BigDecimal marginAmount = quotation.getLines().stream().map(QuotationLine::marginAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal marginPercent = totalAmount.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : marginAmount.divide(totalAmount, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

        WarehouseOptionResponse cheapestPlan = fulfillmentOptimizationService.preview(quotation, FulfillmentOptimizationService.Mode.CHEAPEST);

        // ---- Approval insight ----
        List<QuotationLine> overLines = new ArrayList<>();
        for (QuotationLine line : quotation.getLines()) {
            if (discountRiskService.lineOverage(tier, line).compareTo(BigDecimal.ZERO) > 0) overLines.add(line);
        }
        if (requirement.requiresManager || requirement.requiresFinance) {
            if (overLines.size() == 1) {
                QuotationLine line = overLines.get(0);
                BigDecimal ceiling = discountRiskService.ceilingFor(tier, line.getProduct().getCategory());
                insights.add(new Insight(WARNING, requirement.label,
                        "The " + line.getProduct().getName() + " discount is " + line.getDiscountPercent()
                                + "%, which exceeds the allowed " + line.getProduct().getCategory() + " category limit of "
                                + ceiling + "% by " + discountRiskService.lineOverage(tier, line) + " percentage point(s)."));
            } else if (overLines.size() > 1) {
                insights.add(new Insight(WARNING, requirement.label,
                        overLines.size() + " different product lines exceed their category discount ceiling, "
                                + "producing a blended risk score of " + riskScore + "."));
            } else {
                insights.add(new Insight(WARNING, requirement.label,
                        "Blended discount risk score is " + riskScore + ", which falls in a range that requires approval."));
            }
        } else {
            insights.add(new Insight(POSITIVE, "No approval required",
                    "Every line is within its category discount ceiling for this customer's tier (blended risk score 0)."));
        }

        // ---- Risk insight (multiple violations) ----
        if (overLines.size() > 1) {
            insights.add(new Insight(RISK, "Multiple discount violations",
                    "This quotation has discount ceiling violations across " + overLines.size()
                            + " different product lines, increasing the blended deal risk beyond any single line."));
        }

        // ---- Margin insight ----
        if (!quotation.getLines().isEmpty()) {
            if (marginPercent.compareTo(MARGIN_TARGET_PERCENT) < 0) {
                insights.add(new Insight(WARNING, "Margin below target",
                        "Current deal margin is " + marginPercent + "%, which is below the recommended margin threshold of "
                                + MARGIN_TARGET_PERCENT + "%."));
            } else {
                insights.add(new Insight(POSITIVE, "Healthy margin",
                        "Current deal margin is " + marginPercent + "%, at or above the recommended " + MARGIN_TARGET_PERCENT + "% threshold."));
            }
        }

        // ---- Fulfillment insight ----
        long warehousesNeeded = cheapestPlan.allocations.stream().map(a -> a.warehouseName).distinct().count();
        if (warehousesNeeded > 1) {
            insights.add(new Insight(WARNING, "Multi-warehouse fulfillment",
                    "This order requires fulfillment from " + warehousesNeeded
                            + " warehouses because no single warehouse currently has enough stock for every line."));
        } else if (warehousesNeeded == 1 && !cheapestPlan.allocations.isEmpty()) {
            insights.add(new Insight(POSITIVE, "Single-warehouse fulfillment",
                    "The full order can currently ship from a single warehouse (" + cheapestPlan.allocations.get(0).warehouseName + ")."));
        }
        if (cheapestPlan.backorderUnits > 0) {
            insights.add(new Insight(RISK, "Insufficient stock",
                    cheapestPlan.backorderUnits + " unit(s) on this order exceed current stock across every warehouse and would go on backorder."));
        }

        // ---- Recommendation: reduce the worst offending line to its ceiling ----
        if (!overLines.isEmpty()) {
            QuotationLine worst = overLines.stream()
                    .max((a, b) -> discountRiskService.lineOverage(tier, a).compareTo(discountRiskService.lineOverage(tier, b)))
                    .orElse(null);
            if (worst != null) {
                BigDecimal ceiling = discountRiskService.ceilingFor(tier, worst.getProduct().getCategory());
                insights.add(new Insight(RECOMMENDATION, "Lower the " + worst.getProduct().getName() + " discount",
                        "Reducing the " + worst.getProduct().getName() + " discount from " + worst.getDiscountPercent()
                                + "% to " + ceiling + "% (its category ceiling) would remove this line's contribution to the "
                                + "blended risk score and could avoid or reduce the approval requirement. Try this in the What-If Simulator."));
            }
        }

        // ---- Upsell insight ----
        List<UpsellService.Suggestion> suggestions = upsellService.suggestFor(quotation);
        if (!suggestions.isEmpty()) {
            UpsellService.Suggestion best = suggestions.get(0);
            BigDecimal impact = best.product.getPrice().multiply(best.marginPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            insights.add(new Insight(RECOMMENDATION, "Upsell opportunity: " + best.product.getName(),
                    "Adding " + best.product.getName() + (best.promoted ? " (currently promoted)" : "")
                            + " could increase total deal margin by about " + impact + " per unit."));
        }

        DealIntelligenceResponse response = new DealIntelligenceResponse();
        response.blendedRiskScore = riskScore;
        response.requiresManager = requirement.requiresManager;
        response.requiresFinance = requirement.requiresFinance;
        response.approvalLabel = requirement.label;
        response.marginAmount = marginAmount.setScale(2, RoundingMode.HALF_UP);
        response.marginPercent = marginPercent;
        response.estimatedShipments = cheapestPlan.shipmentCount;
        response.backorderUnits = cheapestPlan.backorderUnits;
        response.insights = insights;
        return response;
    }

    /**
     * Explainable Approval decision (Feature 3): the same allowed/applied/difference/risk-score
     * breakdown per line that the Copilot's approval insight is built from, exposed as its own
     * endpoint so "why did the system make this decision?" always has a direct, structured answer.
     */
    public ApprovalExplanationResponse explainApproval(Quotation quotation) {
        CustomerTier tier = quotation.getCustomer().getTier();
        BigDecimal riskScore = discountRiskService.blendedRiskScore(quotation);
        ApprovalService.ApprovalRequirement requirement = approvalService.describeRequirement(riskScore);

        List<LineApprovalExplanation> lines = new ArrayList<>();
        StringBuilder narrative = new StringBuilder();
        for (QuotationLine line : quotation.getLines()) {
            BigDecimal ceiling = discountRiskService.ceilingFor(tier, line.getProduct().getCategory());
            BigDecimal overage = discountRiskService.lineOverage(tier, line);

            LineApprovalExplanation lineExplanation = new LineApprovalExplanation();
            lineExplanation.productName = line.getProduct().getName();
            lineExplanation.category = line.getProduct().getCategory();
            lineExplanation.allowedDiscount = ceiling;
            lineExplanation.appliedDiscount = line.getDiscountPercent();
            lineExplanation.overage = overage;
            lines.add(lineExplanation);

            if (overage.compareTo(BigDecimal.ZERO) > 0) {
                if (narrative.length() > 0) narrative.append(" ");
                narrative.append(line.getProduct().getCategory()).append(" allows a maximum discount of ").append(ceiling)
                        .append("%, but ").append(line.getProduct().getName()).append(" was given ").append(line.getDiscountPercent())
                        .append("% (").append(overage).append(" point(s) over).");
            }
        }
        if (narrative.length() == 0) {
            narrative.append("Every line is within its category discount ceiling for this customer's tier, so no approval is required.");
        }

        ApprovalExplanationResponse response = new ApprovalExplanationResponse();
        response.blendedRiskScore = riskScore;
        response.requiresManager = requirement.requiresManager;
        response.requiresFinance = requirement.requiresFinance;
        response.approvalLabel = requirement.label;
        response.narrative = narrative.toString();
        response.lines = lines;
        return response;
    }
}
