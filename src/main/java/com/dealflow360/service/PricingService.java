package com.dealflow360.service;

import com.dealflow360.model.Customer;
import com.dealflow360.model.PriceListEntry;
import com.dealflow360.model.Product;
import com.dealflow360.model.SubscriptionPlan;
import com.dealflow360.repository.PriceListEntryRepository;
import com.dealflow360.repository.SubscriptionPlanRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The one place that answers "what does this product cost this customer?" (PDF A2 price lists) and
 * "which subscription plans can this product be sold on?" (PDF A5) - shared by the Quotation
 * Builder, the customer catalog and the recommendation engine so none of them carry their own copy.
 */
@Service
public class PricingService {

    private final PriceListEntryRepository priceListEntryRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;

    public PricingService(PriceListEntryRepository priceListEntryRepository, SubscriptionPlanRepository subscriptionPlanRepository) {
        this.priceListEntryRepository = priceListEntryRepository;
        this.subscriptionPlanRepository = subscriptionPlanRepository;
    }

    /** The customer's tier price for this product if one is configured, else the catalog price. */
    public BigDecimal priceFor(Customer customer, Product product) {
        return priceListEntryRepository.findByTierAndProductId(customer.getTier(), product.getId())
                .map(PriceListEntry::getPrice)
                .orElse(product.getPrice());
    }

    /** Every plan this product can be sold on (empty for a one-time product). */
    public List<SubscriptionPlan> plansFor(Product product) {
        return subscriptionPlanRepository.findByProductId(product.getId());
    }

    /**
     * The plan to use for a product when the caller did not choose one: the plan with the same
     * billing cycle as {@code like} (when upgrading/replacing a recurring line), else the monthly
     * plan, else the cheapest per cycle.
     */
    public Optional<SubscriptionPlan> defaultPlanFor(Product product, SubscriptionPlan like) {
        List<SubscriptionPlan> plans = plansFor(product);
        if (plans.isEmpty()) return Optional.empty();
        if (like != null) {
            Optional<SubscriptionPlan> sameCycle = plans.stream().filter(p -> p.getBillingCycle() == like.getBillingCycle()).findFirst();
            if (sameCycle.isPresent()) return sameCycle;
        }
        return plans.stream()
                .sorted(Comparator.comparing((SubscriptionPlan p) -> p.getBillingCycle().getMonths())
                        .thenComparing(SubscriptionPlan::getPricePerCycle))
                .findFirst();
    }
}
