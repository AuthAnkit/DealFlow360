package com.dealflow360.controller;

import com.dealflow360.dto.IntelligenceDtos.WarehouseOptionResponse;
import com.dealflow360.dto.QuotationDtos.*;
import com.dealflow360.model.Backorder;
import com.dealflow360.model.FulfillmentSplit;
import com.dealflow360.model.Product;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.Warehouse;
import com.dealflow360.repository.ProductRepository;
import com.dealflow360.repository.WarehouseRepository;
import com.dealflow360.service.FulfillmentOptimizationService;
import com.dealflow360.service.FulfillmentService;
import com.dealflow360.service.QuotationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF A4 / B6 - Fulfillment and Warehouse Split Screen. Viewing the plan
 * is open to the whole internal team; accepting overrides or
 * consolidating backorders is a Finance/Operations (or Admin) decision
 * per the PDF's role breakdown ("Finance / Operations... Manages
 * warehouse fulfillment splits and backorder decisions").
 */
@RestController
@RequestMapping("/api/quotations/{id}/fulfillment")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class FulfillmentController {

    private final QuotationService quotationService;
    private final FulfillmentService fulfillmentService;
    private final FulfillmentOptimizationService fulfillmentOptimizationService;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;

    public FulfillmentController(QuotationService quotationService, FulfillmentService fulfillmentService,
                                  FulfillmentOptimizationService fulfillmentOptimizationService,
                                  ProductRepository productRepository, WarehouseRepository warehouseRepository) {
        this.quotationService = quotationService;
        this.fulfillmentService = fulfillmentService;
        this.fulfillmentOptimizationService = fulfillmentOptimizationService;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
    }

    /**
     * Smart Warehouse Optimization Modes - CHEAPEST / FASTEST / FEWEST_SHIPMENTS computed side by
     * side as a preview. Nothing here is persisted; accepting a recommendation still goes through
     * the existing {@code /override} action per allocation line.
     */
    @GetMapping("/optimize")
    public List<WarehouseOptionResponse> optimize(@PathVariable Long id) {
        Quotation quotation = quotationService.getEntity(id);
        return fulfillmentOptimizationService.previewAll(quotation);
    }

    @GetMapping
    public FulfillmentPlanResponse plan(@PathVariable Long id) {
        List<FulfillmentSplit> splits = fulfillmentService.getSplits(id);
        List<Backorder> backorders = fulfillmentService.getBackorders(id);

        FulfillmentPlanResponse response = new FulfillmentPlanResponse();
        response.splits = splits.stream().map(this::toSplitDto).collect(Collectors.toList());
        response.backorders = backorders.stream().map(this::toBackorderDto).collect(Collectors.toList());
        response.totalShipments = splits.size();
        response.totalShippingCost = splits.stream().map(FulfillmentSplit::getShipmentCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return response;
    }

    @PostMapping("/regenerate")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','SALES_MANAGER')")
    public FulfillmentPlanResponse regenerate(@PathVariable Long id, Authentication auth) {
        Quotation quotation = quotationService.getEntity(id);
        if (quotation.getStatus() != Quotation.Status.APPROVED && quotation.getStatus() != Quotation.Status.CONFIRMED
                && quotation.getStatus() != Quotation.Status.UNDER_NEGOTIATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A warehouse split is generated once the quotation is approved (current status: " + quotation.getStatus() + ")");
        }
        fulfillmentService.generateSuggestedSplit(quotation, auth.getName());
        quotationService.save(quotation);
        return plan(id);
    }

    @PostMapping("/override")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public FulfillmentPlanResponse override(@PathVariable Long id, @RequestBody FulfillmentOverrideRequest request, Authentication auth) {
        Quotation quotation = quotationService.getEntity(id);
        Product product = productRepository.findById(request.productId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        Warehouse warehouse = warehouseRepository.findById(request.warehouseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Warehouse not found"));
        fulfillmentService.manualOverride(quotation, product, warehouse, request.quantity, auth.getName());
        return plan(id);
    }

    /** PDF B6 - "Accept Suggested Split" button. */
    @PostMapping("/accept")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public QuotationResponse accept(@PathVariable Long id, Authentication auth) {
        Quotation quotation = quotationService.getEntity(id);
        fulfillmentService.acceptSuggestedSplit(quotation, auth.getName());
        return quotationService.toResponse(quotationService.save(quotation));
    }

    /** Marks one shipment delivered (clears the Deal Health delivery-slippage alert for it). */
    @PostMapping("/splits/{splitId}/delivered")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public FulfillmentPlanResponse delivered(@PathVariable Long id, @PathVariable Long splitId, Authentication auth) {
        Quotation quotation = quotationService.getEntity(id);
        fulfillmentService.markDelivered(quotation, splitId, auth.getName());
        return plan(id);
    }

    @PostMapping("/consolidate-backorders")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public FulfillmentPlanResponse consolidate(@PathVariable Long id, Authentication auth) {
        Quotation quotation = quotationService.getEntity(id);
        fulfillmentService.consolidateBackorders(quotation, auth.getName());
        return plan(id);
    }

    private FulfillmentLineSplit toSplitDto(FulfillmentSplit s) {
        FulfillmentLineSplit dto = new FulfillmentLineSplit();
        dto.id = s.getId();
        dto.productId = s.getProduct().getId();
        dto.productName = s.getProduct().getName();
        dto.warehouseId = s.getWarehouse().getId();
        dto.warehouseName = s.getWarehouse().getName();
        dto.quantityFulfilled = s.getQuantityFulfilled();
        dto.shipmentCost = s.getShipmentCost();
        dto.expectedDeliveryDate = s.getExpectedDeliveryDate();
        dto.manualOverride = s.isManualOverride();
        dto.delivered = s.isDelivered();
        return dto;
    }

    private BackorderResponse toBackorderDto(Backorder b) {
        BackorderResponse dto = new BackorderResponse();
        dto.productId = b.getProduct().getId();
        dto.productName = b.getProduct().getName();
        dto.quantityPending = b.getQuantityPending();
        dto.resolved = b.isResolved();
        return dto;
    }
}
