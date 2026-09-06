package com.dealflow360.service;

import com.dealflow360.model.BillingScheduleEntry;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.model.SubscriptionPlan;
import com.dealflow360.repository.BillingScheduleEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Hybrid billing engine (PDF A5 / B7): one-time lines are billed once as
 * part of the invoice total; recurring lines get their own billing
 * schedule with mid-cycle proration and cancellation refunds/credit
 * notes, kept separate from but alongside the one-time invoice - "Shows
 * one time lines and recurring lines separately within the same order".
 */
@Service
public class BillingService {

    private static final int PREVIEW_CYCLES = 3;

    private final BillingScheduleEntryRepository billingScheduleEntryRepository;
    private final AuditService auditService;

    public BillingService(BillingScheduleEntryRepository billingScheduleEntryRepository, AuditService auditService) {
        this.billingScheduleEntryRepository = billingScheduleEntryRepository;
        this.auditService = auditService;
    }

    /**
     * PDF B7 / "Complete Flow" - "recurring subscription lines... generate a billing schedule
     * alongside any one time invoice". Called once on confirmation for every ONE_TIME line: the
     * line is invoiced in full, immediately, as a BILLED (due) entry that a payment can then be
     * recorded against. Recurring lines never come through here - they get a cycle schedule from
     * {@link #generateInitialSchedule} instead, which is exactly how the two stay "billed
     * correctly and separately" on the same order.
     */
    @Transactional
    public void generateOneTimeInvoice(QuotationLine line) {
        if (line.getLineType() != QuotationLine.LineType.ONE_TIME) return;
        if (!billingScheduleEntryRepository.findByQuotationLineIdOrderByBillingDateAsc(line.getId()).isEmpty()) {
            return; // already invoiced (confirm is idempotent for billing)
        }
        BillingScheduleEntry invoice = new BillingScheduleEntry(line, LocalDate.now(), line.lineTotal(),
                BillingScheduleEntry.EntryType.ONE_TIME_INVOICE, "One-time invoice - issued on confirmation");
        invoice.setStatus(BillingScheduleEntry.Status.BILLED);
        billingScheduleEntryRepository.save(invoice);
    }

    /**
     * Quick-test step 8 - "record a payment, and check that the invoice status updates
     * correctly". Marks one BILLED entry (or, with payAll, every open BILLED entry on the
     * quotation) as PAID with a timestamp and optional reference; PENDING future cycles are not
     * payable yet and CANCELLED/CREDITED entries never are.
     */
    @Transactional
    public List<BillingScheduleEntry> recordPayment(Long quotationId, Long entryId, boolean payAll, String reference, String actorUsername) {
        List<BillingScheduleEntry> entries = scheduleForQuotation(quotationId);
        List<BillingScheduleEntry> paid = new java.util.ArrayList<>();
        for (BillingScheduleEntry entry : entries) {
            boolean selected = payAll || (entryId != null && entryId.equals(entry.getId()));
            if (!selected) continue;
            if (entry.getStatus() == BillingScheduleEntry.Status.PAID) {
                if (!payAll) throw new IllegalStateException("This entry has already been paid");
                continue;
            }
            if (entry.getStatus() != BillingScheduleEntry.Status.BILLED) {
                if (!payAll) throw new IllegalStateException("Only a BILLED (due) entry can be paid - this one is " + entry.getStatus());
                continue;
            }
            entry.setStatus(BillingScheduleEntry.Status.PAID);
            entry.setPaidAt(LocalDateTime.now());
            entry.setPaymentReference(reference == null || reference.isBlank() ? null : reference.trim());
            billingScheduleEntryRepository.save(entry);
            paid.add(entry);
        }
        if (!payAll && paid.isEmpty()) {
            throw new IllegalArgumentException("Billing entry not found on this quotation");
        }
        if (payAll && paid.isEmpty()) {
            throw new IllegalStateException("Nothing is currently due on this order");
        }
        BigDecimal total = paid.stream().map(BillingScheduleEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        auditService.log("Quotation", quotationId, "PAYMENT_RECORDED", actorUsername,
                "Payment of " + total + " recorded against " + paid.size() + " entry(ies)"
                        + (reference == null || reference.isBlank() ? "" : " (ref " + reference.trim() + ")"));
        return paid;
    }

    /** Invoice-level status derived from the entries: nothing invoiced yet / unpaid / partially paid / paid. */
    public String invoiceStatus(List<BillingScheduleEntry> entries) {
        boolean anyDue = false, anyPaid = false;
        for (BillingScheduleEntry e : entries) {
            if (e.getStatus() == BillingScheduleEntry.Status.BILLED) anyDue = true;
            if (e.getStatus() == BillingScheduleEntry.Status.PAID) anyPaid = true;
        }
        if (!anyDue && !anyPaid) return "NOT_INVOICED";
        if (anyDue && anyPaid) return "PARTIALLY_PAID";
        return anyDue ? "UNPAID" : "PAID";
    }

    /** Called once a quotation is confirmed: bills the first cycle now and previews the next few. */
    @Transactional
    public void generateInitialSchedule(QuotationLine line) {
        if (line.getLineType() != QuotationLine.LineType.RECURRING || line.getSubscriptionPlan() == null) return;
        if (!billingScheduleEntryRepository.findByQuotationLineIdOrderByBillingDateAsc(line.getId()).isEmpty()) {
            return; // schedule already generated for this line - do not duplicate
        }

        SubscriptionPlan plan = line.getSubscriptionPlan();
        BigDecimal cycleAmount = amountForCycle(line);
        LocalDate cycleStart = LocalDate.now();

        for (int i = 0; i < PREVIEW_CYCLES; i++) {
            LocalDate billingDate = cycleStart.plusMonths((long) plan.getBillingCycle().getMonths() * i);
            BillingScheduleEntry entry = new BillingScheduleEntry(line, billingDate, cycleAmount,
                    BillingScheduleEntry.EntryType.REGULAR, i == 0 ? "First cycle - billed on confirmation" : "Upcoming cycle (preview)");
            entry.setStatus(i == 0 ? BillingScheduleEntry.Status.BILLED : BillingScheduleEntry.Status.PENDING);
            billingScheduleEntryRepository.save(entry);
        }
    }

    private BigDecimal amountForCycle(QuotationLine line) {
        BigDecimal discountFactor = BigDecimal.ONE.subtract(line.getDiscountPercent().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        return line.getSubscriptionPlan().getPricePerCycle()
                .multiply(BigDecimal.valueOf(line.getQuantity()))
                .multiply(discountFactor)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Mid-cycle quantity change (PDF A5 - "Configure proration rules for mid
     * cycle quantity or plan changes"). Prorates the delta in quantity
     * across the days remaining in the current billing cycle.
     */
    @Transactional
    public BillingScheduleEntry prorateQuantityChange(QuotationLine line, int newQuantity, String actorUsername) {
        if (newQuantity < 1) {
            throw new IllegalArgumentException("New quantity must be at least 1 - use Cancel to end the subscription");
        }
        SubscriptionPlan plan = line.getSubscriptionPlan();
        int oldQuantity = line.getQuantity();
        int deltaQuantity = newQuantity - oldQuantity;

        if (deltaQuantity == 0 || plan == null || !plan.isProrationEnabled()) {
            line.setQuantity(newQuantity);
            return null;
        }

        int cycleLengthDays = plan.getBillingCycle().getMonths() * 30;
        LocalDate cycleStart = mostRecentCycleStart(line);
        long daysElapsed = Math.min(cycleLengthDays, ChronoUnit.DAYS.between(cycleStart, LocalDate.now()));
        long daysRemaining = Math.max(0, cycleLengthDays - daysElapsed);

        BigDecimal discountFactor = BigDecimal.ONE.subtract(line.getDiscountPercent().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal proratedAmount = plan.getPricePerCycle()
                .multiply(BigDecimal.valueOf(deltaQuantity))
                .multiply(discountFactor)
                .multiply(BigDecimal.valueOf(daysRemaining))
                .divide(BigDecimal.valueOf(cycleLengthDays), 2, RoundingMode.HALF_UP);

        BillingScheduleEntry.EntryType type = proratedAmount.compareTo(BigDecimal.ZERO) >= 0
                ? BillingScheduleEntry.EntryType.PRORATION_CHARGE
                : BillingScheduleEntry.EntryType.PRORATION_CREDIT;

        BillingScheduleEntry entry = new BillingScheduleEntry(line, LocalDate.now(), proratedAmount, type,
                "Quantity change " + oldQuantity + " -> " + newQuantity + " (" + daysRemaining + "/" + cycleLengthDays + " days remaining in cycle)");
        entry.setStatus(BillingScheduleEntry.Status.BILLED);
        billingScheduleEntryRepository.save(entry);

        line.setQuantity(newQuantity);
        auditService.log("QuotationLine", line.getId(), "SUBSCRIPTION_PRORATED", actorUsername, entry.getNote());
        return entry;
    }

    /** Cancellation with automatic partial refund / credit note (PDF A5 / B7). */
    @Transactional
    public BillingScheduleEntry cancelSubscription(QuotationLine line, String reason, String actorUsername) {
        SubscriptionPlan plan = line.getSubscriptionPlan();

        // Cancel every still-pending future entry.
        List<BillingScheduleEntry> entries = billingScheduleEntryRepository.findByQuotationLineIdOrderByBillingDateAsc(line.getId());
        for (BillingScheduleEntry entry : entries) {
            if (entry.getStatus() == BillingScheduleEntry.Status.PENDING) {
                entry.setStatus(BillingScheduleEntry.Status.CANCELLED);
                billingScheduleEntryRepository.save(entry);
            }
        }

        BillingScheduleEntry refundEntry = null;
        if (plan != null && plan.isPartialRefundOnCancel()) {
            int cycleLengthDays = plan.getBillingCycle().getMonths() * 30;
            LocalDate cycleStart = mostRecentCycleStart(line);
            long daysElapsed = Math.min(cycleLengthDays, ChronoUnit.DAYS.between(cycleStart, LocalDate.now()));
            long daysRemaining = Math.max(0, cycleLengthDays - daysElapsed);

            BigDecimal refundAmount = amountForCycle(line)
                    .multiply(BigDecimal.valueOf(daysRemaining))
                    .divide(BigDecimal.valueOf(cycleLengthDays), 2, RoundingMode.HALF_UP)
                    .negate();

            refundEntry = new BillingScheduleEntry(line, LocalDate.now(), refundAmount,
                    BillingScheduleEntry.EntryType.CANCELLATION_REFUND, "Cancellation credit note: " + reason);
            refundEntry.setStatus(BillingScheduleEntry.Status.CREDITED);
            billingScheduleEntryRepository.save(refundEntry);
        }

        auditService.log("QuotationLine", line.getId(), "SUBSCRIPTION_CANCELLED", actorUsername, reason);
        return refundEntry;
    }

    private LocalDate mostRecentCycleStart(QuotationLine line) {
        return billingScheduleEntryRepository.findByQuotationLineIdOrderByBillingDateAsc(line.getId()).stream()
                .filter(e -> e.getStatus() == BillingScheduleEntry.Status.BILLED || e.getStatus() == BillingScheduleEntry.Status.PAID)
                .filter(e -> e.getEntryType() == BillingScheduleEntry.EntryType.REGULAR)
                .map(BillingScheduleEntry::getBillingDate)
                .reduce((first, second) -> second) // last billed entry
                .orElse(LocalDate.now());
    }

    public List<BillingScheduleEntry> scheduleForQuotation(Long quotationId) {
        return billingScheduleEntryRepository.findByQuotationLine_Quotation_IdOrderByBillingDateAsc(quotationId);
    }
}
