package com.dealflow360.service;

import com.dealflow360.dto.IntelligenceDtos.ScenarioLineChange;
import com.dealflow360.dto.IntelligenceDtos.ScenarioRequest;
import com.dealflow360.dto.IntelligenceDtos.ScenarioResponse;
import com.dealflow360.dto.IntelligenceDtos.ScenarioSnapshot;
import com.dealflow360.dto.IntelligenceDtos.WarehouseOptionResponse;
import com.dealflow360.dto.QuotationDtos.AddLineRequest;
import com.dealflow360.dto.QuotationDtos.UpdateLineRequest;
import com.dealflow360.model.Product;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What-If Deal Simulator: lets a rep try alternative discounts/quantities/
 * products WITHOUT touching the real quotation. {@link #simulate} builds
 * a plain, detached, in-memory copy of the quotation, applies the
 * requested changes only to that copy, and runs the exact same
 * calculators the real quotation uses ({@link DiscountRiskService},
 * {@link ApprovalService}, {@link FulfillmentOptimizationService}) - so a
 * scenario's numbers are only ever a preview of the same business logic,
 * never a separate/duplicated calculation. Nothing is saved until
 * {@link #apply} is called, and apply reuses the real
 * {@link QuotationService} write methods rather than persisting the
 * detached clone directly.
 */
@Service
public class ScenarioService {

    private final QuotationService quotationService;
    private final ProductRepository productRepository;
    private final DiscountRiskService discountRiskService;
    private final ApprovalService approvalService;
    private final FulfillmentOptimizationService fulfillmentOptimizationService;
    private final AuditService auditService;

    public ScenarioService(QuotationService quotationService, ProductRepository productRepository,
                            DiscountRiskService discountRiskService, ApprovalService approvalService,
                            FulfillmentOptimizationService fulfillmentOptimizationService, AuditService auditService) {
        this.quotationService = quotationService;
        this.productRepository = productRepository;
        this.discountRiskService = discountRiskService;
        this.approvalService = approvalService;
        this.fulfillmentOptimizationService = fulfillmentOptimizationService;
        this.auditService = auditService;
    }

    /** Compares the real quotation's current numbers against a hypothetical scenario. Nothing is persisted. */
    public ScenarioResponse simulate(Long quotationId, ScenarioRequest request) {
        Quotation real = quotationService.getEntity(quotationId);
        ScenarioSnapshot current = snapshot(real);

        Quotation scenarioClone = cloneForScenario(real);
        applyChangesInMemory(scenarioClone, request);
        ScenarioSnapshot scenario = snapshot(scenarioClone);

        ScenarioResponse response = new ScenarioResponse();
        response.current = current;
        response.scenario = scenario;
        return response;
    }

    /**
     * Commits a chosen scenario to the real quotation, reusing the same add/update/remove-line
     * methods a manual edit in the Cart tab would use - no separate "apply scenario" persistence path.
     */
    public Quotation apply(Long quotationId, ScenarioRequest request, String actorUsername) {
        if (request.changes == null || request.changes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No scenario changes to apply");
        }
        for (ScenarioLineChange change : request.changes) {
            if (change.remove && change.lineId != null) {
                quotationService.removeLine(quotationId, change.lineId);
            } else if (change.lineId != null) {
                UpdateLineRequest update = new UpdateLineRequest();
                update.quantity = change.quantity;
                update.discountPercent = change.discountPercent;
                quotationService.updateLine(quotationId, change.lineId, update);
            } else if (change.productId != null) {
                AddLineRequest add = new AddLineRequest();
                add.productId = change.productId;
                add.quantity = change.quantity != null ? Math.max(1, change.quantity) : 1;
                add.discountPercent = change.discountPercent != null ? change.discountPercent : BigDecimal.ZERO;
                add.lineType = QuotationLine.LineType.ONE_TIME;
                quotationService.addLine(quotationId, add);
            }
        }
        auditService.log("Quotation", quotationId, "SCENARIO_APPLIED", actorUsername,
                "Applied a What-If scenario with " + request.changes.size() + " change(s)");
        return quotationService.getEntity(quotationId);
    }

    /** A plain, detached, never-persisted copy - mutating it cannot affect the real quotation. */
    private Quotation cloneForScenario(Quotation source) {
        Quotation clone = new Quotation();
        clone.setId(source.getId());
        clone.setCustomer(source.getCustomer());
        clone.setSalesRep(source.getSalesRep());
        clone.setStatus(source.getStatus());
        clone.setBlendedRiskScore(source.getBlendedRiskScore());
        clone.setCreatedAt(source.getCreatedAt());
        clone.setUpdatedAt(source.getUpdatedAt());

        List<QuotationLine> clonedLines = new ArrayList<>();
        for (QuotationLine line : source.getLines()) {
            QuotationLine copy = new QuotationLine();
            copy.setId(line.getId());
            copy.setQuotation(clone);
            copy.setProduct(line.getProduct());
            copy.setQuantity(line.getQuantity());
            copy.setUnitPrice(line.getUnitPrice());
            copy.setDiscountPercent(line.getDiscountPercent());
            copy.setLineType(line.getLineType());
            copy.setSubscriptionPlan(line.getSubscriptionPlan());
            clonedLines.add(copy);
        }
        clone.setLines(clonedLines);
        return clone;
    }

    private void applyChangesInMemory(Quotation clone, ScenarioRequest request) {
        if (request.changes == null) return;
        for (ScenarioLineChange change : request.changes) {
            if (change.lineId != null) {
                Optional<QuotationLine> existing = clone.getLines().stream()
                        .filter(l -> l.getId() != null && l.getId().equals(change.lineId))
                        .findFirst();
                if (existing.isEmpty()) continue;
                if (change.remove) {
                    clone.getLines().remove(existing.get());
                } else {
                    QuotationLine line = existing.get();
                    if (change.quantity != null) line.setQuantity(Math.max(1, change.quantity));
                    if (change.discountPercent != null) line.setDiscountPercent(change.discountPercent);
                }
            } else if (change.productId != null && !change.remove) {
                Product product = productRepository.findById(change.productId).orElse(null);
                if (product == null) continue;
                QuotationLine newLine = new QuotationLine();
                newLine.setQuotation(clone);
                newLine.setProduct(product);
                newLine.setQuantity(change.quantity != null ? Math.max(1, change.quantity) : 1);
                newLine.setUnitPrice(product.getPrice());
                newLine.setDiscountPercent(change.discountPercent != null ? change.discountPercent : BigDecimal.ZERO);
                newLine.setLineType(QuotationLine.LineType.ONE_TIME);
                clone.getLines().add(newLine);
            }
        }
    }

    private ScenarioSnapshot snapshot(Quotation quotation) {
        BigDecimal totalAmount = quotation.totalAmount();
        BigDecimal grossBeforeDiscount = quotation.getLines().stream()
                .map(l -> l.getUnitPrice().multiply(BigDecimal.valueOf(l.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal marginAmount = quotation.getLines().stream().map(QuotationLine::marginAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal marginPercent = totalAmount.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : marginAmount.divide(totalAmount, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal riskScore = discountRiskService.blendedRiskScore(quotation);
        ApprovalService.ApprovalRequirement requirement = approvalService.describeRequirement(riskScore);
        WarehouseOptionResponse cheapestPlan = fulfillmentOptimizationService.preview(quotation, FulfillmentOptimizationService.Mode.CHEAPEST);

        ScenarioSnapshot snapshot = new ScenarioSnapshot();
        snapshot.totalAmount = totalAmount.setScale(2, RoundingMode.HALF_UP);
        snapshot.totalDiscountAmount = grossBeforeDiscount.subtract(totalAmount).setScale(2, RoundingMode.HALF_UP);
        snapshot.marginAmount = marginAmount.setScale(2, RoundingMode.HALF_UP);
        snapshot.marginPercent = marginPercent;
        snapshot.blendedRiskScore = riskScore;
        snapshot.requiresManager = requirement.requiresManager;
        snapshot.requiresFinance = requirement.requiresFinance;
        snapshot.approvalLabel = requirement.label;
        snapshot.estimatedShipments = cheapestPlan.shipmentCount;
        return snapshot;
    }
}
