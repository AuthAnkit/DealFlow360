package com.dealflow360.controller;

import com.dealflow360.model.ApprovalChainRule;
import com.dealflow360.model.DiscountCeiling;
import com.dealflow360.repository.ApprovalChainRuleRepository;
import com.dealflow360.repository.DiscountCeilingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * PDF A3 - Discount Tier & Approval Chain Setup. Both discount ceilings
 * and the approval chain can be configured by Admin or the Sales Manager
 * ("Configures discount tiers and approval chains" is explicitly listed
 * under the Sales Manager role).
 */
@RestController
@RequestMapping("/api/config")
@PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','SALES_REP','FINANCE')")
public class DiscountConfigController {

    private final DiscountCeilingRepository discountCeilingRepository;
    private final ApprovalChainRuleRepository approvalChainRuleRepository;

    public DiscountConfigController(DiscountCeilingRepository discountCeilingRepository, ApprovalChainRuleRepository approvalChainRuleRepository) {
        this.discountCeilingRepository = discountCeilingRepository;
        this.approvalChainRuleRepository = approvalChainRuleRepository;
    }

    @GetMapping("/discount-ceilings")
    public List<DiscountCeiling> listCeilings() {
        return discountCeilingRepository.findAll();
    }

    @PostMapping("/discount-ceilings")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public DiscountCeiling saveCeiling(@RequestBody DiscountCeiling ceiling) {
        ceiling.setId(null);
        return discountCeilingRepository.save(ceiling);
    }

    @PutMapping("/discount-ceilings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public DiscountCeiling updateCeiling(@PathVariable Long id, @RequestBody DiscountCeiling updated) {
        DiscountCeiling existing = discountCeilingRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setTier(updated.getTier());
        existing.setCategory(updated.getCategory());
        existing.setMaxDiscountPercent(updated.getMaxDiscountPercent());
        return discountCeilingRepository.save(existing);
    }

    @DeleteMapping("/discount-ceilings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public void deleteCeiling(@PathVariable Long id) {
        discountCeilingRepository.deleteById(id);
    }

    @GetMapping("/approval-chain")
    public List<ApprovalChainRule> listApprovalChain() {
        return approvalChainRuleRepository.findAllByOrderByMinRiskScoreAsc();
    }

    @PostMapping("/approval-chain")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public ApprovalChainRule saveApprovalRule(@RequestBody ApprovalChainRule rule) {
        rule.setId(null);
        return approvalChainRuleRepository.save(rule);
    }

    @PutMapping("/approval-chain/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public ApprovalChainRule updateApprovalRule(@PathVariable Long id, @RequestBody ApprovalChainRule updated) {
        ApprovalChainRule existing = approvalChainRuleRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setMinRiskScore(updated.getMinRiskScore());
        existing.setMaxRiskScore(updated.getMaxRiskScore());
        existing.setRequiresManager(updated.isRequiresManager());
        existing.setRequiresFinance(updated.isRequiresFinance());
        existing.setLabel(updated.getLabel());
        return approvalChainRuleRepository.save(existing);
    }

    @DeleteMapping("/approval-chain/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public void deleteApprovalRule(@PathVariable Long id) {
        approvalChainRuleRepository.deleteById(id);
    }
}
