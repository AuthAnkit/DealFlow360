package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A quotation / deal. This is the central aggregate of DealFlow360: it
 * carries the product lines, the computed blended discount risk score,
 * the current approval step, and moves through the full quote -> approval
 * -> fulfillment -> billing -> negotiation -> confirmed lifecycle
 * described in the PDF "5) Complete Flow (End-to-End)".
 */
@Entity
@Table(name = "quotation")
public class Quotation {

    public enum Status {
        DRAFT,
        PENDING_APPROVAL,
        APPROVED,
        REJECTED,
        UNDER_NEGOTIATION,
        CONFIRMED
    }

    public enum ApprovalStep {
        NONE,
        MANAGER,
        FINANCE,
        DONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(optional = false)
    @JoinColumn(name = "sales_rep_id")
    private AppUser salesRep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStep currentApprovalStep = ApprovalStep.NONE;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal blendedRiskScore = BigDecimal.ZERO;

    /**
     * The blended risk score that was actually cleared by Manager/Finance (or auto-cleared because it
     * never exceeded any ceiling) the last time this quotation reached APPROVED. {@code
     * blendedRiskScore} above is always the CURRENT score and can drift upward after approval (a
     * customer's counter-discount changes a line's discount without re-running the approval chain);
     * comparing the two at confirm time is what stops a discount that grew past its ceiling after
     * approval from being confirmed on the strength of an approval that covered a smaller discount.
     *
     * <p>Deliberately NOT {@code nullable = false}: Hibernate's {@code ddl-auto=update} adds this
     * column with a plain {@code ADD COLUMN ... NOT NULL} and no DEFAULT, which PostgreSQL rejects
     * outright on a table that already has rows ("column contains null values"). Making it nullable
     * lets the migration succeed against an existing populated database (legacy rows simply get
     * NULL here); {@link #getApprovedRiskScore()} below treats a null the same as {@link
     * BigDecimal#ZERO} so every comparison elsewhere keeps working for those old rows.
     */
    @Column(precision = 6, scale = 2)
    private BigDecimal approvedRiskScore = BigDecimal.ZERO;

    // EAGER (not the JPA default of LAZY) on purpose: with spring.jpa.open-in-view=false, many read
    // paths (toResponse/toSummary, ReportService, DealHealthService, TrendService, the new Deal
    // Intelligence services) load a Quotation and then read its lines from a plain non-transactional
    // service method. A LAZY collection would throw LazyInitializationException the moment the short
    // repository-level transaction that loaded the Quotation closes. Quotations have at most a
    // handful of lines, so eager loading here is a deliberate, safe tradeoff, not an oversight.
    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<QuotationLine> lines = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    private LocalDateTime confirmedAt;

    /**
     * PDF B6 - "Accept Suggested Split". Set when Finance/Ops (or Admin) accepts the warehouse plan
     * as the one the order will actually ship on; cleared whenever the plan is regenerated so an
     * acceptance never silently covers a different split than the one that was looked at. Both are
     * nullable on purpose - see the note on approvedRiskScore about adding NOT NULL columns to a
     * table that already has rows.
     */
    private LocalDateTime fulfillmentAcceptedAt;

    private String fulfillmentAcceptedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public AppUser getSalesRep() {
        return salesRep;
    }

    public void setSalesRep(AppUser salesRep) {
        this.salesRep = salesRep;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public ApprovalStep getCurrentApprovalStep() {
        return currentApprovalStep;
    }

    public void setCurrentApprovalStep(ApprovalStep currentApprovalStep) {
        this.currentApprovalStep = currentApprovalStep;
    }

    public BigDecimal getBlendedRiskScore() {
        return blendedRiskScore;
    }

    public void setBlendedRiskScore(BigDecimal blendedRiskScore) {
        this.blendedRiskScore = blendedRiskScore;
    }

    public BigDecimal getApprovedRiskScore() {
        return approvedRiskScore != null ? approvedRiskScore : BigDecimal.ZERO;
    }

    public void setApprovedRiskScore(BigDecimal approvedRiskScore) {
        this.approvedRiskScore = approvedRiskScore;
    }

    public LocalDateTime getFulfillmentAcceptedAt() {
        return fulfillmentAcceptedAt;
    }

    public void setFulfillmentAcceptedAt(LocalDateTime fulfillmentAcceptedAt) {
        this.fulfillmentAcceptedAt = fulfillmentAcceptedAt;
    }

    public String getFulfillmentAcceptedBy() {
        return fulfillmentAcceptedBy;
    }

    public void setFulfillmentAcceptedBy(String fulfillmentAcceptedBy) {
        this.fulfillmentAcceptedBy = fulfillmentAcceptedBy;
    }

    public List<QuotationLine> getLines() {
        return lines;
    }

    public void setLines(List<QuotationLine> lines) {
        this.lines = lines;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public BigDecimal totalAmount() {
        return lines.stream()
                .map(QuotationLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal averageDiscountPercent() {
        if (lines.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = lines.stream().map(QuotationLine::getDiscountPercent).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(lines.size()), 2, java.math.RoundingMode.HALF_UP);
    }
}
