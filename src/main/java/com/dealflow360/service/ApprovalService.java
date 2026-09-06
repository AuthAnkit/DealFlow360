package com.dealflow360.service;

import com.dealflow360.model.*;
import com.dealflow360.repository.ApprovalChainRuleRepository;
import com.dealflow360.repository.ApprovalLogRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Routes a quotation through its approval chain based on the blended
 * discount risk score (PDF A3 - "Configure approval chain: which discount
 * range needs Sales Manager only, and which range needs Sales Manager
 * followed by Finance").
 */
@Service
public class ApprovalService {

    private final ApprovalChainRuleRepository approvalChainRuleRepository;
    private final ApprovalLogRepository approvalLogRepository;
    private final DiscountRiskService discountRiskService;

    public ApprovalService(ApprovalChainRuleRepository approvalChainRuleRepository,
                            ApprovalLogRepository approvalLogRepository,
                            DiscountRiskService discountRiskService) {
        this.approvalChainRuleRepository = approvalChainRuleRepository;
        this.approvalLogRepository = approvalLogRepository;
        this.discountRiskService = discountRiskService;
    }

    /** Recomputes the risk score and routes the quotation, moving it to PENDING_APPROVAL or straight to APPROVED. */
    public void evaluateAndRoute(Quotation quotation, String actorUsername) {
        BigDecimal score = discountRiskService.blendedRiskScore(quotation);
        quotation.setBlendedRiskScore(score);

        ApprovalChainRule matched = findMatchingRule(score);

        if (matched == null || (!matched.isRequiresManager() && !matched.isRequiresFinance())) {
            // No approval required - the deal is clean.
            quotation.setStatus(Quotation.Status.APPROVED);
            quotation.setCurrentApprovalStep(Quotation.ApprovalStep.DONE);
            quotation.setApprovedRiskScore(score);
            return;
        }

        quotation.setStatus(Quotation.Status.PENDING_APPROVAL);
        quotation.setCurrentApprovalStep(matched.isRequiresManager() ? Quotation.ApprovalStep.MANAGER : Quotation.ApprovalStep.FINANCE);

        ApprovalLog submitted = new ApprovalLog(quotation, Role.SALES_REP, ApprovalLog.Action.SUBMITTED, actorUsername,
                "Auto-routed: blended risk score = " + score + " (requires manager=" + matched.isRequiresManager()
                        + ", finance=" + matched.isRequiresFinance() + ")");
        approvalLogRepository.save(submitted);
    }

    private ApprovalChainRule findMatchingRule(BigDecimal score) {
        List<ApprovalChainRule> rules = approvalChainRuleRepository.findAllByOrderByMinRiskScoreAsc();
        for (ApprovalChainRule rule : rules) {
            if (score.compareTo(rule.getMinRiskScore()) >= 0 && score.compareTo(rule.getMaxRiskScore()) < 0) {
                return rule;
            }
        }
        return null;
    }

    /** Whether a rule at the quotation's current risk score also requires Finance after Manager approval. */
    public boolean requiresFinanceStep(BigDecimal score) {
        ApprovalChainRule matched = findMatchingRule(score);
        return matched != null && matched.isRequiresFinance();
    }

    /**
     * Pure, side-effect-free lookup of "what approval would this risk score require?" - the same
     * rule table {@link #evaluateAndRoute} uses, exposed as a plain read so the Deal Copilot, the
     * What-If Simulator, and the Negotiation Assistant can all show "what would happen" without
     * duplicating the approval-chain matching logic or mutating a real quotation.
     */
    public ApprovalRequirement describeRequirement(BigDecimal score) {
        ApprovalChainRule matched = findMatchingRule(score);
        ApprovalRequirement requirement = new ApprovalRequirement();
        requirement.requiresManager = matched != null && matched.isRequiresManager();
        requirement.requiresFinance = matched != null && matched.isRequiresFinance();
        if (!requirement.requiresManager && !requirement.requiresFinance) {
            requirement.label = "No approval required";
        } else if (requirement.requiresManager && requirement.requiresFinance) {
            requirement.label = "Manager + Finance approval required";
        } else if (requirement.requiresManager) {
            requirement.label = "Manager approval required";
        } else {
            requirement.label = "Finance approval required";
        }
        return requirement;
    }

    /** Plain result of {@link #describeRequirement} - not a JPA entity, just a computed value object. */
    public static class ApprovalRequirement {
        public boolean requiresManager;
        public boolean requiresFinance;
        public String label;
    }

    public void approve(Quotation quotation, Role approverRole, String actorUsername, String reason) {
        approvalLogRepository.save(new ApprovalLog(quotation, approverRole, ApprovalLog.Action.APPROVE, actorUsername, reason));

        if (quotation.getCurrentApprovalStep() == Quotation.ApprovalStep.MANAGER && requiresFinanceStep(quotation.getBlendedRiskScore())) {
            quotation.setCurrentApprovalStep(Quotation.ApprovalStep.FINANCE);
            // stays PENDING_APPROVAL, now waiting on Finance
        } else {
            quotation.setCurrentApprovalStep(Quotation.ApprovalStep.DONE);
            quotation.setStatus(Quotation.Status.APPROVED);
            quotation.setApprovedRiskScore(quotation.getBlendedRiskScore());
        }
        quotation.setUpdatedAt(LocalDateTime.now());
    }

    public void reject(Quotation quotation, Role approverRole, String actorUsername, String reason) {
        approvalLogRepository.save(new ApprovalLog(quotation, approverRole, ApprovalLog.Action.REJECT, actorUsername, reason));
        quotation.setStatus(Quotation.Status.REJECTED);
        quotation.setUpdatedAt(LocalDateTime.now());
    }

    public void returnForRevision(Quotation quotation, Role approverRole, String actorUsername, String reason) {
        approvalLogRepository.save(new ApprovalLog(quotation, approverRole, ApprovalLog.Action.RETURN_FOR_REVISION, actorUsername, reason));
        quotation.setStatus(Quotation.Status.DRAFT);
        quotation.setCurrentApprovalStep(Quotation.ApprovalStep.NONE);
        quotation.setUpdatedAt(LocalDateTime.now());
    }

    public List<ApprovalLog> history(Long quotationId) {
        return approvalLogRepository.findByQuotationIdOrderByTimestampAsc(quotationId);
    }
}
