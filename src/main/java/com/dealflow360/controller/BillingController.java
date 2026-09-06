package com.dealflow360.controller;

import com.dealflow360.dto.QuotationDtos.*;
import com.dealflow360.model.BillingScheduleEntry;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.repository.QuotationLineRepository;
import com.dealflow360.service.BillingService;
import com.dealflow360.service.QuotationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/** PDF A5 / B7 - Subscription and Billing Screen: schedule, mid-cycle proration, cancellation with refund/credit note. */
@RestController
@RequestMapping("/api/quotations/{id}/billing")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class BillingController {

    private final QuotationService quotationService;
    private final BillingService billingService;
    private final QuotationLineRepository quotationLineRepository;

    public BillingController(QuotationService quotationService, BillingService billingService, QuotationLineRepository quotationLineRepository) {
        this.quotationService = quotationService;
        this.billingService = billingService;
        this.quotationLineRepository = quotationLineRepository;
    }

    @GetMapping("/schedule")
    public List<BillingEntryResponse> schedule(@PathVariable Long id) {
        return billingService.scheduleForQuotation(id).stream().map(this::toDto).collect(Collectors.toList());
    }

    /**
     * Invoice-level view for the Billing screen: one-time invoice vs. recurring schedule, what is
     * paid and what is still outstanding, plus the derived invoice status the quick-test flow
     * (step 8) checks after a payment is recorded.
     */
    @GetMapping("/summary")
    public BillingSummaryResponse summary(@PathVariable Long id) {
        List<BillingScheduleEntry> entries = billingService.scheduleForQuotation(id);
        BillingSummaryResponse dto = new BillingSummaryResponse();
        dto.oneTimeInvoiceTotal = BigDecimal.ZERO;
        dto.recurringBilledToDate = BigDecimal.ZERO;
        dto.creditsAndRefunds = BigDecimal.ZERO;
        dto.paidTotal = BigDecimal.ZERO;
        dto.outstandingTotal = BigDecimal.ZERO;
        for (BillingScheduleEntry e : entries) {
            boolean settledOrDue = e.getStatus() == BillingScheduleEntry.Status.BILLED || e.getStatus() == BillingScheduleEntry.Status.PAID;
            if (e.getEntryType() == BillingScheduleEntry.EntryType.ONE_TIME_INVOICE && settledOrDue) {
                dto.oneTimeInvoiceTotal = dto.oneTimeInvoiceTotal.add(e.getAmount());
            } else if (e.getEntryType() != BillingScheduleEntry.EntryType.ONE_TIME_INVOICE && settledOrDue) {
                dto.recurringBilledToDate = dto.recurringBilledToDate.add(e.getAmount());
            }
            if (e.getStatus() == BillingScheduleEntry.Status.CREDITED) dto.creditsAndRefunds = dto.creditsAndRefunds.add(e.getAmount());
            if (e.getStatus() == BillingScheduleEntry.Status.PAID) dto.paidTotal = dto.paidTotal.add(e.getAmount());
            if (e.getStatus() == BillingScheduleEntry.Status.BILLED) dto.outstandingTotal = dto.outstandingTotal.add(e.getAmount());
        }
        dto.invoiceStatus = billingService.invoiceStatus(entries);
        dto.entries = entries.stream().map(this::toDto).collect(Collectors.toList());
        return dto;
    }

    /** Quick-test step 8 - "record a payment, and check that the invoice status updates correctly". */
    @PostMapping("/pay")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public BillingSummaryResponse pay(@PathVariable Long id, @RequestBody RecordPaymentRequest request, Authentication auth) {
        requireConfirmed(id);
        if (!request.payAll && request.entryId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an entry to pay, or pay everything that is due");
        }
        billingService.recordPayment(id, request.entryId, request.payAll, request.reference, auth.getName());
        return summary(id);
    }

    @PostMapping("/modify")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','SALES_REP')")
    public List<BillingEntryResponse> modify(@PathVariable Long id, @RequestBody SubscriptionModifyRequest request, Authentication auth) {
        requireConfirmed(id);
        QuotationLine line = requireLine(id, request.quotationLineId);
        if (line.getLineType() != QuotationLine.LineType.RECURRING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a recurring (subscription) line can be prorated");
        }
        billingService.prorateQuantityChange(line, request.newQuantity, auth.getName());
        quotationLineRepository.save(line);
        return schedule(id);
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public List<BillingEntryResponse> cancel(@PathVariable Long id, @RequestBody SubscriptionCancelRequest request, Authentication auth) {
        requireConfirmed(id);
        QuotationLine line = requireLine(id, request.quotationLineId);
        if (line.getLineType() != QuotationLine.LineType.RECURRING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a recurring (subscription) line can be cancelled here");
        }
        billingService.cancelSubscription(line, request.reason, auth.getName());
        return schedule(id);
    }

    /**
     * Bug fix: proration / cancellation / payment only make sense once an order is CONFIRMED (that
     * is when the schedule and invoice are generated). Before that these endpoints silently edited a
     * draft's line quantity through a side door that skipped every quotation-editing rule.
     */
    private void requireConfirmed(Long quotationId) {
        Quotation quotation = quotationService.getEntity(quotationId);
        if (quotation.getStatus() != Quotation.Status.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Billing actions are available once the order is confirmed (current status: " + quotation.getStatus() + ")");
        }
    }

    private QuotationLine requireLine(Long quotationId, Long lineId) {
        Quotation quotation = quotationService.getEntity(quotationId);
        return quotation.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found on this quotation"));
    }

    private BillingEntryResponse toDto(BillingScheduleEntry e) {
        BillingEntryResponse dto = new BillingEntryResponse();
        dto.id = e.getId();
        dto.quotationLineId = e.getQuotationLine().getId();
        dto.productName = e.getQuotationLine().getProduct().getName();
        dto.lineType = e.getQuotationLine().getLineType().name();
        dto.billingDate = e.getBillingDate();
        dto.amount = e.getAmount();
        dto.status = e.getStatus().name();
        dto.entryType = e.getEntryType().name();
        dto.note = e.getNote();
        dto.paidAt = e.getPaidAt();
        dto.paymentReference = e.getPaymentReference();
        return dto;
    }
}
