package com.dealflow360.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A sales rep's "Dismiss" on one recommendation for one quotation. Persisted (rather than kept in
 * the browser) so the recommendation stays hidden on every refresh, every reload and for every
 * colleague opening the same deal - while the rule itself stays active for every other quotation.
 * A dismissal is scoped to the rule and, for a PRODUCT_UPGRADE (which is per line), to the source
 * line it was raised for.
 */
@Entity
@Table(name = "recommendation_dismissal")
public class RecommendationDismissal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "quotation_id")
    private Quotation quotation;

    @ManyToOne(optional = false)
    @JoinColumn(name = "rule_id")
    private UpsellRule rule;

    /** Null for cart-wide (CROSS_SELL / UPSELL) dismissals; the source line id for an upgrade. */
    private Long sourceLineId;

    private String dismissedBy;

    private LocalDateTime dismissedAt = LocalDateTime.now();

    public RecommendationDismissal() {
    }

    public RecommendationDismissal(Quotation quotation, UpsellRule rule, Long sourceLineId, String dismissedBy) {
        this.quotation = quotation;
        this.rule = rule;
        this.sourceLineId = sourceLineId;
        this.dismissedBy = dismissedBy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Quotation getQuotation() { return quotation; }
    public void setQuotation(Quotation quotation) { this.quotation = quotation; }
    public UpsellRule getRule() { return rule; }
    public void setRule(UpsellRule rule) { this.rule = rule; }
    public Long getSourceLineId() { return sourceLineId; }
    public void setSourceLineId(Long sourceLineId) { this.sourceLineId = sourceLineId; }
    public String getDismissedBy() { return dismissedBy; }
    public void setDismissedBy(String dismissedBy) { this.dismissedBy = dismissedBy; }
    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(LocalDateTime dismissedAt) { this.dismissedAt = dismissedAt; }
}
