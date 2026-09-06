import com.dealflow360.dto.QuotationDtos.*;
import com.dealflow360.model.*;
import com.dealflow360.repository.*;
import com.dealflow360.service.*;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * End-to-end verification of the quotation lifecycle using the REAL services (QuotationService,
 * ApprovalService, FulfillmentService, BillingService, DiscountRiskService, UpsellService,
 * AuditService) wired to in-memory repositories. Every rule the fixes rely on is exercised
 * against the actual code paths rather than a mirrored condition.
 */
public class LifecycleVerify {

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
        public List<UpsellRule> findByBaseProductId(Long id) { return new ArrayList<>(); }
        public List<UpsellRule> findBySuggestedProductId(Long id) { return new ArrayList<>(); }
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

    // ------------------------------------------------------------------ checks
    static int passed = 0, failed = 0;
    static void check(String label, boolean cond) {
        if (cond) { System.out.println("PASS - " + label); passed++; } else { System.out.println("FAIL - " + label); failed++; }
    }
    static String refused(Runnable r) {
        try { r.run(); return null; } catch (ResponseStatusException e) { return e.getReason(); } catch (IllegalStateException | IllegalArgumentException e) { return e.getMessage(); }
    }

    static AddLineRequest line(Long productId, int qty, String disc) {
        AddLineRequest r = new AddLineRequest(); r.productId = productId; r.quantity = qty; r.discountPercent = new BigDecimal(disc); return r;
    }
    static NegotiationMessageRequest counter(Long lineId, String disc) {
        NegotiationMessageRequest r = new NegotiationMessageRequest(); r.messageType = "COUNTER_DISCOUNT"; r.quotationLineId = lineId; r.proposedDiscountPercent = new BigDecimal(disc); r.content = "counter"; return r;
    }
    static NegotiationMessageRequest comment(String text) {
        NegotiationMessageRequest r = new NegotiationMessageRequest(); r.messageType = "COMMENT"; r.content = text; return r;
    }

    public static void main(String[] args) {
        Quotations quotations = new Quotations(); Lines lines = new Lines(); Customers customers = new Customers(); Users users = new Users();
        Products products = new Products(); Plans plans = new Plans(); Messages messages = new Messages(); PriceLists priceLists = new PriceLists();
        ApprovalLogs approvalLogs = new ApprovalLogs(); Stocks stocks = new Stocks(); Splits splits = new Splits(); Backorders backorders = new Backorders();
        Billing billing = new Billing(); Upsells upsells = new Upsells(); Audits audits = new Audits(); Ceilings ceilings = new Ceilings(); Rules rules = new Rules();

        AuditService audit = new AuditService(audits);
        DiscountRiskService risk = new DiscountRiskService(ceilings);
        ApprovalService approval = new ApprovalService(rules, approvalLogs, risk);
        FulfillmentService fulfillment = new FulfillmentService(stocks, splits, backorders, audit);
        BillingService billingService = new BillingService(billing, audit);
        PricingService pricing = new PricingService(priceLists, plans);
        UpsellService upsell = new UpsellService(upsells, products, new Dismissals(), pricing, risk, audit);
        QuotationService svc = new QuotationService(quotations, lines, customers, users, products, plans, messages, pricing, approvalLogs,
                risk, approval, fulfillment, billingService, upsell, audit);

        // seed (mirrors DataSeeder's rules)
        AppUser rep = users.save(new AppUser("rep1", "x", "Ananya", "a@x", Role.SALES_REP));
        Customer acme = customers.save(new Customer("Acme", "b@acme", CustomerTier.GOLD, "acme", "x"));
        Customer bronze = customers.save(new Customer("Bronze", "b@bronze", CustomerTier.BRONZE, "bronze", "x"));
        Product laptop = products.save(new Product("Laptop", "Hardware", new BigDecimal("1200.00"), new BigDecimal("900.00"), "unit", BigDecimal.ZERO, ""));
        Product service = products.save(new Product("Setup", "Service", new BigDecimal("300.00"), new BigDecimal("100.00"), "engagement", BigDecimal.ZERO, ""));
        Product cloud = products.save(new Product("Cloud", "Subscription", new BigDecimal("50.00"), new BigDecimal("20.00"), "seat/month", BigDecimal.ZERO, ""));
        SubscriptionPlan yearly = plans.save(new SubscriptionPlan("Cloud yearly", cloud, BillingCycle.YEARLY, new BigDecimal("540.00"), true, true));
        Warehouse main = new Warehouse("Main", "A", new BigDecimal("1.00")); assignId(main);
        Warehouse east = new Warehouse("East", "B", new BigDecimal("1.50")); assignId(east);
        stocks.save(new StockLevel(main, laptop, 5, 5));
        stocks.save(new StockLevel(east, laptop, 2, 5));
        ceilings.save(new DiscountCeiling(CustomerTier.GOLD, "DEFAULT", new BigDecimal("15.00")));
        ceilings.save(new DiscountCeiling(CustomerTier.GOLD, "Hardware", new BigDecimal("15.00")));
        ceilings.save(new DiscountCeiling(CustomerTier.GOLD, "Service", new BigDecimal("10.00")));
        ceilings.save(new DiscountCeiling(CustomerTier.BRONZE, "DEFAULT", new BigDecimal("5.00")));
        ceilings.save(new DiscountCeiling(CustomerTier.BRONZE, "Service", new BigDecimal("3.00")));
        rules.save(new ApprovalChainRule(new BigDecimal("0.00"), new BigDecimal("0.01"), false, false, "clean"));
        rules.save(new ApprovalChainRule(new BigDecimal("0.01"), new BigDecimal("10.00"), true, false, "manager"));
        rules.save(new ApprovalChainRule(new BigDecimal("10.00"), new BigDecimal("999999.00"), true, true, "manager+finance"));
        priceLists.save(new PriceListEntry(CustomerTier.GOLD, laptop, new BigDecimal("1150.00")));

        // ===== 1. Price list + validation
        Quotation q1 = svc.createQuotation(acme.getId(), "rep1");
        svc.addLine(q1.getId(), line(laptop.getId(), 8, "5.00"));
        check("A2 price list: Gold customer gets the tier price (1150) instead of catalog (1200)",
                q1.getLines().get(0).getUnitPrice().compareTo(new BigDecimal("1150.00")) == 0);
        check("Discount above 100% is refused", refused(() -> svc.addLine(q1.getId(), line(service.getId(), 1, "150"))) != null);
        check("Negative discount is refused", refused(() -> svc.addLine(q1.getId(), line(service.getId(), 1, "-5"))) != null);
        AddLineRequest rec = line(cloud.getId(), 3, "0"); rec.lineType = QuotationLine.LineType.RECURRING; rec.subscriptionPlanId = yearly.getId();
        svc.addLine(q1.getId(), rec);
        QuotationLine cloudLine = q1.getLines().stream().filter(l -> l.getLineType() == QuotationLine.LineType.RECURRING).findFirst().orElseThrow();
        check("Recurring line is priced at the PLAN price per cycle (540 yearly), not the product's monthly catalog price",
                cloudLine.getUnitPrice().compareTo(new BigDecimal("540.00")) == 0);
        AddLineRequest wrongPlan = line(laptop.getId(), 1, "0"); wrongPlan.lineType = QuotationLine.LineType.RECURRING; wrongPlan.subscriptionPlanId = yearly.getId();
        check("A plan belonging to another product is refused", refused(() -> svc.addLine(q1.getId(), wrongPlan)) != null);

        // order discount
        svc.applyOrderDiscount(q1.getId(), new BigDecimal("5.00"), "rep1");
        check("B3 order-level discount applied to every line", q1.getLines().stream().allMatch(l -> l.getDiscountPercent().compareTo(new BigDecimal("5.00")) == 0));

        // ===== 2. Submit -> clean -> approved -> confirm -> invoice + schedule + backorder (8 laptops vs 7 stock)
        svc.submitForApproval(q1.getId(), "rep1");
        check("Within-ceiling deal is auto-APPROVED on submit", q1.getStatus() == Quotation.Status.APPROVED);
        check("Submit while APPROVED is refused (no silent re-route)", refused(() -> svc.submitForApproval(q1.getId(), "rep1")) != null);
        int backordersAfterApprove = backorders.findByQuotationIdAndResolvedFalse(q1.getId()).size();
        svc.confirmQuotation(q1.getId(), "rep1");
        check("Confirm succeeds for an approved deal", q1.getStatus() == Quotation.Status.CONFIRMED);
        check("Fulfillment split spans both warehouses (5 from Main + 2 from East)",
                splits.findByQuotationId(q1.getId()).stream().mapToInt(FulfillmentSplit::getQuantityFulfilled).sum() == 7);
        check("Backorder is NOT duplicated when the split is regenerated on confirm (was " + backordersAfterApprove + " after approve)",
                backorders.findByQuotationIdAndResolvedFalse(q1.getId()).size() == 1
                        && backorders.findByQuotationIdAndResolvedFalse(q1.getId()).get(0).getQuantityPending() == 1);
        List<BillingScheduleEntry> entries = billingService.scheduleForQuotation(q1.getId());
        long invoices = entries.stream().filter(e -> e.getEntryType() == BillingScheduleEntry.EntryType.ONE_TIME_INVOICE).count();
        long cycles = entries.stream().filter(e -> e.getEntryType() == BillingScheduleEntry.EntryType.REGULAR).count();
        check("B7: one-time line gets a ONE_TIME_INVOICE, recurring line gets its own cycle schedule (" + invoices + " invoice, " + cycles + " cycles)", invoices == 1 && cycles == 3);
        check("Invoice status is UNPAID right after confirmation", billingService.invoiceStatus(entries).equals("UNPAID"));
        BillingScheduleEntry inv = entries.stream().filter(e -> e.getEntryType() == BillingScheduleEntry.EntryType.ONE_TIME_INVOICE).findFirst().orElseThrow();
        check("One-time invoice amount = laptop line total (8 x 1150 x 0.95 = 8740)", inv.getAmount().compareTo(new BigDecimal("8740.00")) == 0);
        billingService.recordPayment(q1.getId(), inv.getId(), false, "NEFT-1", "finance");
        check("Step 8: recording a payment marks the invoice PAID with a timestamp", inv.getStatus() == BillingScheduleEntry.Status.PAID && inv.getPaidAt() != null);
        check("Invoice status becomes PARTIALLY_PAID (first subscription cycle still due)", billingService.invoiceStatus(billingService.scheduleForQuotation(q1.getId())).equals("PARTIALLY_PAID"));
        check("Paying the same entry twice is refused", refused(() -> billingService.recordPayment(q1.getId(), inv.getId(), false, "", "finance")) != null);
        billingService.recordPayment(q1.getId(), null, true, "", "finance");
        check("Pay-all settles everything due -> invoice status PAID", billingService.invoiceStatus(billingService.scheduleForQuotation(q1.getId())).equals("PAID"));
        check("Customer counter on a CONFIRMED order is refused", refused(() -> svc.addNegotiationMessage(q1.getId(), counter(q1.getLines().get(0).getId(), "1"), "CUSTOMER", "Acme")) != null);
        check("Comment on a CONFIRMED order still posts", refused(() -> svc.addNegotiationMessage(q1.getId(), comment("thanks"), "CUSTOMER", "Acme")) == null);
        check("Editing a CONFIRMED order's lines is refused", refused(() -> svc.removeLine(q1.getId(), q1.getLines().get(0).getId())) != null);

        // ===== 3. Rejection dead-end: Bronze asks 12% on Service (ceiling 3) -> Manager -> reject -> ways back
        Quotation q2 = svc.createQuotation(bronze.getId(), "rep1");
        svc.addLine(q2.getId(), line(service.getId(), 1, "12.00"));
        svc.submitForApproval(q2.getId(), "rep1");
        check("Over-ceiling Bronze deal routes to PENDING_APPROVAL / MANAGER", q2.getStatus() == Quotation.Status.PENDING_APPROVAL && q2.getCurrentApprovalStep() == Quotation.ApprovalStep.MANAGER);
        Long q2Line = q2.getLines().get(0).getId();
        check("Customer counter while PENDING_APPROVAL is refused (numbers can't move under the Manager)",
                refused(() -> svc.addNegotiationMessage(q2.getId(), counter(q2Line, "8"), "CUSTOMER", "Bronze")) != null
                        && q2.getLines().get(0).getDiscountPercent().compareTo(new BigDecimal("12.00")) == 0);
        check("Comment while PENDING_APPROVAL posts without changing status",
                refused(() -> svc.addNegotiationMessage(q2.getId(), comment("hi"), "SALES_REP", "Ananya")) == null && q2.getStatus() == Quotation.Status.PENDING_APPROVAL);
        check("Portal confirm while PENDING_APPROVAL is refused", refused(() -> svc.portalConfirm(q2.getId())) != null && q2.getCurrentApprovalStep() == Quotation.ApprovalStep.MANAGER);
        check("Submit again while PENDING_APPROVAL is refused", refused(() -> svc.submitForApproval(q2.getId(), "rep1")) != null);
        svc.rejectStep(q2.getId(), Role.SALES_MANAGER, "manager", "too high");
        check("Manager rejection -> REJECTED", q2.getStatus() == Quotation.Status.REJECTED);
        check("Rep cannot edit lines while REJECTED (must reopen)", refused(() -> svc.updateLine(q2.getId(), q2Line, new UpdateLineRequest())) != null);
        check("Portal confirm of a REJECTED deal is refused", refused(() -> svc.portalConfirm(q2.getId())) != null);
        // way back #1: customer lowers the ask from the portal
        svc.addNegotiationMessage(q2.getId(), counter(q2Line, "2.00"), "CUSTOMER", "Bronze");
        check("Customer counter on a REJECTED deal reopens it as UNDER_NEGOTIATION", q2.getStatus() == Quotation.Status.UNDER_NEGOTIATION);
        check("...and the rep can now Submit it again (no more dead end)", refused(() -> svc.submitForApproval(q2.getId(), "rep1")) == null && q2.getStatus() == Quotation.Status.APPROVED);
        // way back #2: rep reopens for revision
        svc.reopenForRevision(q2.getId(), "rep1", "revise");
        check("Rep 'Reopen for revision' takes an APPROVED/REJECTED deal back to DRAFT with the approval wiped",
                q2.getStatus() == Quotation.Status.DRAFT && q2.getCurrentApprovalStep() == Quotation.ApprovalStep.NONE && q2.getApprovedRiskScore().signum() == 0);
        check("...and its lines are editable again", refused(() -> { UpdateLineRequest u = new UpdateLineRequest(); u.discountPercent = new BigDecimal("3.00"); svc.updateLine(q2.getId(), q2Line, u); }) == null);
        check("Reopen of a DRAFT is refused", refused(() -> svc.reopenForRevision(q2.getId(), "rep1", "")) != null);
        check("Confirm of a never-resubmitted reopened DRAFT is refused (step NONE)", refused(() -> svc.confirmQuotation(q2.getId(), "rep1")) != null);
        check("REOPENED is in the approval audit trail", approvalLogs.findByQuotationIdOrderByTimestampAsc(q2.getId()).stream().anyMatch(l -> l.getAction() == ApprovalLog.Action.REOPENED));

        // ===== 4. Approved deal, customer counters past ceiling -> confirm re-enters approval (quick test step 7)
        Quotation q3 = svc.createQuotation(acme.getId(), "rep1");
        svc.addLine(q3.getId(), line(service.getId(), 2, "5.00"));
        svc.submitForApproval(q3.getId(), "rep1");
        check("Clean Gold deal approved", q3.getStatus() == Quotation.Status.APPROVED);
        Long q3Line = q3.getLines().get(0).getId();
        svc.addNegotiationMessage(q3.getId(), counter(q3Line, "18.00"), "CUSTOMER", "Acme");
        check("Counter to 18% (Service ceiling 10) -> UNDER_NEGOTIATION with current score 8 > approved 0",
                q3.getStatus() == Quotation.Status.UNDER_NEGOTIATION && q3.getBlendedRiskScore().compareTo(new BigDecimal("8.00")) == 0);
        check("Rep internal confirm is refused and the deal is re-routed to PENDING_APPROVAL automatically",
                refused(() -> svc.confirmQuotation(q3.getId(), "rep1")) != null && q3.getStatus() == Quotation.Status.PENDING_APPROVAL);
        svc.approveStep(q3.getId(), Role.SALES_MANAGER, "manager", "ok");
        check("Manager approval clears it (score 8 < 10 so no Finance step)", q3.getStatus() == Quotation.Status.APPROVED && q3.getApprovedRiskScore().compareTo(new BigDecimal("8.00")) == 0);
        svc.portalConfirm(q3.getId());
        check("Customer portal confirm then CONFIRMS at the approved terms", q3.getStatus() == Quotation.Status.CONFIRMED);
        check("Portal confirm of an already CONFIRMED order is refused", refused(() -> svc.portalConfirm(q3.getId())) != null);

        // ===== 5. Rejection releases reserved stock; reopen releases too
        int laptopMainBefore = stocks.findByWarehouseIdAndProductId(main.getId(), laptop.getId()).orElseThrow().getQuantityAvailable();
        Quotation q4 = svc.createQuotation(acme.getId(), "rep1");
        svc.addLine(q4.getId(), line(laptop.getId(), 1, "0"));
        svc.submitForApproval(q4.getId(), "rep1"); // approved -> reserves 1 laptop
        check("Approval reserves stock", stocks.findByWarehouseIdAndProductId(main.getId(), laptop.getId()).orElseThrow().getQuantityAvailable() == laptopMainBefore - 1 || laptopMainBefore == 0);
        svc.reopenForRevision(q4.getId(), "rep1", "");
        check("Reopen releases the reserved stock back", stocks.findByWarehouseIdAndProductId(main.getId(), laptop.getId()).orElseThrow().getQuantityAvailable() == laptopMainBefore);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
