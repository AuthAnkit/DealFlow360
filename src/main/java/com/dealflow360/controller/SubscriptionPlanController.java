package com.dealflow360.controller;

import com.dealflow360.model.SubscriptionPlan;
import com.dealflow360.repository.SubscriptionPlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** PDF A5 - Subscription / Recurring Plan Setup. */
@RestController
@RequestMapping("/api/subscription-plans")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class SubscriptionPlanController {

    private final SubscriptionPlanRepository subscriptionPlanRepository;

    public SubscriptionPlanController(SubscriptionPlanRepository subscriptionPlanRepository) {
        this.subscriptionPlanRepository = subscriptionPlanRepository;
    }

    @GetMapping
    public List<SubscriptionPlan> list() {
        return subscriptionPlanRepository.findAll();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public SubscriptionPlan create(@RequestBody SubscriptionPlan plan) {
        plan.setId(null);
        return subscriptionPlanRepository.save(plan);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SubscriptionPlan update(@PathVariable Long id, @RequestBody SubscriptionPlan updated) {
        SubscriptionPlan existing = subscriptionPlanRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setName(updated.getName());
        existing.setProduct(updated.getProduct());
        existing.setBillingCycle(updated.getBillingCycle());
        existing.setPricePerCycle(updated.getPricePerCycle());
        existing.setProrationEnabled(updated.isProrationEnabled());
        existing.setPartialRefundOnCancel(updated.isPartialRefundOnCancel());
        return subscriptionPlanRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        subscriptionPlanRepository.deleteById(id);
    }
}
