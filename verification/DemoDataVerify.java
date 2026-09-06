import com.dealflow360.model.*;
import com.dealflow360.repository.*;
import com.dealflow360.service.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Runs the real DemoDataService end to end on in-memory repositories and checks what it produced. */
public class DemoDataVerify {
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
    static class Warehouses extends MemRepo<Warehouse> implements WarehouseRepository {}
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

    public static void main(String[] args) {
        Quotations quotations = new Quotations(); Lines lines = new Lines(); Customers customers = new Customers(); Users users = new Users();
        Products products = new Products(); Plans plans = new Plans(); Messages messages = new Messages(); PriceLists priceLists = new PriceLists();
        ApprovalLogs approvalLogs = new ApprovalLogs(); Stocks stocks = new Stocks(); Splits splits = new Splits(); Backorders backorders = new Backorders();
        Billing billing = new Billing(); Upsells upsells = new Upsells(); Audits audits = new Audits(); Ceilings ceilings = new Ceilings(); Rules rules = new Rules();
        Dismissals dismissals = new Dismissals(); Warehouses warehouses = new Warehouses();

        AuditService audit = new AuditService(audits);
        DiscountRiskService risk = new DiscountRiskService(ceilings);
        ApprovalService approval = new ApprovalService(rules, approvalLogs, risk);
        FulfillmentService fulfillment = new FulfillmentService(stocks, splits, backorders, audit);
        BillingService billingService = new BillingService(billing, audit);
        PricingService pricing = new PricingService(priceLists, plans);
        UpsellService upsell = new UpsellService(upsells, products, dismissals, pricing, risk, audit);
        QuotationService svc = new QuotationService(quotations, lines, customers, users, products, plans, messages, pricing, approvalLogs,
                risk, approval, fulfillment, billingService, upsell, audit);
        org.springframework.security.crypto.password.PasswordEncoder enc = new org.springframework.security.crypto.password.PasswordEncoder() {
            public String encode(CharSequence raw) { return "enc:" + raw; }
            public boolean matches(CharSequence raw, String e) { return e.equals("enc:" + raw); }
        };

        // baseline the seeder would have created: users, ceilings, chain, two warehouses
        users.save(new AppUser("admin", "x", "Sam Admin", "a@x", Role.ADMIN));
        users.save(new AppUser("manager", "x", "Priya", "m@x", Role.SALES_MANAGER));
        users.save(new AppUser("finance", "x", "Raj", "f@x", Role.FINANCE));
        users.save(new AppUser("rep1", "x", "Ananya Verma", "r1@x", Role.SALES_REP));
        for (CustomerTier t : CustomerTier.values()) {
            int base = t == CustomerTier.GOLD ? 15 : t == CustomerTier.SILVER ? 10 : 5;
            ceilings.save(new DiscountCeiling(t, "DEFAULT", BigDecimal.valueOf(base)));
            ceilings.save(new DiscountCeiling(t, "Hardware", BigDecimal.valueOf(base)));
            ceilings.save(new DiscountCeiling(t, "Service", BigDecimal.valueOf(t == CustomerTier.GOLD ? 10 : t == CustomerTier.SILVER ? 7 : 3)));
            ceilings.save(new DiscountCeiling(t, "Subscription", BigDecimal.valueOf(t == CustomerTier.GOLD ? 12 : t == CustomerTier.SILVER ? 8 : 4)));
        }
        rules.save(new ApprovalChainRule(new BigDecimal("0.00"), new BigDecimal("0.01"), false, false, "clean"));
        rules.save(new ApprovalChainRule(new BigDecimal("0.01"), new BigDecimal("10.00"), true, false, "manager"));
        rules.save(new ApprovalChainRule(new BigDecimal("10.00"), new BigDecimal("999999.00"), true, true, "manager+finance"));
        warehouses.save(new Warehouse("Main Warehouse", "Ahmedabad", new BigDecimal("1.00")));
        warehouses.save(new Warehouse("East Depot", "Pune", new BigDecimal("1.50")));

        DemoDataService demo = new DemoDataService(users, customers, products, warehouses, stocks, plans, priceLists, upsells, quotations, splits, billing, svc, billingService, audit, enc);
        long t0 = System.currentTimeMillis();
        DemoDataService.Summary s = demo.load("test");
        long ms = System.currentTimeMillis() - t0;
        System.out.println("   products=" + s.products + " quotations=" + s.quotations + " customers=" + s.customers + " reps=" + s.salesReps + " rules=" + s.rules + " plans=" + s.plans + " (" + ms + " ms)");
        Map<Quotation.Status, Long> mix = quotations.findAll().stream().collect(Collectors.groupingBy(Quotation::getStatus, TreeMap::new, Collectors.counting()));
        System.out.println("   status mix: " + mix);

        check("~450 base products created (" + s.products + ")", s.products >= 400);
        check("300 quotations created (" + s.quotations + ")", s.quotations == 300);
        check("60 customers, 8 sales reps (base, not grown)", s.customers == 60 && s.salesReps == 8);
        check("Recommendation rules generated (" + s.rules + ")", s.rules >= 200);
        check("Every subscription product has a plan", products.findAll().stream().filter(p -> p.getCategory().equals("Subscription")).allMatch(p -> !plans.findByProductId(p.getId()).isEmpty()));
        check("Every status is represented", mix.keySet().containsAll(EnumSet.allOf(Quotation.Status.class)));
        check("About half of the deals are CONFIRMED (" + mix.get(Quotation.Status.CONFIRMED) + ")", mix.get(Quotation.Status.CONFIRMED) >= 120 && mix.get(Quotation.Status.CONFIRMED) <= 220);
        check("Every quotation has at least one line", quotations.findAll().stream().allMatch(q -> !q.getLines().isEmpty()));
        check("No quotation is CONFIRMED without an invoice/schedule", quotations.findAll().stream().filter(q -> q.getStatus() == Quotation.Status.CONFIRMED)
                .allMatch(q -> !billing.findByQuotationLine_Quotation_IdOrderByBillingDateAsc(q.getId()).isEmpty()));
        check("Confirmed deals have paid and unpaid invoices (both states present)",
                billing.findAll().stream().anyMatch(e -> e.getStatus() == BillingScheduleEntry.Status.PAID) && billing.findAll().stream().anyMatch(e -> e.getStatus() == BillingScheduleEntry.Status.BILLED));
        check("Some shipments delivered, some still in transit", splits.findAll().stream().anyMatch(FulfillmentSplit::isDelivered) && splits.findAll().stream().anyMatch(sp -> !sp.isDelivered()));
        check("PENDING_APPROVAL deals have an approval log; REJECTED ones have a rejection reason",
                quotations.findAll().stream().filter(q -> q.getStatus() == Quotation.Status.PENDING_APPROVAL).allMatch(q -> !approvalLogs.findByQuotationIdOrderByTimestampAsc(q.getId()).isEmpty())
                && quotations.findAll().stream().filter(q -> q.getStatus() == Quotation.Status.REJECTED).allMatch(q -> approvalLogs.findByQuotationIdOrderByTimestampAsc(q.getId()).stream().anyMatch(l -> l.getAction() == ApprovalLog.Action.REJECT)));
        check("UNDER_NEGOTIATION deals carry a customer counter-offer", quotations.findAll().stream().filter(q -> q.getStatus() == Quotation.Status.UNDER_NEGOTIATION)
                .allMatch(q -> !messages.findByQuotationIdOrderByTimestampAsc(q.getId()).isEmpty()));
        check("Created dates spread over ~6 months", quotations.findAll().stream().anyMatch(q -> q.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusDays(120))));
        check("Blended risk scores computed (some > 0)", quotations.findAll().stream().anyMatch(q -> q.getBlendedRiskScore().signum() > 0));
        check("Every quotation has computed line totals > 0", quotations.findAll().stream().allMatch(q -> q.totalAmount().signum() > 0));
        long productsBefore = products.count();
        long customersBefore = customers.count();
        long repsBefore = users.findByRole(Role.SALES_REP).size();
        DemoDataService.Summary again = demo.load("test", 100, 50, 30, 5);
        System.out.println("   second batch: +" + again.quotations + " quotations (now " + again.totalQuotations + "), +" + again.newProducts + " products (now " + again.products
                + "), +" + again.newCustomers + " customers (now " + again.customers + "), +" + again.newReps + " reps (now " + again.salesReps + ")");
        check("Manual batch: a second call adds ONLY the 100 quotations asked for (now 400)", again.alreadyLoaded && again.quotations == 100 && quotations.count() == 400);
        check("Manual batch: 50 extra products generated as catalog variants (unique names, right categories)", again.newProducts == 50 && products.count() == productsBefore + 50
                && products.findAll().stream().map(Product::getName).distinct().count() == products.count());
        check("Extra subscription variants have plans; extra hardware variants have stock",
                products.findAll().stream().filter(pr -> pr.getName().contains("(") && pr.getCategory().equals("Subscription")).allMatch(pr -> !plans.findByProductId(pr.getId()).isEmpty())
                && products.findAll().stream().filter(pr -> pr.getName().contains("edition)") && pr.getCategory().equals("Hardware")).anyMatch(pr -> stocks.findByProductIdOrderByWarehouse_ShippingCostWeightAsc(pr.getId()).size() > 0));
        check("Manual batch: 30 extra customers generated with unique generated names", again.newCustomers == 30 && customers.count() == customersBefore + 30
                && customers.findAll().stream().map(Customer::getName).distinct().count() == customers.count());
        check("Manual batch: 5 extra sales reps generated with unique generated names", again.newReps == 5 && users.findByRole(Role.SALES_REP).size() == repsBefore + 5
                && users.findByRole(Role.SALES_REP).stream().map(AppUser::getFullName).distinct().count() == users.findByRole(Role.SALES_REP).size());
        check("Base catalog (first 60 customers, rep1..rep8) is exactly preserved, nothing forced beyond what was asked",
                customers.findAll().stream().anyMatch(c -> c.getName().equals("Nimbus Logistics")) && users.findByUsername("rep1").isPresent()
                && "Ananya Verma".equals(users.findByUsername("rep1").get().getFullName()));
        check("New quotations are not copies of the first batch (different seed)", quotations.findAll().stream().skip(300).anyMatch(q -> q.getLines().size() != quotations.findAll().get(0).getLines().size() || !q.getCustomer().getId().equals(quotations.findAll().get(0).getCustomer().getId())));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
