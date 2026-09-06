package com.dealflow360.service;

import com.dealflow360.dto.IntelligenceDtos.WarehouseAllocationLine;
import com.dealflow360.dto.IntelligenceDtos.WarehouseOptionResponse;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.model.StockLevel;
import com.dealflow360.repository.StockLevelRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Smart Warehouse Optimization Modes - a read-only "what would each
 * strategy recommend?" preview, sitting alongside (not replacing) the
 * real, persisted greedy-cheapest split in {@link FulfillmentService}.
 * Nothing here reserves stock or writes a {@code FulfillmentSplit}; it
 * only reads current {@link StockLevel} data so a rep/manager can compare
 * strategies before accepting one (accepting still goes through the
 * existing {@code manualOverride} endpoint/action, so stock accounting
 * stays centralized in one place).
 * <ul>
 *   <li><b>CHEAPEST</b> - same ranking as the real auto-split: warehouses
 *       ordered by shipping-cost weight, cheapest first.</li>
 *   <li><b>FASTEST</b> - warehouses ordered by quantity currently on
 *       hand, largest first, so the biggest part of the order ships
 *       immediately from a single pick instead of waiting on multiple
 *       smaller allocations.</li>
 *   <li><b>FEWEST_SHIPMENTS</b> - prefers a single warehouse that can
 *       cover the entire line alone (cheapest such warehouse if more than
 *       one qualifies); only splits across warehouses if no single one
 *       has enough stock.</li>
 * </ul>
 */
@Service
public class FulfillmentOptimizationService {

    public enum Mode { CHEAPEST, FASTEST, FEWEST_SHIPMENTS }

    private final StockLevelRepository stockLevelRepository;

    public FulfillmentOptimizationService(StockLevelRepository stockLevelRepository) {
        this.stockLevelRepository = stockLevelRepository;
    }

    /** All three strategies computed side by side, for the comparison view. */
    public List<WarehouseOptionResponse> previewAll(Quotation quotation) {
        List<WarehouseOptionResponse> options = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            options.add(preview(quotation, mode));
        }
        return options;
    }

    public WarehouseOptionResponse preview(Quotation quotation, Mode mode) {
        List<WarehouseAllocationLine> allocations = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        int backorderUnits = 0;

        for (QuotationLine line : quotation.getLines()) {
            if (line.getLineType() != QuotationLine.LineType.ONE_TIME) continue; // subscriptions are not physically shipped

            List<StockLevel> stockByCheapest = stockLevelRepository
                    .findByProductIdOrderByWarehouse_ShippingCostWeightAsc(line.getProduct().getId());
            List<StockLevel> ordered = orderFor(mode, stockByCheapest, line.getQuantity());

            int remaining = line.getQuantity();
            for (StockLevel stock : ordered) {
                if (remaining <= 0) break;
                if (stock.getQuantityAvailable() <= 0) continue;
                int take = Math.min(remaining, stock.getQuantityAvailable());

                WarehouseAllocationLine allocation = new WarehouseAllocationLine();
                allocation.productName = line.getProduct().getName();
                allocation.warehouseName = stock.getWarehouse().getName();
                allocation.quantity = take;
                allocation.shippingCost = stock.getWarehouse().getShippingCostWeight().multiply(BigDecimal.valueOf(take));
                allocations.add(allocation);
                totalCost = totalCost.add(allocation.shippingCost);

                remaining -= take;
            }
            backorderUnits += remaining;
        }

        WarehouseOptionResponse response = new WarehouseOptionResponse();
        response.mode = mode.name();
        response.allocations = allocations;
        response.totalShippingCost = totalCost;
        response.shipmentCount = allocations.size();
        response.backorderUnits = backorderUnits;
        response.narrative = narrativeFor(mode, backorderUnits);
        return response;
    }

    private List<StockLevel> orderFor(Mode mode, List<StockLevel> stockByCheapest, int requiredQuantity) {
        List<StockLevel> ordered = new ArrayList<>(stockByCheapest);
        switch (mode) {
            case CHEAPEST -> {
                // stockByCheapest is already ordered by shipping-cost weight ascending.
            }
            case FASTEST -> ordered.sort(Comparator.comparingInt(StockLevel::getQuantityAvailable).reversed());
            case FEWEST_SHIPMENTS -> {
                Optional<StockLevel> singleWarehouseCover = stockByCheapest.stream()
                        .filter(s -> s.getQuantityAvailable() >= requiredQuantity)
                        .findFirst(); // already cheapest-first, so the first one that alone covers the order wins
                if (singleWarehouseCover.isPresent()) {
                    ordered = new ArrayList<>(List.of(singleWarehouseCover.get()));
                } else {
                    ordered.sort(Comparator.comparingInt(StockLevel::getQuantityAvailable).reversed());
                }
            }
        }
        return ordered;
    }

    private String narrativeFor(Mode mode, int backorderUnits) {
        String base = switch (mode) {
            case CHEAPEST -> "Minimizes estimated shipping cost by allocating from the lowest shipping-cost warehouse(s) first.";
            case FASTEST -> "Prioritizes the warehouse(s) with the most stock on hand right now, so the largest part of the order ships immediately.";
            case FEWEST_SHIPMENTS -> "Prefers a single warehouse that can cover the whole order alone, to minimize the number of separate shipments.";
        };
        if (backorderUnits > 0) {
            base += " " + backorderUnits + " unit(s) cannot be covered by current stock under this strategy and would go on backorder.";
        }
        return base;
    }
}
