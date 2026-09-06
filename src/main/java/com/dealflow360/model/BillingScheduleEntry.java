package com.dealflow360.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One upcoming (or historical) billing occurrence for a recurring
 * quotation line (PDF B7 - "Displays upcoming billing schedule for
 * recurring lines", "Handles mid cycle proration when quantity changes",
 * "Cancel or modify subscription controls, with an automatic partial
 * refund or credit note trigger when applicable").
 */
@Entity
@Table(name = "billing_schedule_entry")
public class BillingScheduleEntry {

    public enum Status {
        PENDING,   // scheduled for a future date, not yet invoiced
        BILLED,    // invoiced and due - waiting for payment
        PAID,      // payment recorded (quick-test step 8 - "record a payment, check the invoice status updates")
        CREDITED,  // a credit note / refund entry (negative amount)
        CANCELLED
    }

    public enum EntryType {
        ONE_TIME_INVOICE,   // the one-time (non-recurring) lines, invoiced once on confirmation
        REGULAR,            // a recurring cycle
        PRORATION_CHARGE,
        PRORATION_CREDIT,
        CANCELLATION_REFUND
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "quotation_line_id")
    private QuotationLine quotationLine;

    @Column(nullable = false)
    private LocalDate billingDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType entryType = EntryType.REGULAR;

    private String note;

    /** Nullable on purpose (existing rows) - set when the entry is marked PAID. */
    private LocalDateTime paidAt;

    private String paymentReference;

    public BillingScheduleEntry() {
    }

    public BillingScheduleEntry(QuotationLine quotationLine, LocalDate billingDate, BigDecimal amount, EntryType entryType, String note) {
        this.quotationLine = quotationLine;
        this.billingDate = billingDate;
        this.amount = amount;
        this.entryType = entryType;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public QuotationLine getQuotationLine() {
        return quotationLine;
    }

    public void setQuotationLine(QuotationLine quotationLine) {
        this.quotationLine = quotationLine;
    }

    public LocalDate getBillingDate() {
        return billingDate;
    }

    public void setBillingDate(LocalDate billingDate) {
        this.billingDate = billingDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(EntryType entryType) {
        this.entryType = entryType;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }
}
