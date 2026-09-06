package com.dealflow360.service;

import com.dealflow360.model.*;
import com.dealflow360.repository.BackorderRepository;
import com.dealflow360.repository.FulfillmentSplitRepository;
import com.dealflow360.repository.StockLevelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Warehouse fulfillment split (PDF A4 / B6): for every one-time line on
 * the quotation, allocate the required quantity from warehouses ranked by
 * shipping-cost weight (cheapest first), splitting across warehouses only
 * when a single warehouse cannot cover the full quantity - this is what
 * "minimize number of shipments" means in practice: prefer the fewest
 * warehouses that can satisfy the order. Any quantity that cannot be
 * covered by any warehouse's current stock becomes a Backorder.
 * <p>
 * Every {@link FulfillmentSplit} that is created here reserves real stock
 * (the warehouse's {@code quantityAvailable} is decremented), and every
 * split that is deleted (e.g. before recomputing a suggestion) releases
 * that stock back - otherwise regenerating a suggestion or reopening the
 * Fulfillment tab more than once would silently double-allocate the same
 * physical stock across quotations.
 */
@Service
public class FulfillmentService {

    private final StockLevelRepository stockLevelRepository;
    private final FulfillmentSplitRepository fulfillmentSplitRepository;
    private final BackorderRepository backorderRepository;
    private final AuditService auditService;

    public FulfillmentService(StockLevelRepository stockLevelRepository,
                               FulfillmentSplitRepository fulfillmentSplitRepository,
                               BackorderRepository backorderRepository,
                               AuditService auditService) {
        this.stockLevelRepository = stockLevelRepository;
        this.fulfillmentSplitRepository = fulfillmentSplitRepository;
        this.backorderRepository = backorderRepository;
        this.auditService = auditService;
    }

    /** Computes (and persists) the suggested warehouse split for every one-time line of the quotation. */
    @Transactional
    public void generateSuggestedSplit(Quotation quotation, String actorUsername) {
        // Clear any previous auto-generated (non manually-overridden) splits before recomputing,
        // releasing the stock they were reserving back to its warehouse first.
        List<FulfillmentSplit> existing = fulfillmentSplitRepository.findByQuotationId(quotation.getId());
        for (FulfillmentSplit split : existing) {
            if (!split.isManualOverride()) {
                releaseStock(split.getWarehouse(), split.getProduct(), split.getQuantityFulfilled());
                fulfillmentSplitRepository.delete(split);
            }
        }
        // Bug fix: the old auto-generated splits were cleared above, but the backorders those same
        // runs created were left behind - so every regenerate (submit -> approve -> confirm each
        // trigger one) stacked another duplicate "N units pending" backorder for the same shortfall
        // on top of the last. Drop the unresolved backorders for every product that is about to be
        // recomputed (manually-overridden products keep theirs - they are skipped below too).
        for (Backorder backorder : backorderRepository.findByQuotationIdAndResolvedFalse(quotation.getId())) {
            boolean overridden = existing.stream()
                    .anyMatch(s -> s.isManualOverride() && s.getProduct().getId().equals(backorder.getProduct().getId()));
            if (!overridden) backorderRepository.delete(backorder);
        }
        // A regenerated plan is a different plan - any earlier "Accept Suggested Split" no longer covers it.
        quotation.setFulfillmentAcceptedAt(null);
        quotation.setFulfillmentAcceptedBy(null);

        for (QuotationLine line : quotation.getLines()) {
            if (line.getLineType() != QuotationLine.LineType.ONE_TIME) continue; // subscriptions are not physically shipped
            boolean alreadyOverridden = existing.stream()
                    .anyMatch(s -> s.isManualOverride() && s.getProduct().getId().equals(line.getProduct().getId()));
            if (alreadyOverridden) continue;

            splitLine(quotation, line.getProduct(), line.getQuantity());
        }

        auditService.log("Quotation", quotation.getId(), "FULFILLMENT_SPLIT_GENERATED", actorUsername,
                "Auto-generated warehouse split for " + quotation.getLines().size() + " line(s)");
    }

    private void splitLine(Quotation quotation, Product product, int requiredQuantity) {
        List<StockLevel> stockByCheapestWarehouse = stockLevelRepository
                .findByProductIdOrderByWarehouse_ShippingCostWeightAsc(product.getId());

        int remaining = requiredQuantity;
        for (StockLevel stock : stockByCheapestWarehouse) {
            if (remaining <= 0) break;
            if (stock.getQuantityAvailable() <= 0) continue;

            int take = Math.min(remaining, stock.getQuantityAvailable());
            FulfillmentSplit split = new FulfillmentSplit();
            split.setQuotation(quotation);
            split.setProduct(product);
            split.setWarehouse(stock.getWarehouse());
            split.setQuantityFulfilled(take);
            split.setShipmentCost(stock.getWarehouse().getShippingCostWeight().multiply(BigDecimal.valueOf(take)));
            split.setExpectedDeliveryDate(LocalDate.now().plusDays(3));
            fulfillmentSplitRepository.save(split);

            stock.setQuantityAvailable(stock.getQuantityAvailable() - take);
            stockLevelRepository.save(stock);

            remaining -= take;
        }

        if (remaining > 0) {
            Backorder backorder = new Backorder(quotation, product, remaining);
            backorderRepository.save(backorder);
        }
    }

    /** Manual override: force a specific warehouse/quantity for a product line (PDF B6 - "Manual Override" button). */
    @Transactional
    public void manualOverride(Quotation quotation, Product product, Warehouse warehouse, int quantity, String actorUsername) {
        fulfillmentSplitRepository.findByQuotationId(quotation.getId()).stream()
                .filter(s -> s.getProduct().getId().equals(product.getId()))
                .forEach(s -> {
                    releaseStock(s.getWarehouse(), s.getProduct(), s.getQuantityFulfilled());
                    fulfillmentSplitRepository.delete(s);
                });
        backorderRepository.findByQuotationIdAndResolvedFalse(quotation.getId()).stream()
                .filter(b -> b.getProduct().getId().equals(product.getId()))
                .forEach(b -> {
                    b.setResolved(true);
                    backorderRepository.save(b);
                });

        FulfillmentSplit split = new FulfillmentSplit();
        split.setQuotation(quotation);
        split.setProduct(product);
        split.setWarehouse(warehouse);
        split.setQuantityFulfilled(quantity);
        split.setShipmentCost(warehouse.getShippingCostWeight().multiply(BigDecimal.valueOf(quantity)));
        split.setExpectedDeliveryDate(LocalDate.now().plusDays(3));
        split.setManualOverride(true);
        fulfillmentSplitRepository.save(split);
        // A manual override is a deliberate human decision that can knowingly exceed the system's
        // own suggestion, so stock is still reserved but is never pushed below zero.
        reserveStockClamped(warehouse, product, quantity);

        auditService.log("Quotation", quotation.getId(), "FULFILLMENT_MANUAL_OVERRIDE", actorUsername,
                "Manually set " + quantity + "x " + product.getName() + " from " + warehouse.getName());
    }

    /**
     * "If stock arrives mid fulfillment, a 'Consolidate Remaining Backorder' prompt appears automatically."
     * Re-checks unresolved backorders against current stock and fulfills whatever is now available.
     */
    @Transactional
    public List<Backorder> consolidateBackorders(Quotation quotation, String actorUsername) {
        List<Backorder> unresolved = backorderRepository.findByQuotationIdAndResolvedFalse(quotation.getId());
        List<Backorder> stillPending = new ArrayList<>();

        for (Backorder backorder : unresolved) {
            List<StockLevel> stockByCheapestWarehouse = stockLevelRepository
                    .findByProductIdOrderByWarehouse_ShippingCostWeightAsc(backorder.getProduct().getId());

            int remaining = backorder.getQuantityPending();
            for (StockLevel stock : stockByCheapestWarehouse) {
                if (remaining <= 0) break;
                if (stock.getQuantityAvailable() <= 0) continue;
                int take = Math.min(remaining, stock.getQuantityAvailable());

                FulfillmentSplit split = new FulfillmentSplit();
                split.setQuotation(quotation);
                split.setProduct(backorder.getProduct());
                split.setWarehouse(stock.getWarehouse());
                split.setQuantityFulfilled(take);
                split.setShipmentCost(stock.getWarehouse().getShippingCostWeight().multiply(BigDecimal.valueOf(take)));
                split.setExpectedDeliveryDate(LocalDate.now().plusDays(3));
                fulfillmentSplitRepository.save(split);

                stock.setQuantityAvailable(stock.getQuantityAvailable() - take);
                stockLevelRepository.save(stock);

                remaining -= take;
            }

            if (remaining <= 0) {
                backorder.setResolved(true);
                backorderRepository.save(backorder);
                auditService.log("Quotation", quotation.getId(), "BACKORDER_CONSOLIDATED", actorUsername,
                        "Backorder for " + backorder.getProduct().getName() + " fully resolved from newly available stock");
            } else {
                backorder.setQuantityPending(remaining);
                backorderRepository.save(backorder);
                stillPending.add(backorder);
            }
        }
        return stillPending;
    }

    /**
     * PDF B6 - "Accept Suggested Split": Finance/Ops signs off on the current warehouse plan as the
     * one the order ships on. Purely a recorded decision (plus audit trail) - the stock was already
     * reserved when the plan was generated.
     */
    @Transactional
    public void acceptSuggestedSplit(Quotation quotation, String actorUsername) {
        if (fulfillmentSplitRepository.findByQuotationId(quotation.getId()).isEmpty()) {
            throw new IllegalStateException("There is no fulfillment plan to accept yet - the quotation must be approved first");
        }
        quotation.setFulfillmentAcceptedAt(LocalDateTime.now());
        quotation.setFulfillmentAcceptedBy(actorUsername);
        auditService.log("Quotation", quotation.getId(), "FULFILLMENT_SPLIT_ACCEPTED", actorUsername,
                "Suggested warehouse split accepted as the shipping plan");
    }

    /** Marks one shipment as delivered - clears it from the Deal Health "delivery slippage" alert. */
    @Transactional
    public FulfillmentSplit markDelivered(Quotation quotation, Long splitId, String actorUsername) {
        FulfillmentSplit split = fulfillmentSplitRepository.findById(splitId)
                .filter(s -> s.getQuotation().getId().equals(quotation.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Shipment not found on this quotation"));
        if (split.isDelivered()) return split;
        split.setDelivered(true);
        fulfillmentSplitRepository.save(split);
        auditService.log("Quotation", quotation.getId(), "SHIPMENT_DELIVERED", actorUsername,
                split.getQuantityFulfilled() + "x " + split.getProduct().getName() + " delivered from " + split.getWarehouse().getName());
        return split;
    }

    /**
     * Releases every reservation this quotation holds (splits and open backorders) - used when a deal
     * is rejected or reopened for revision, so stock reserved for a deal that is no longer going
     * ahead as approved is not locked away from other orders.
     */
    @Transactional
    public void releaseAll(Quotation quotation, String actorUsername) {
        List<FulfillmentSplit> splits = fulfillmentSplitRepository.findByQuotationId(quotation.getId());
        if (splits.isEmpty() && backorderRepository.findByQuotationIdAndResolvedFalse(quotation.getId()).isEmpty()) return;
        for (FulfillmentSplit split : splits) {
            releaseStock(split.getWarehouse(), split.getProduct(), split.getQuantityFulfilled());
            fulfillmentSplitRepository.delete(split);
        }
        for (Backorder backorder : backorderRepository.findByQuotationIdAndResolvedFalse(quotation.getId())) {
            backorderRepository.delete(backorder);
        }
        quotation.setFulfillmentAcceptedAt(null);
        quotation.setFulfillmentAcceptedBy(null);
        auditService.log("Quotation", quotation.getId(), "FULFILLMENT_RELEASED", actorUsername,
                "Reserved stock released - the deal is being revised");
    }

    /** Releases previously-reserved stock back to a warehouse (used when a split is deleted/recomputed). */
    private void releaseStock(Warehouse warehouse, Product product, int quantity) {
        stockLevelRepository.findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                .ifPresent(stock -> {
                    stock.setQuantityAvailable(stock.getQuantityAvailable() + quantity);
                    stockLevelRepository.save(stock);
                });
    }

    /** Reserves stock for a manual override, clamped so it never goes negative even if over-committed. */
    private void reserveStockClamped(Warehouse warehouse, Product product, int quantity) {
        stockLevelRepository.findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                .ifPresent(stock -> {
                    stock.setQuantityAvailable(Math.max(0, stock.getQuantityAvailable() - quantity));
                    stockLevelRepository.save(stock);
                });
    }

    public List<FulfillmentSplit> getSplits(Long quotationId) {
        return fulfillmentSplitRepository.findByQuotationId(quotationId);
    }

    public List<Backorder> getBackorders(Long quotationId) {
        return backorderRepository.findByQuotationIdAndResolvedFalse(quotationId);
    }
}
