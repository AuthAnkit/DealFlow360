package com.dealflow360.service;

import com.dealflow360.dto.DashboardDtos.Alert;
import com.dealflow360.dto.DashboardDtos.DealHealthResponse;
import com.dealflow360.model.AuditEntry;
import com.dealflow360.model.Backorder;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.StockLevel;
import com.dealflow360.repository.AuditEntryRepository;
import com.dealflow360.repository.BackorderRepository;
import com.dealflow360.repository.QuotationRepository;
import com.dealflow360.repository.StockLevelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * "We need our project to have automations as much as possible" - this is
 * the self-governing half of DealFlow360: three background jobs that run
 * on their own, with no user action, every {@code dealflow360.automation
 * .interval-ms} milliseconds:
 * <ol>
 *   <li><b>Auto-nudge stalled deals</b> - anything Deal Health already
 *       flags as stalled gets an automatic reminder logged against it
 *       (once per stalled spell, not every cycle).</li>
 *   <li><b>Auto-consolidate backorders</b> - re-checks every open backorder
 *       against current stock and fulfills whatever has since become
 *       available, instead of waiting for a human to click "Consolidate".</li>
 *   <li><b>Auto-flag low stock</b> - warehouses at or below their
 *       replenishment threshold are logged once per day so Admin/Manager
 *       can see it on the Automation activity feed without having to go
 *       looking for it.</li>
 * </ol>
 * Every action taken here is written to the same {@link AuditEntry} trail
 * as manual actions (with an {@code AUTO_} action prefix) so the whole
 * system stays explainable: nothing happens silently.
 */
@Component
public class AutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

    /** Don't re-nudge/re-flag the same thing on every tick - wait at least this long between repeats. */
    private static final long REPEAT_COOLDOWN_HOURS = 20;

    private final QuotationRepository quotationRepository;
    private final DealHealthService dealHealthService;
    private final FulfillmentService fulfillmentService;
    private final BackorderRepository backorderRepository;
    private final StockLevelRepository stockLevelRepository;
    private final AuditService auditService;
    private final AuditEntryRepository auditEntryRepository;

    public AutomationScheduler(QuotationRepository quotationRepository, DealHealthService dealHealthService,
                                FulfillmentService fulfillmentService, BackorderRepository backorderRepository,
                                StockLevelRepository stockLevelRepository, AuditService auditService,
                                AuditEntryRepository auditEntryRepository) {
        this.quotationRepository = quotationRepository;
        this.dealHealthService = dealHealthService;
        this.fulfillmentService = fulfillmentService;
        this.backorderRepository = backorderRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.auditService = auditService;
        this.auditEntryRepository = auditEntryRepository;
    }

    @Scheduled(fixedRateString = "${dealflow360.automation.interval-ms:180000}")
    public void runAutomationCycle() {
        try {
            autoNudgeStalledDeals();
        } catch (Exception e) {
            log.warn("Automation: stalled-deal nudge pass failed", e);
        }
        try {
            autoConsolidateBackorders();
        } catch (Exception e) {
            log.warn("Automation: backorder consolidation pass failed", e);
        }
        try {
            autoFlagLowStock();
        } catch (Exception e) {
            log.warn("Automation: low-stock scan failed", e);
        }
    }

    private void autoNudgeStalledDeals() {
        DealHealthResponse health = dealHealthService.compute();
        for (Alert alert : health.stalledDeals) {
            if (recentlyLogged("Quotation", alert.quotationId, "AUTO_NUDGE_SENT")) continue;
            auditService.log("Quotation", alert.quotationId, "AUTO_NUDGE_SENT", "system-automation",
                    "Automatically nudged: " + alert.message);
        }
    }

    private void autoConsolidateBackorders() {
        List<Backorder> unresolved = backorderRepository.findByResolvedFalse();
        Set<Long> quotationIds = new LinkedHashSet<>();
        for (Backorder b : unresolved) {
            quotationIds.add(b.getQuotation().getId());
        }
        for (Long quotationId : quotationIds) {
            Optional<Quotation> quotation = quotationRepository.findById(quotationId);
            quotation.ifPresent(q -> fulfillmentService.consolidateBackorders(q, "system-automation"));
        }
    }

    private void autoFlagLowStock() {
        List<StockLevel> allStock = stockLevelRepository.findAll();
        for (StockLevel stock : allStock) {
            if (stock.getQuantityAvailable() > stock.getReplenishmentThreshold()) continue;
            if (recentlyLogged("StockLevel", stock.getId(), "AUTO_LOW_STOCK_FLAGGED")) continue;
            auditService.log("StockLevel", stock.getId(), "AUTO_LOW_STOCK_FLAGGED", "system-automation",
                    stock.getProduct().getName() + " at " + stock.getWarehouse().getName()
                            + " is at " + stock.getQuantityAvailable() + " units (threshold "
                            + stock.getReplenishmentThreshold() + ") - replenishment recommended");
        }
    }

    /** True if this exact automated action already ran recently, so we don't spam the audit trail every tick. */
    private boolean recentlyLogged(String entityType, Long entityId, String action) {
        return auditEntryRepository.findTopByEntityTypeAndEntityIdAndActionOrderByTimestampDesc(entityType, entityId, action)
                .map(entry -> entry.getTimestamp().isAfter(LocalDateTime.now().minusHours(REPEAT_COOLDOWN_HOURS)))
                .orElse(false);
    }
}
