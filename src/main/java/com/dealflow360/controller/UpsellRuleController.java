package com.dealflow360.controller;

import com.dealflow360.dto.RecommendationDtos.RecommendationRuleRequest;
import com.dealflow360.dto.RecommendationDtos.RecommendationRuleResponse;
import com.dealflow360.model.UpsellRule;
import com.dealflow360.service.UpsellService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF A6 - Upsell / Cross Sell Rule Setup, now the configuration surface of the Live Product
 * Recommendation Engine. Every rule carries a type (CROSS_SELL / UPSELL / PRODUCT_UPGRADE),
 * priority, promotion tag, minimum margin threshold, active flag and a reason - see
 * {@link UpsellRule}. Bodies are DTOs (never the entity), and all validation lives in
 * {@link UpsellService}; the controller only routes.
 */
@RestController
@RequestMapping("/api/upsell-rules")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class UpsellRuleController {

    private final UpsellService upsellService;

    public UpsellRuleController(UpsellService upsellService) {
        this.upsellService = upsellService;
    }

    /** All rules (active and inactive) - the admin screen shows both; the engine only uses active ones. */
    @GetMapping
    public List<RecommendationRuleResponse> list() {
        return upsellService.listRules().stream().map(upsellService::toRuleResponse).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public RecommendationRuleResponse get(@PathVariable Long id) {
        return upsellService.toRuleResponse(upsellService.getRule(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public RecommendationRuleResponse create(@RequestBody RecommendationRuleRequest request, Authentication auth) {
        return upsellService.toRuleResponse(upsellService.createRule(request, auth.getName()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public RecommendationRuleResponse update(@PathVariable Long id, @RequestBody RecommendationRuleRequest request, Authentication auth) {
        return upsellService.toRuleResponse(upsellService.updateRule(id, request, auth.getName()));
    }

    /** Soft off-switch: the rule stays (history, easy re-enable) but the engine ignores it. */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public RecommendationRuleResponse deactivate(@PathVariable Long id, Authentication auth) {
        return upsellService.toRuleResponse(upsellService.setActive(id, false, auth.getName()));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER')")
    public RecommendationRuleResponse activate(@PathVariable Long id, Authentication auth) {
        return upsellService.toRuleResponse(upsellService.setActive(id, true, auth.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id, Authentication auth) {
        upsellService.deleteRule(id, auth.getName());
    }
}
