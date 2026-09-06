import com.dealflow360.dto.QuotationDtos.*;
import com.dealflow360.dto.RecommendationDtos.*;
import com.dealflow360.model.*;
import com.dealflow360.model.UpsellRule.RecommendationType;
import com.dealflow360.repository.*;
import com.dealflow360.service.*;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Drives the REAL recommendation engine (UpsellService + QuotationService + PricingService) against in-memory repositories. */
public class RecommendationVerify {
    // ------------------------------------------------------------------ generic in-memory repo
    static long nextId = 1;
    static void assignId(Object entity) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            if (f.get(entity) == null) f.set(entity, nextId++);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static Long idOf(Object entity) {
        try { Field f = entity.getClass().getDeclaredField("id"); f.setAccessible(true); return (Long) f.get(entity); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    static class MemRepo<T> {
        final Map<Long, T> store = new LinkedHashMap<>();
        public T save(T e) { assignId(e); store.put(idOf(e), e); return e; }
        public Optional<T> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        public List<T> findAll() { return new ArrayList<>(store.values()); }
        public void deleteById(Long id) { store.remove(id); }
        public void delete(T e) { store.remove(idOf(e)); }
        public long count() { return store.size(); }
        public boolean existsById(Long id) { return store.containsKey(id); }
    }

    static class Quotations extends MemRepo<Quotation> implements QuotationRepository {
        @Override public Quotation save(Quotation q) { super.save(q); for (QuotationLine l : q.getLines()) assignId(l); return q; }
        public List<Quotation> findBySalesRepId(Long id) { return findAll().stream().filter(q -> q.getSalesRep().getId().equals(id)).collect(Collectors.toList()); }
        public List<Quotation> findByCustomerId(Long id) { return findAll().stream().filter(q -> q.getCustomer().getId().equals(id)).collect(Collectors.toList()); }
        public List<Quotation> findByStatus(Quotation.Status s) { return findAll().stream().filter(q -> q.getStatus() == s).collect(Collectors.toList()); }
    }
    static class Lines extends MemRepo<QuotationLine> implements QuotationLineRepository {
        public List<QuotationLine> findByQuotationId(Long id) { return new ArrayList<>(); }
    }
    static class Customers extends MemRepo<Customer> implements CustomerRepository {
        public Optional<Customer> findByPortalUsername(String u) { return findAll().stream().filter(c -> u.equals(c.getPortalUsername())).findFirst(); }
    }
    static class Users extends MemRepo<AppUser> implements AppUserRepository {
        public Optional<AppUser> findByUsername(String u) { return findAll().stream().filter(x -> u.equals(x.getUsername())).findFirst(); }
        public List<AppUser> findByRole(Role r) { return findAll().stream().filter(x -> x.getRole() == r).collect(Collectors.toList()); }
    }
    static class Products extends MemRepo<Product> implements ProductRepository {
        public List<Product> findByCategory(String c) { return findAll().stream().filter(p -> c.equals(p.getCategory())).collect(Collectors.toList()); }
    }
    static class Plans extends MemRepo<SubscriptionPlan> implements SubscriptionPlanRepository {
        public List<SubscriptionPlan> findByProductId(Long pid) { return findAll().stream().filter(p -> p.getProduct().getId().equals(pid)).collect(Collectors.toList()); }
    }
    static class Dismissals extends MemRepo<RecommendationDismissal> implements RecommendationDismissalRepository {
        public List<RecommendationDismissal> findByQuotationId(Long id) { return findAll().stream().filter(d -> d.getQuotation().getId().equals(id)).collect(Collectors.toList()); }
        public void deleteByQuotationIdAndRuleId(Long q, Long r) {}
    }
    static class Messages extends MemRepo<NegotiationMessage> implements NegotiationMessageRepository {
        public List<NegotiationMessage> findByQuotationIdOrderByTimestampAsc(Long id) { return findAll().stream().filter(m -> m.getQuotation().getId().equals(id)).collect(Collectors.toList()); }
    }
    static class PriceLists extends MemRepo<PriceListEntry> implements PriceListEntryRepository {
        public Optional<PriceListEntry> findByTierAndProductId(CustomerTier t, Long pid) { return findAll().stream().filter(e -> e.getTier() == t && e.getProduct().getId().equals(pid)).findFirst(); }
        public List<PriceListEntry> findByProductId(Long pid) { return findAll().stream().filter(e -> e.getProduct().getId().equals(pid)).collect(Collectors.toList()); }
    }
    static class ApprovalLogs extends MemRepo<ApprovalLog> implements ApprovalLogRepository {
        public List<ApprovalLog> findByQuotationIdOrderByTimestampAsc(Long id) { return findAll().stream().filter(l -> l.getQuotation().getId().equals(id)).collect(Collectors.toList()); }
    }
    static class Stocks extends MemRepo<StockLevel> implements StockLevelRepository {
        public List<StockLevel> findByProductIdOrderByWarehouse_ShippingCostWeightAsc(Long pid) {
            return findAll().stream().filter(s -> s.getProduct().getId().equals(pid))
                    .sorted(Comparator.comparing(s -> s.getWarehouse().getShippingCostWeight())).collect(Collectors.toList());
        }
        public Optional<StockLevel> findByWarehouseIdAndProductId(Long wid, Long pid) { return findAll().stream().filter(s -> s.getWarehouse().getId().equals(wid) && s.getProduct().getId().equals(pid)).findFirst(); }
    }
    static class Splits extends MemRepo<FulfillmentSplit> implements FulfillmentSplitRepository {
        public List<FulfillmentSplit> findByQuotationId(Long id) { return findAll().stream().filter(s -> s.getQuotation().getId().equals(id)).collect(Collectors.toList()); }
        public List<FulfillmentSplit> findByDeliveredFalseAndExpectedDeliveryDateBefore(LocalDate d) { return new ArrayList<>(); }
    }
    static class Backorders extends MemRepo<Backorder> implements BackorderRepository {
        public List<Backorder> findByQuotationIdAndResolvedFalse(Long id) { return findAll().stream().filter(b -> b.getQuotation().getId().equals(id) && !b.isResolved()).collect(Collectors.toList()); }
        public List<Backorder> findByResolvedFalse() { return findAll().stream().filter(b -> !b.isResolved()).collect(Collectors.toList()); }
    }
    static class Billing extends MemRepo<BillingScheduleEntry> implements BillingScheduleEntryRepository {
        public List<BillingScheduleEntry> findByQuotationLineIdOrderByBillingDateAsc(Long lid) { return findAll().stream().filter(e -> e.getQuotationLine().getId().equals(lid)).collect(Collectors.toList()); }
        public List<BillingScheduleEntry> findByQuotationLine_Quotation_IdOrderByBillingDateAsc(Long qid) { return findAll().stream().filter(e -> e.getQuotationLine().getQuotation().getId().equals(qid)).collect(Collectors.toList()); }
    }
    static class Upsells extends MemRepo<UpsellRule> implements UpsellRuleRepository {
        public List<UpsellRule> findByBaseProductId(Long id) { return findAll().stream().filter(r -> r.getBaseProduct().getId().equals(id)).collect(Collectors.toList()); }
        public List<UpsellRule> findBySuggestedProductId(Long id) { return findAll().stream().filter(r -> r.getSuggestedProduct().getId().equals(id)).collect(Collectors.toList()); }
    }
    static class Audits extends MemRepo<AuditEntry> implements AuditEntryRepository {
        public List<AuditEntry> findByEntityTypeAndEntityIdOrderByTimestampAsc(String t, Long id) { return findAll().stream().filter(a -> t.equals(a.getEntityType()) && id.equals(a.getEntityId())).collect(Collectors.toList()); }
        public Optional<AuditEntry> findTopByEntityTypeAndEntityIdAndActionOrderByTimestampDesc(String t, Long id, String a) { return Optional.empty(); }
        public List<AuditEntry> findTop200ByOrderByTimestampDesc() { return findAll(); }
        public List<AuditEntry> findTop200ByActionStartingWithOrderByTimestampDesc(String p) { return findAll(); }
    }
    static class Ceilings extends MemRepo<DiscountCeiling> implements DiscountCeilingRepository {
        public Optional<DiscountCeiling> findByTierAndCategory(CustomerTier t, String c) { return findAll().stream().filter(x -> x.getTier() == t && x.getCategory().equals(c)).findFirst(); }
        public List<DiscountCeiling> findByTier(CustomerTier t) { return findAll().stream().filter(x -> x.getTier() == t).collect(Collectors.toList()); }
    }
    static class Rules extends MemRepo<ApprovalChainRule> implements ApprovalChainRuleRepository {
        public List<ApprovalChainRule> findAllByOrderByMinRiskScoreAsc() { return findAll().stream().sorted(Comparator.comparing(ApprovalChainRule::getMinRiskScore)).collect(Collectors.toList()); }
    }


    static int passed = 0, failed = 0;
    static void check(String label, boolean cond) { if (cond) { System.out.println("PASS - " + label); passed++; } else { System.out.println("FAIL - " + label); failed++; } }
    static String refused(Runnable r) { try { r.run(); return null; } catch (ResponseStatusException e) { return e.getReason(); } catch (RuntimeException e) { return e.getMessage(); } }
    static AddLineRequest line(Long productId, int qty, String disc) { AddLineRequest r = new AddLineRequest(); r.productId = productId; r.quantity = qty; r.discountPercent = new BigDecimal(disc); return r; }
    static RecommendationResponse find(RecommendationPanelResponse p, RecommendationType t, String name) {
        return p.recommendations.stream().filter(r -> r.type == t && r.productName.equals(name)).findFirst().orElse(null);
    }
    static RecommendationRuleRequest ruleReq(Long base, Long sug, RecommendationType t, int prio, String thr, boolean promoted, String tag, String reason) {
        RecommendationRuleRequest r = new RecommendationRuleRequest(); r.baseProductId = base; r.suggestedProductId = sug; r.recommendationType = t; r.priority = prio;
        r.minMarginThreshold = new BigDecimal(thr); r.promoted = promoted; r.promotionTag = tag; r.reason = reason; return r;
    }

    public static void main(String[] args) {
        Quotations quotations = new Quotations(); Lines lines = new Lines(); Customers customers = new Customers(); Users users = new Users();
        Products products = new Products(); Plans plans = new Plans(); Messages messages = new Messages(); PriceLists priceLists = new PriceLists();
        ApprovalLogs approvalLogs = new ApprovalLogs(); Stocks stocks = new Stocks(); Splits splits = new Splits(); Backorders backorders = new Backorders();
        Billing billing = new Billing(); Upsells upsells = new Upsells(); Audits audits = new Audits(); Ceilings ceilings = new Ceilings(); Rules rules = new Rules();
        Dismissals dismissals = new Dismissals();

        AuditService audit = new AuditService(audits);
        DiscountRiskService risk = new DiscountRiskService(ceilings);
        ApprovalService approval = new ApprovalService(rules, approvalLogs, risk);
        FulfillmentService fulfillment = new FulfillmentService(stocks, splits, backorders, audit);
        BillingService billingService = new BillingService(billing, audit);
        PricingService pricing = new PricingService(priceLists, plans);
        UpsellService upsell = new UpsellService(upsells, products, dismissals, pricing, risk, audit);
        QuotationService svc = new QuotationService(quotations, lines, customers, users, products, plans, messages, pricing, approvalLogs,
                risk, approval, fulfillment, billingService, upsell, audit);

        AppUser rep = users.save(new AppUser("rep1", "x", "Ananya", "a@x", Role.SALES_REP));
        Customer acme = customers.save(new Customer("Acme", "b@acme", CustomerTier.GOLD, "acme", "x"));
        Product laptopBasic = products.save(new Product("Laptop Basic", "Hardware", new BigDecimal("850.00"), new BigDecimal("700.00"), "unit", BigDecimal.ZERO, ""));
        Product laptopPro = products.save(new Product("Laptop Pro", "Hardware", new BigDecimal("1200.00"), new BigDecimal("900.00"), "unit", BigDecimal.ZERO, ""));
        Product mouse = products.save(new Product("Wireless Mouse", "Hardware", new BigDecimal("25.00"), new BigDecimal("12.00"), "unit", BigDecimal.ZERO, ""));
        Product bag = products.save(new Product("Laptop Bag", "Hardware", new BigDecimal("45.00"), new BigDecimal("18.00"), "unit", BigDecimal.ZERO, ""));
        Product thinMargin = products.save(new Product("Cheap Cable", "Hardware", new BigDecimal("10.00"), new BigDecimal("9.50"), "unit", BigDecimal.ZERO, ""));
        Product discontinued = products.save(new Product("Old Dock", "Hardware", new BigDecimal("90.00"), new BigDecimal("40.00"), "unit", BigDecimal.ZERO, ""));
        discontinued.setActive(false);
        Product cloud = products.save(new Product("Cloud Suite", "Subscription", new BigDecimal("50.00"), new BigDecimal("20.00"), "seat/month", BigDecimal.ZERO, ""));
        Product cloudPro = products.save(new Product("Cloud Suite Pro", "Subscription", new BigDecimal("80.00"), new BigDecimal("28.00"), "seat/month", BigDecimal.ZERO, ""));
        Product analytics = products.save(new Product("Analytics Add-on", "Subscription", new BigDecimal("20.00"), new BigDecimal("8.00"), "seat/month", BigDecimal.ZERO, ""));
        SubscriptionPlan cloudMonthly = plans.save(new SubscriptionPlan("Cloud monthly", cloud, BillingCycle.MONTHLY, new BigDecimal("50.00"), true, true));
        SubscriptionPlan cloudYearly = plans.save(new SubscriptionPlan("Cloud yearly", cloud, BillingCycle.YEARLY, new BigDecimal("540.00"), true, true));
        SubscriptionPlan proMonthly = plans.save(new SubscriptionPlan("Pro monthly", cloudPro, BillingCycle.MONTHLY, new BigDecimal("80.00"), true, true));
        SubscriptionPlan proYearly = plans.save(new SubscriptionPlan("Pro yearly", cloudPro, BillingCycle.YEARLY, new BigDecimal("864.00"), true, true));
        plans.save(new SubscriptionPlan("Analytics monthly", analytics, BillingCycle.MONTHLY, new BigDecimal("20.00"), true, true));
        Warehouse main = new Warehouse("Main", "A", new BigDecimal("1.00")); assignId(main);
        stocks.save(new StockLevel(main, laptopPro, 50, 5)); stocks.save(new StockLevel(main, laptopBasic, 50, 5)); stocks.save(new StockLevel(main, mouse, 50, 5)); stocks.save(new StockLevel(main, bag, 50, 5));
        ceilings.save(new DiscountCeiling(CustomerTier.GOLD, "DEFAULT", new BigDecimal("15.00")));
        ceilings.save(new DiscountCeiling(CustomerTier.GOLD, "Hardware", new BigDecimal("15.00")));
        ceilings.save(new DiscountCeiling(CustomerTier.GOLD, "Subscription", new BigDecimal("12.00")));
        rules.save(new ApprovalChainRule(new BigDecimal("0.00"), new BigDecimal("0.01"), false, false, "clean"));
        rules.save(new ApprovalChainRule(new BigDecimal("0.01"), new BigDecimal("999999.00"), true, false, "manager"));
        priceLists.save(new PriceListEntry(CustomerTier.GOLD, laptopPro, new BigDecimal("1150.00")));

        // ---- rule admin validation
        check("Rule: product cannot recommend itself", refused(() -> upsell.createRule(ruleReq(mouse.getId(), mouse.getId(), RecommendationType.CROSS_SELL, 50, "0", false, null, null), "admin")) != null);
        check("Rule: an upgrade to a cheaper product is refused", refused(() -> upsell.createRule(ruleReq(laptopPro.getId(), laptopBasic.getId(), RecommendationType.PRODUCT_UPGRADE, 50, "0", false, null, null), "admin")) != null);
        check("Rule: priority outside 0-100 is refused", refused(() -> upsell.createRule(ruleReq(laptopBasic.getId(), mouse.getId(), RecommendationType.CROSS_SELL, 120, "0", false, null, null), "admin")) != null);
        UpsellRule upgradeRule = upsell.createRule(ruleReq(laptopBasic.getId(), laptopPro.getId(), RecommendationType.PRODUCT_UPGRADE, 95, "20", true, "Recommended Upgrade", "More RAM, faster processor, more storage"), "admin");
        UpsellRule mouseRule = upsell.createRule(ruleReq(laptopBasic.getId(), mouse.getId(), RecommendationType.CROSS_SELL, 90, "30", true, "Bundle offer", null), "admin");
        UpsellRule bagRule = upsell.createRule(ruleReq(laptopBasic.getId(), bag.getId(), RecommendationType.CROSS_SELL, 60, "30", false, null, null), "admin");
        UpsellRule cableRule = upsell.createRule(ruleReq(laptopBasic.getId(), thinMargin.getId(), RecommendationType.CROSS_SELL, 99, "30", true, null, null), "admin");
        UpsellRule dockRule = upsell.createRule(ruleReq(laptopBasic.getId(), discontinued.getId(), RecommendationType.CROSS_SELL, 99, "0", true, null, null), "admin");
        UpsellRule proMouse = upsell.createRule(ruleReq(laptopPro.getId(), mouse.getId(), RecommendationType.CROSS_SELL, 70, "30", false, null, null), "admin");
        UpsellRule cloudUpgrade = upsell.createRule(ruleReq(cloud.getId(), cloudPro.getId(), RecommendationType.PRODUCT_UPGRADE, 90, "20", true, "Recommended Upgrade", "SSO, audit logs, priority support"), "admin");
        UpsellRule cloudUpsell = upsell.createRule(ruleReq(cloud.getId(), analytics.getId(), RecommendationType.UPSELL, 75, "20", true, "Popular add-on", null), "admin");
        check("Rule: an exact duplicate (same source, target, type) is refused", refused(() -> upsell.createRule(ruleReq(laptopBasic.getId(), mouse.getId(), RecommendationType.CROSS_SELL, 10, "0", false, null, null), "admin")) != null);
        check("Rule response flags a rule whose target margin is below its own threshold", upsell.toRuleResponse(cableRule).warning != null);

        // ---- the quotation: 10 x Laptop Basic at 5%, 10 seats of Cloud Suite yearly
        Quotation q0 = svc.createQuotation(acme.getId(), "rep1");
        final Long qId = q0.getId();
        Quotation q = q0;
        svc.addLine(q.getId(), line(laptopBasic.getId(), 10, "5.00"));
        AddLineRequest cloudLine = line(cloud.getId(), 10, "0"); cloudLine.subscriptionPlanId = cloudYearly.getId(); // no lineType given on purpose
        svc.addLine(q.getId(), cloudLine);
        QuotationLine cloudQl = q.getLines().get(1);
        check("Subscription fix: a product with plans is added as RECURRING even when the caller left the line type at ONE_TIME",
                cloudQl.getLineType() == QuotationLine.LineType.RECURRING && cloudQl.getSubscriptionPlan().getId().equals(cloudYearly.getId()));
        AddLineRequest noPlan = line(analytics.getId(), 1, "0");
        Quotation tmp = svc.createQuotation(acme.getId(), "rep1");
        svc.addLine(tmp.getId(), noPlan);
        check("Subscription fix: no plan chosen -> the product's default (monthly) plan is used", tmp.getLines().get(0).getSubscriptionPlan() != null && tmp.getLines().get(0).getUnitPrice().compareTo(new BigDecimal("20.00")) == 0);

        RecommendationPanelResponse panel = upsell.recommendFor(q, null);
        List<String> names = panel.recommendations.stream().map(r -> r.type + ":" + r.productName).collect(Collectors.toList());
        System.out.println("   ranked: " + names);
        check("Thin-margin product (5% < 30% threshold) is NOT recommended", find(panel, RecommendationType.CROSS_SELL, "Cheap Cable") == null);
        check("Inactive product is NOT recommended", find(panel, RecommendationType.CROSS_SELL, "Old Dock") == null);
        RecommendationResponse up = find(panel, RecommendationType.PRODUCT_UPGRADE, "Laptop Pro");
        check("PRODUCT_UPGRADE Laptop Basic -> Laptop Pro is offered", up != null);
        check("Upgrade card: current 850, upgrade at the GOLD tier price 1150, quantity suggestion 10",
                up != null && up.currentPrice.compareTo(new BigDecimal("850.00")) == 0 && up.price.compareTo(new BigDecimal("1150.00")) == 0 && up.quantitySuggestion == 10);
        // priceImpact = (1150-850)*10*0.95 = 2850 ; marginImpact = new margin (1150*10*.95 - 9000 = 1925) - old (850*10*.95 - 7000 = 1075) = 850
        check("Upgrade card: additional cost 2850.00 and margin impact +850.00 computed from the real line (qty 10, 5% discount)",
                up != null && up.priceImpact.compareTo(new BigDecimal("2850.00")) == 0 && up.marginImpact.compareTo(new BigDecimal("850.00")) == 0);
        check("Upgrade card: reason and promotion tag come from the rule", up != null && "More RAM, faster processor, more storage".equals(up.reason) && "Recommended Upgrade".equals(up.promotionTag));
        check("Upgrade card offers UPGRADE and ADD_BOTH; cross-sell offers ADD only",
                up != null && up.actions.contains("UPGRADE") && up.actions.contains("ADD_BOTH") && find(panel, RecommendationType.CROSS_SELL, "Wireless Mouse").actions.equals(List.of("ADD")));
        check("Ranking: promoted Mouse (priority 90) ranks above Laptop Bag (priority 60)", names.indexOf("CROSS_SELL:Wireless Mouse") < names.indexOf("CROSS_SELL:Laptop Bag"));
        check("Ranking: the two promoted Gold upgrades take the top two slots; the higher-margin one (Cloud Pro 65%) edges the higher-priority one (Laptop Pro 25%)",
                names.get(0).equals("PRODUCT_UPGRADE:Cloud Suite Pro") && names.get(1).equals("PRODUCT_UPGRADE:Laptop Pro"));
        check("Cloud Suite -> Cloud Suite Pro upgrade and -> Analytics upsell both present",
                find(panel, RecommendationType.PRODUCT_UPGRADE, "Cloud Suite Pro") != null && find(panel, RecommendationType.UPSELL, "Analytics Add-on") != null);
        RecommendationResponse cloudUp = find(panel, RecommendationType.PRODUCT_UPGRADE, "Cloud Suite Pro");
        check("Recurring upgrade uses the plan with the SAME billing cycle (yearly 864 vs yearly 540)", cloudUp != null && cloudUp.price.compareTo(new BigDecimal("864.00")) == 0);
        check("Panel counts: 2 cross-sell, 1 upsell, 2 upgrades", panel.crossSellCount == 2 && panel.upsellCount == 1 && panel.upgradeCount == 2);
        check("Every recommendation has a score breakdown", panel.recommendations.stream().allMatch(r -> r.scoreBreakdown != null && r.priorityScore != null));

        // ---- dismiss
        RecommendationActionRequest dis = new RecommendationActionRequest(); dis.ruleId = bagRule.getId();
        svc.dismissRecommendation(q.getId(), dis, "rep1");
        panel = upsell.recommendFor(q, null);
        check("Dismissed Laptop Bag disappears and is counted as dismissed", find(panel, RecommendationType.CROSS_SELL, "Laptop Bag") == null && panel.dismissedCount == 1);
        check("Type filter works", upsell.recommendFor(q, RecommendationType.CROSS_SELL).recommendations.stream().allMatch(r -> r.type == RecommendationType.CROSS_SELL));
        svc.restoreRecommendations(q.getId(), "rep1");
        check("Restore brings the dismissed card back", find(upsell.recommendFor(q, null), RecommendationType.CROSS_SELL, "Laptop Bag") != null);

        // ---- accept CROSS_SELL (ADD)
        BigDecimal totalBefore = q.totalAmount();
        RecommendationActionRequest addMouse = new RecommendationActionRequest(); addMouse.ruleId = mouseRule.getId(); addMouse.mode = "ADD";
        q = svc.acceptRecommendation(q.getId(), addMouse, "rep1");
        check("ADD: Wireless Mouse line added (qty 1, 0%), total up by 25.00", q.getLines().size() == 3 && q.totalAmount().subtract(totalBefore).compareTo(new BigDecimal("25.00")) == 0);
        check("ADD: original Laptop Basic line still there", q.getLines().stream().anyMatch(l -> l.getProduct().getId().equals(laptopBasic.getId())));
        check("Mouse no longer recommended (already in cart) - from either laptop rule", find(upsell.recommendFor(q, null), RecommendationType.CROSS_SELL, "Wireless Mouse") == null);
        check("Accepting the same recommendation twice is refused", refused(() -> svc.acceptRecommendation(qId, addMouse, "rep1")) != null);

        // ---- accept PRODUCT_UPGRADE (replace)
        Long basicLineId = q.getLines().stream().filter(l -> l.getProduct().getId().equals(laptopBasic.getId())).findFirst().get().getId();
        RecommendationActionRequest wrongMode = new RecommendationActionRequest(); wrongMode.ruleId = mouseRule.getId(); wrongMode.mode = "UPGRADE";
        check("UPGRADE mode on a CROSS_SELL rule is refused", refused(() -> svc.acceptRecommendation(qId, wrongMode, "rep1")) != null);
        RecommendationActionRequest upgrade = new RecommendationActionRequest(); upgrade.ruleId = upgradeRule.getId(); upgrade.sourceLineId = basicLineId; upgrade.mode = "UPGRADE";
        totalBefore = q.totalAmount();
        q = svc.acceptRecommendation(q.getId(), upgrade, "rep1");
        QuotationLine upgraded = q.getLines().stream().filter(l -> l.getId().equals(basicLineId)).findFirst().get();
        check("UPGRADE: the SAME line now carries Laptop Pro (replaced, not duplicated)", upgraded.getProduct().getId().equals(laptopPro.getId()) && q.getLines().stream().noneMatch(l -> l.getProduct().getId().equals(laptopBasic.getId())));
        check("UPGRADE: quantity 10 and 5% discount preserved, unit price re-derived to the tier price 1150", upgraded.getQuantity() == 10 && upgraded.getDiscountPercent().compareTo(new BigDecimal("5.00")) == 0 && upgraded.getUnitPrice().compareTo(new BigDecimal("1150.00")) == 0);
        check("UPGRADE: quotation total rose by exactly the card's additional cost (2850.00)", q.totalAmount().subtract(totalBefore).compareTo(new BigDecimal("2850.00")) == 0);
        check("UPGRADE: line count unchanged (3)", q.getLines().size() == 3);
        check("Audit trail: 'Sales Rep accepted PRODUCT_UPGRADE recommendation: Laptop Basic -> Laptop Pro'",
                audits.findAll().stream().anyMatch(a -> "RECOMMENDATION_ACCEPTED".equals(a.getAction()) && a.getDetails().contains("accepted PRODUCT_UPGRADE recommendation: Laptop Basic -> Laptop Pro")));
        check("After the upgrade, Laptop Pro's own cross-sells apply and the Basic->Pro upgrade is gone",
                find(upsell.recommendFor(q, null), RecommendationType.PRODUCT_UPGRADE, "Laptop Pro") == null);

        // ---- discount risk / approval integration: raise the upgraded line to 18% (Hardware ceiling 15) then check the engine + submit
        UpdateLineRequest u = new UpdateLineRequest(); u.discountPercent = new BigDecimal("18.00");
        q = svc.updateLine(q.getId(), basicLineId, u);
        check("Risk score recomputed after discount change (18 - 15 = 3.00)", q.getBlendedRiskScore().compareTo(new BigDecimal("3.00")) == 0);
        RecommendationActionRequest cloudUpgradeReq = new RecommendationActionRequest(); cloudUpgradeReq.ruleId = cloudUpgrade.getId(); cloudUpgradeReq.sourceLineId = q.getLines().get(1).getId(); cloudUpgradeReq.mode = "UPGRADE";
        q = svc.acceptRecommendation(q.getId(), cloudUpgradeReq, "rep1");
        QuotationLine cloudUpgraded = q.getLines().get(1);
        check("Recurring UPGRADE: Cloud Suite -> Cloud Suite Pro on the yearly plan, 10 seats kept", cloudUpgraded.getProduct().getId().equals(cloudPro.getId()) && cloudUpgraded.getSubscriptionPlan().getId().equals(proYearly.getId()) && cloudUpgraded.getQuantity() == 10);
        svc.submitForApproval(q.getId(), "rep1");
        check("Submit after accepted recommendations routes on the recomputed score (3.00 -> Manager approval)", q.getStatus() == Quotation.Status.PENDING_APPROVAL);
        check("Accepting a recommendation while PENDING_APPROVAL is refused (lines are locked)", refused(() -> svc.acceptRecommendation(qId, new RecommendationActionRequest() {{ ruleId = bagRule.getId(); }}, "rep1")) != null);

        // ---- legacy endpoint still works
        Quotation q2 = svc.createQuotation(acme.getId(), "rep1");
        svc.addLine(q2.getId(), line(laptopBasic.getId(), 1, "0"));
        check("Legacy suggestFor still returns add-type suggestions (promoted first)", !upsell.suggestFor(q2).isEmpty() && upsell.suggestFor(q2).get(0).product.getId().equals(mouse.getId()));
        upsell.setActive(mouseRule.getId(), false, "admin");
        check("Deactivated rule disappears from both the engine and the legacy list", find(upsell.recommendFor(q2, null), RecommendationType.CROSS_SELL, "Wireless Mouse") == null && upsell.suggestFor(q2).stream().noneMatch(s -> s.product.getId().equals(mouse.getId())));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
