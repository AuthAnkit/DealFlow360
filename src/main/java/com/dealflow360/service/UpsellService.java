package com.dealflow360.service;

import com.dealflow360.dto.RecommendationDtos.RecommendationPanelResponse;
import com.dealflow360.dto.RecommendationDtos.RecommendationResponse;
import com.dealflow360.dto.RecommendationDtos.RecommendationRuleRequest;
import com.dealflow360.dto.RecommendationDtos.RecommendationRuleResponse;
import com.dealflow360.model.*;
import com.dealflow360.model.UpsellRule.RecommendationType;
import com.dealflow360.repository.ProductRepository;
import com.dealflow360.repository.RecommendationDismissalRepository;
import com.dealflow360.repository.UpsellRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Live Product Recommendation Engine (PDF B5 / A6). Reads the configured {@link UpsellRule}s for
 * every product on a quotation and turns them into ranked, explainable recommendations of three
 * kinds:
 * <ul>
 *   <li><b>CROSS_SELL</b> - a complementary product to ADD next to what is there (Laptop -> Mouse).</li>
 *   <li><b>UPSELL</b> - a higher-value / premium offering to ADD (Cloud Suite -> Analytics Add-on);
 *       an in-place upgrade is offered as an alternative action when it makes sense.</li>
 *   <li><b>PRODUCT_UPGRADE</b> - a premium version of the same thing that REPLACES the source line
 *       while keeping its quantity (10 x Laptop Basic -> 10 x Laptop Pro).</li>
 * </ul>
 * Nothing here mutates a quotation - accepting is done by {@code QuotationService} through the same
 * add/replace-line paths a manual edit uses. Nothing is hardcoded either: every figure on a card is
 * computed from the real product costs, the customer's tier price, the source line's quantity and
 * discount, and the discount ceilings.
 * <p>
 * The original {@link #suggestFor} (promoted-first, then margin) is kept for the pre-existing
 * {@code /upsell-suggestions} endpoint.
 */
@Service
public class UpsellService {

    private final UpsellRuleRepository upsellRuleRepository;
    private final ProductRepository productRepository;
    private final RecommendationDismissalRepository dismissalRepository;
    private final PricingService pricingService;
    private final DiscountRiskService discountRiskService;
    private final AuditService auditService;

    public UpsellService(UpsellRuleRepository upsellRuleRepository, ProductRepository productRepository,
                         RecommendationDismissalRepository dismissalRepository, PricingService pricingService,
                         DiscountRiskService discountRiskService, AuditService auditService) {
        this.upsellRuleRepository = upsellRuleRepository;
        this.productRepository = productRepository;
        this.dismissalRepository = dismissalRepository;
        this.pricingService = pricingService;
        this.discountRiskService = discountRiskService;
        this.auditService = auditService;
    }

    // =================================================================== legacy suggestions (kept)

    public static class Suggestion {
        public final Product product;
        public final BigDecimal marginPercent;
        public final boolean promoted;

        public Suggestion(Product product, BigDecimal marginPercent, boolean promoted) {
            this.product = product;
            this.marginPercent = marginPercent;
            this.promoted = promoted;
        }
    }

    /** Original simple list (add-type rules only) used by the pre-existing /upsell-suggestions endpoint. */
    public List<Suggestion> suggestFor(Quotation quotation) {
        Set<Long> productsInCart = quotation.getLines().stream().map(l -> l.getProduct().getId()).collect(Collectors.toSet());
        Map<Long, Suggestion> suggestions = new LinkedHashMap<>();

        for (QuotationLine line : quotation.getLines()) {
            for (UpsellRule rule : upsellRuleRepository.findByBaseProductId(line.getProduct().getId())) {
                if (!rule.isActive() || rule.getRecommendationType() == RecommendationType.PRODUCT_UPGRADE) continue;
                Product suggested = rule.getSuggestedProduct();
                if (!suggested.isActive() || productsInCart.contains(suggested.getId())) continue;
                BigDecimal margin = suggested.marginPercent();
                if (margin.compareTo(rule.getMinMarginThreshold()) < 0) continue;
                Suggestion candidate = new Suggestion(suggested, margin, rule.isPromoted());
                suggestions.merge(suggested.getId(), candidate, (existing, incoming) ->
                        (incoming.promoted && !existing.promoted) || incoming.marginPercent.compareTo(existing.marginPercent) > 0 ? incoming : existing);
            }
        }
        return suggestions.values().stream()
                .sorted(Comparator.comparing((Suggestion s) -> s.promoted).reversed()
                        .thenComparing(s -> s.marginPercent, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    // =================================================================== live recommendation panel

    /** Ranked recommendations for the quotation, minus anything the rep has dismissed on it. */
    public RecommendationPanelResponse recommendFor(Quotation quotation, RecommendationType onlyType) {
        Set<Long> productsInCart = quotation.getLines().stream().map(l -> l.getProduct().getId()).collect(Collectors.toSet());
        Set<String> dismissed = dismissalRepository.findByQuotationId(quotation.getId()).stream()
                .map(d -> key(d.getRule().getId(), d.getSourceLineId()))
                .collect(Collectors.toSet());
        Customer customer = quotation.getCustomer();

        // How many different cart lines point at the same recommended product - "relationship strength".
        Map<Long, Integer> strength = new HashMap<>();
        for (QuotationLine line : quotation.getLines()) {
            for (UpsellRule rule : upsellRuleRepository.findByBaseProductId(line.getProduct().getId())) {
                if (rule.isActive()) strength.merge(rule.getSuggestedProduct().getId(), 1, Integer::sum);
            }
        }

        Map<String, RecommendationResponse> best = new LinkedHashMap<>();
        int dismissedCount = 0;

        for (QuotationLine line : quotation.getLines()) {
            for (UpsellRule rule : upsellRuleRepository.findByBaseProductId(line.getProduct().getId())) {
                if (!rule.isActive()) continue;
                RecommendationType type = rule.getRecommendationType();
                if (onlyType != null && type != onlyType) continue;
                Product suggested = rule.getSuggestedProduct();
                if (!suggested.isActive()) continue;                          // inactive product
                if (suggested.getId().equals(line.getProduct().getId())) continue; // a rule pointing at itself
                if (productsInCart.contains(suggested.getId())) continue;      // already on the quotation
                BigDecimal marginPercent = suggested.marginPercent();
                if (marginPercent.compareTo(rule.getMinMarginThreshold()) < 0) continue; // below the minimum margin

                boolean perLine = type == RecommendationType.PRODUCT_UPGRADE;
                Long sourceLineId = perLine ? line.getId() : null;
                if (dismissed.contains(key(rule.getId(), sourceLineId))) { dismissedCount++; continue; }

                RecommendationResponse rec = perLine ? buildUpgrade(quotation, line, rule, customer) : buildAdd(quotation, line, rule, customer);
                if (rec == null) continue; // e.g. an "upgrade" that is not actually a step up
                rec.marginPercent = marginPercent.setScale(2, RoundingMode.HALF_UP);
                score(rec, rule, customer, strength.getOrDefault(suggested.getId(), 1), quotation);

                // De-duplicate: same product recommended from several lines -> keep the strongest card.
                String dedupeKey = type + ":" + suggested.getId() + (perLine ? ":" + line.getId() : "");
                best.merge(dedupeKey, rec, (a, b) -> b.priorityScore.compareTo(a.priorityScore) > 0 ? b : a);
            }
        }

        List<RecommendationResponse> ranked = best.values().stream()
                .sorted(Comparator.comparing((RecommendationResponse r) -> r.priorityScore).reversed()
                        .thenComparing(r -> r.marginImpact, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        RecommendationPanelResponse panel = new RecommendationPanelResponse();
        panel.recommendations = ranked;
        panel.crossSellCount = (int) ranked.stream().filter(r -> r.type == RecommendationType.CROSS_SELL).count();
        panel.upsellCount = (int) ranked.stream().filter(r -> r.type == RecommendationType.UPSELL).count();
        panel.upgradeCount = (int) ranked.stream().filter(r -> r.type == RecommendationType.PRODUCT_UPGRADE).count();
        panel.dismissedCount = dismissedCount;
        panel.currentTotal = quotation.totalAmount();
        panel.currentMargin = quotationMargin(quotation);
        return panel;
    }

    /** CROSS_SELL / UPSELL: the recommended product is ADDED (quantity 1) at the customer's tier price, undiscounted. */
    private RecommendationResponse buildAdd(Quotation quotation, QuotationLine source, UpsellRule rule, Customer customer) {
        Product suggested = rule.getSuggestedProduct();
        List<SubscriptionPlan> plans = pricingService.plansFor(suggested);
        BigDecimal unitPrice = plans.isEmpty()
                ? pricingService.priceFor(customer, suggested)
                : pricingService.defaultPlanFor(suggested, source.getSubscriptionPlan()).map(SubscriptionPlan::getPricePerCycle).orElse(suggested.getPrice());
        int qty = 1;

        RecommendationResponse rec = base(rule, source, suggested);
        rec.price = unitPrice;
        rec.quantitySuggestion = qty;
        rec.priceImpact = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        rec.marginImpact = unitPrice.subtract(suggested.getCost()).multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        rec.wouldNeedApproval = false; // added at 0% discount - always within ceiling
        rec.actions = new ArrayList<>(List.of("ADD"));
        // An UPSELL that is a premium version of the source (same category, higher price) can also be applied as an in-place upgrade.
        if (rule.getRecommendationType() == RecommendationType.UPSELL
                && Objects.equals(suggested.getCategory(), source.getProduct().getCategory())
                && unitPrice.compareTo(source.getUnitPrice()) > 0
                && (source.getLineType() != QuotationLine.LineType.RECURRING || !plans.isEmpty())) {
            rec.actions.add("UPGRADE");
        }
        rec.reason = rule.getReason() != null && !rule.getReason().isBlank() ? rule.getReason()
                : (rule.getRecommendationType() == RecommendationType.UPSELL
                    ? "Premium option customers of " + source.getProduct().getName() + " typically add"
                    : "Frequently bought together with " + source.getProduct().getName());
        return rec;
    }

    /**
     * PRODUCT_UPGRADE: the source line is REPLACED by the upgrade at the same quantity and discount, so
     * every figure is the real delta between the two line totals / margins.
     */
    private RecommendationResponse buildUpgrade(Quotation quotation, QuotationLine source, UpsellRule rule, Customer customer) {
        Product upgrade = rule.getSuggestedProduct();
        boolean recurring = source.getLineType() == QuotationLine.LineType.RECURRING;
        BigDecimal newUnit;
        if (recurring) {
            Optional<SubscriptionPlan> plan = pricingService.defaultPlanFor(upgrade, source.getSubscriptionPlan());
            if (plan.isEmpty()) return null; // a recurring line can only be upgraded to a product that has a plan
            newUnit = plan.get().getPricePerCycle();
        } else {
            newUnit = pricingService.priceFor(customer, upgrade);
        }
        if (newUnit.compareTo(source.getUnitPrice()) <= 0) return null; // not a step up for this customer -> never "upgrade" downwards

        int qty = source.getQuantity();
        BigDecimal discountFactor = BigDecimal.ONE.subtract(source.getDiscountPercent().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal newTotal = newUnit.multiply(BigDecimal.valueOf(qty)).multiply(discountFactor).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newMargin = newTotal.subtract(upgrade.getCost().multiply(BigDecimal.valueOf(qty)));

        RecommendationResponse rec = base(rule, source, upgrade);
        rec.currentPrice = source.getUnitPrice();
        rec.price = newUnit;
        rec.quantitySuggestion = qty;
        rec.priceImpact = newTotal.subtract(source.lineTotal()).setScale(2, RoundingMode.HALF_UP);
        rec.marginImpact = newMargin.subtract(source.marginAmount()).setScale(2, RoundingMode.HALF_UP);
        // The line keeps its discount - if the upgrade's category has a lower ceiling, that discount may now need approval.
        BigDecimal ceiling = discountRiskService.ceilingFor(customer.getTier(), upgrade.getCategory());
        rec.wouldNeedApproval = source.getDiscountPercent().compareTo(ceiling) > 0;
        rec.actions = new ArrayList<>(List.of("UPGRADE", "ADD_BOTH"));
        rec.reason = rule.getReason() != null && !rule.getReason().isBlank() ? rule.getReason()
                : "Premium version of " + source.getProduct().getName() + " - higher customer value";
        return rec;
    }

    private RecommendationResponse base(UpsellRule rule, QuotationLine source, Product suggested) {
        RecommendationResponse rec = new RecommendationResponse();
        rec.ruleId = rule.getId();
        rec.type = rule.getRecommendationType();
        rec.sourceLineId = rule.getRecommendationType() == RecommendationType.PRODUCT_UPGRADE ? source.getId() : null;
        rec.recommendationId = "R" + rule.getId() + (rec.sourceLineId != null ? "-L" + rec.sourceLineId : "");
        rec.sourceProductId = source.getProduct().getId();
        rec.sourceProduct = source.getProduct().getName();
        rec.recommendedProductId = suggested.getId();
        rec.recommendedProduct = suggested.getName();
        rec.productName = suggested.getName();
        rec.category = suggested.getCategory();
        rec.productImageUrl = suggested.getImageUrl();
        rec.promotionTag = rule.getPromotionTag();
        rec.promoted = rule.isPromoted();
        return rec;
    }

    /**
     * Smart ranking. Every factor is additive and written into {@code scoreBreakdown} so the order of
     * the cards can always be explained:
     * <pre>
     *   configured priority (0-100)
     * + 20 if the rule is promoted
     * + up to 30 from the recommended product's own margin % (0.5 pt per %)
     * + 8 if accepting it adds margin / -15 if it would lose margin
     * + 5 per extra cart line that also points at this product (relationship strength, max +15)
     * + tier affinity: GOLD +8 / SILVER +4 on premium (UPSELL / PRODUCT_UPGRADE) recommendations
     * + type: PRODUCT_UPGRADE +5, UPSELL +3
     * - 10 if accepting it would push the deal into approval
     * </pre>
     */
    private void score(RecommendationResponse rec, UpsellRule rule, Customer customer, int relationshipStrength, Quotation quotation) {
        BigDecimal score = BigDecimal.valueOf(rule.getPriority());
        List<String> parts = new ArrayList<>();
        parts.add("priority " + rule.getPriority());
        if (rule.isPromoted()) { score = score.add(BigDecimal.valueOf(20)); parts.add("promoted +20"); }

        BigDecimal marginPts = rec.marginPercent.multiply(new BigDecimal("0.5")).min(BigDecimal.valueOf(30)).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
        score = score.add(marginPts); parts.add("margin " + rec.marginPercent + "% +" + marginPts);

        if (rec.marginImpact.signum() > 0) { score = score.add(BigDecimal.valueOf(8)); parts.add("adds margin +8"); }
        else if (rec.marginImpact.signum() < 0) { score = score.subtract(BigDecimal.valueOf(15)); parts.add("loses margin -15"); }

        int strengthPts = Math.min(15, Math.max(0, relationshipStrength - 1) * 5);
        if (strengthPts > 0) { score = score.add(BigDecimal.valueOf(strengthPts)); parts.add("suggested by " + relationshipStrength + " cart lines +" + strengthPts); }

        boolean premium = rec.type != RecommendationType.CROSS_SELL;
        if (premium && customer.getTier() == CustomerTier.GOLD) { score = score.add(BigDecimal.valueOf(8)); parts.add("Gold customer +8"); }
        else if (premium && customer.getTier() == CustomerTier.SILVER) { score = score.add(BigDecimal.valueOf(4)); parts.add("Silver customer +4"); }

        if (rec.type == RecommendationType.PRODUCT_UPGRADE) { score = score.add(BigDecimal.valueOf(5)); parts.add("upgrade +5"); }
        else if (rec.type == RecommendationType.UPSELL) { score = score.add(BigDecimal.valueOf(3)); parts.add("upsell +3"); }

        if (rec.wouldNeedApproval) { score = score.subtract(BigDecimal.valueOf(10)); parts.add("would need approval -10"); }

        rec.priorityScore = score.setScale(1, RoundingMode.HALF_UP);
        rec.scoreBreakdown = String.join(", ", parts);
    }

    public static BigDecimal quotationMargin(Quotation quotation) {
        return quotation.getLines().stream().map(QuotationLine::marginAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static String key(Long ruleId, Long sourceLineId) {
        return ruleId + ":" + (sourceLineId == null ? "" : sourceLineId);
    }

    // =================================================================== dismissals

    @Transactional
    public void dismiss(Quotation quotation, UpsellRule rule, Long sourceLineId, String actor) {
        boolean already = dismissalRepository.findByQuotationId(quotation.getId()).stream()
                .anyMatch(d -> d.getRule().getId().equals(rule.getId()) && Objects.equals(d.getSourceLineId(), sourceLineId));
        if (!already) {
            dismissalRepository.save(new RecommendationDismissal(quotation, rule, sourceLineId, actor));
        }
        auditService.log("Quotation", quotation.getId(), "RECOMMENDATION_DISMISSED", actor,
                "Dismissed " + rule.getRecommendationType() + " recommendation: " + rule.getBaseProduct().getName() + " -> " + rule.getSuggestedProduct().getName());
    }

    @Transactional
    public void clearDismissal(Quotation quotation, UpsellRule rule) {
        dismissalRepository.findByQuotationId(quotation.getId()).stream()
                .filter(d -> d.getRule().getId().equals(rule.getId()))
                .forEach(dismissalRepository::delete);
    }

    @Transactional
    public int restoreDismissed(Quotation quotation, String actor) {
        List<RecommendationDismissal> dismissals = dismissalRepository.findByQuotationId(quotation.getId());
        dismissals.forEach(dismissalRepository::delete);
        if (!dismissals.isEmpty()) {
            auditService.log("Quotation", quotation.getId(), "RECOMMENDATIONS_RESTORED", actor, "Restored " + dismissals.size() + " dismissed recommendation(s)");
        }
        return dismissals.size();
    }

    // =================================================================== rule administration (A6)

    public List<UpsellRule> listRules() {
        return upsellRuleRepository.findAll();
    }

    public UpsellRule getRule(Long id) {
        return upsellRuleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommendation rule not found"));
    }

    @Transactional
    public UpsellRule createRule(RecommendationRuleRequest request, String actor) {
        UpsellRule rule = new UpsellRule();
        apply(rule, request);
        rule.setCreatedAt(LocalDateTime.now());
        rule = upsellRuleRepository.save(rule);
        auditService.log("UpsellRule", rule.getId(), "RECOMMENDATION_RULE_CREATED", actor, describe(rule));
        return rule;
    }

    @Transactional
    public UpsellRule updateRule(Long id, RecommendationRuleRequest request, String actor) {
        UpsellRule rule = getRule(id);
        apply(rule, request);
        rule = upsellRuleRepository.save(rule);
        auditService.log("UpsellRule", rule.getId(), "RECOMMENDATION_RULE_UPDATED", actor, describe(rule));
        return rule;
    }

    @Transactional
    public UpsellRule setActive(Long id, boolean active, String actor) {
        UpsellRule rule = getRule(id);
        rule.setActive(active);
        rule.setUpdatedAt(LocalDateTime.now());
        rule = upsellRuleRepository.save(rule);
        auditService.log("UpsellRule", rule.getId(), active ? "RECOMMENDATION_RULE_ACTIVATED" : "RECOMMENDATION_RULE_DEACTIVATED", actor, describe(rule));
        return rule;
    }

    @Transactional
    public void deleteRule(Long id, String actor) {
        UpsellRule rule = getRule(id);
        auditService.log("UpsellRule", rule.getId(), "RECOMMENDATION_RULE_DELETED", actor, describe(rule));
        upsellRuleRepository.delete(rule);
    }

    private void apply(UpsellRule rule, RecommendationRuleRequest request) {
        if (request.baseProductId == null || request.suggestedProductId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both the source product and the recommended product are required");
        }
        if (request.baseProductId.equals(request.suggestedProductId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A product cannot recommend itself");
        }
        Product base = productRepository.findById(request.baseProductId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source product not found"));
        Product suggested = productRepository.findById(request.suggestedProductId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommended product not found"));
        RecommendationType type = request.recommendationType != null ? request.recommendationType : RecommendationType.CROSS_SELL;
        int priority = request.priority != null ? request.priority : UpsellRule.DEFAULT_PRIORITY;
        if (priority < 0 || priority > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Priority must be between 0 and 100");
        }
        BigDecimal threshold = request.minMarginThreshold != null ? request.minMarginThreshold : BigDecimal.ZERO;
        if (threshold.compareTo(BigDecimal.ZERO) < 0 || threshold.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum margin threshold must be between 0 and 100 percent");
        }
        if (type == RecommendationType.PRODUCT_UPGRADE && suggested.getPrice().compareTo(base.getPrice()) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A product upgrade must point at a higher-priced product (" + suggested.getName() + " is not more expensive than " + base.getName() + ")");
        }
        boolean duplicate = upsellRuleRepository.findByBaseProductId(base.getId()).stream()
                .anyMatch(r -> !r.getId().equals(rule.getId()) && r.getSuggestedProduct().getId().equals(suggested.getId()) && r.getRecommendationType() == type);
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + type + " rule from " + base.getName() + " to " + suggested.getName() + " already exists - edit that one instead");
        }
        rule.setBaseProduct(base);
        rule.setSuggestedProduct(suggested);
        rule.setRecommendationType(type);
        rule.setPriority(priority);
        rule.setActive(request.active == null || request.active);
        rule.setPromotionTag(request.promotionTag == null || request.promotionTag.isBlank() ? null : request.promotionTag.trim());
        rule.setMinMarginThreshold(threshold);
        rule.setPromoted(request.promoted != null && request.promoted);
        rule.setReason(request.reason == null || request.reason.isBlank() ? null : request.reason.trim());
        rule.setUpdatedAt(LocalDateTime.now());
    }

    private String describe(UpsellRule rule) {
        return rule.getRecommendationType() + ": " + rule.getBaseProduct().getName() + " -> " + rule.getSuggestedProduct().getName()
                + " (priority " + rule.getPriority() + ", " + (rule.isActive() ? "active" : "inactive") + ")";
    }

    public RecommendationRuleResponse toRuleResponse(UpsellRule rule) {
        RecommendationRuleResponse dto = new RecommendationRuleResponse();
        dto.id = rule.getId();
        dto.baseProductId = rule.getBaseProduct().getId();
        dto.baseProductName = rule.getBaseProduct().getName();
        dto.baseProductCategory = rule.getBaseProduct().getCategory();
        dto.baseProductPrice = rule.getBaseProduct().getPrice();
        dto.suggestedProductId = rule.getSuggestedProduct().getId();
        dto.suggestedProductName = rule.getSuggestedProduct().getName();
        dto.suggestedProductCategory = rule.getSuggestedProduct().getCategory();
        dto.suggestedProductPrice = rule.getSuggestedProduct().getPrice();
        dto.suggestedProductMarginPercent = rule.getSuggestedProduct().marginPercent().setScale(2, RoundingMode.HALF_UP);
        dto.recommendationType = rule.getRecommendationType();
        dto.priority = rule.getPriority();
        dto.active = rule.isActive();
        dto.promotionTag = rule.getPromotionTag();
        dto.minMarginThreshold = rule.getMinMarginThreshold();
        dto.promoted = rule.isPromoted();
        dto.reason = rule.getReason();
        dto.createdAt = rule.getCreatedAt();
        dto.updatedAt = rule.getUpdatedAt();
        if (dto.suggestedProductMarginPercent.compareTo(rule.getMinMarginThreshold()) < 0) {
            dto.warning = "The recommended product's margin (" + dto.suggestedProductMarginPercent + "%) is below this rule's minimum (" + rule.getMinMarginThreshold() + "%) - it will never be shown";
        } else if (!rule.getSuggestedProduct().isActive()) {
            dto.warning = "The recommended product is inactive - it will never be shown";
        }
        return dto;
    }
}
