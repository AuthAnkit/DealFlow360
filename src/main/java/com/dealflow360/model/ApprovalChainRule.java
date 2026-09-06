package com.dealflow360.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Approval-chain configuration (PDF A3 - "Configure approval chain: which
 * discount range needs Sales Manager only, and which range needs Sales
 * Manager followed by Finance"). A rule applies when the quotation's
 * blended discount risk score falls within [minRiskScore, maxRiskScore).
 */
@Entity
@Table(name = "approval_chain_rule")
public class ApprovalChainRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal minRiskScore;

    /** Use a very large number for an open-ended upper bound. */
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal maxRiskScore;

    @Column(nullable = false)
    private boolean requiresManager;

    @Column(nullable = false)
    private boolean requiresFinance;

    private String label;

    public ApprovalChainRule() {
    }

    public ApprovalChainRule(BigDecimal minRiskScore, BigDecimal maxRiskScore, boolean requiresManager, boolean requiresFinance, String label) {
        this.minRiskScore = minRiskScore;
        this.maxRiskScore = maxRiskScore;
        this.requiresManager = requiresManager;
        this.requiresFinance = requiresFinance;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getMinRiskScore() {
        return minRiskScore;
    }

    public void setMinRiskScore(BigDecimal minRiskScore) {
        this.minRiskScore = minRiskScore;
    }

    public BigDecimal getMaxRiskScore() {
        return maxRiskScore;
    }

    public void setMaxRiskScore(BigDecimal maxRiskScore) {
        this.maxRiskScore = maxRiskScore;
    }

    public boolean isRequiresManager() {
        return requiresManager;
    }

    public void setRequiresManager(boolean requiresManager) {
        this.requiresManager = requiresManager;
    }

    public boolean isRequiresFinance() {
        return requiresFinance;
    }

    public void setRequiresFinance(boolean requiresFinance) {
        this.requiresFinance = requiresFinance;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
