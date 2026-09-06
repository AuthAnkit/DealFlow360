import com.dealflow360.model.*;
import com.dealflow360.repository.*;
import com.dealflow360.service.ApprovalService;
import com.dealflow360.service.DiscountRiskService;

import java.math.BigDecimal;
import java.util.*;

/**
 * Standalone reproduction of the reported bug ("over-ceiling discount is not going to manager,
 * it gets corrected/pushed through by the sales rep") and proof that the fix in
 * QuotationService.confirmQuotation + ApprovalService closes it, using the REAL
 * DiscountRiskService/ApprovalService classes (not reimplemented logic) with fake in-memory repos.
 */
public class ApprovalBugVerify {

    // ---- fake repos ----
    static class FakeCeilingRepo implements DiscountCeilingRepository {
        Map<String, DiscountCeiling> byKey = new HashMap<>();
        void put(CustomerTier tier, String category, String pct) { byKey.put(tier + "|" + category, new DiscountCeiling(tier, category, new BigDecimal(pct))); }
        public Optional<DiscountCeiling> findByTierAndCategory(CustomerTier tier, String category) { return Optional.ofNullable(byKey.get(tier + "|" + category)); }
        public List<DiscountCeiling> findByTier(CustomerTier tier) { return new ArrayList<>(); }
        public DiscountCeiling save(DiscountCeiling e) { return e; }
        public Optional<DiscountCeiling> findById(Long id) { return Optional.empty(); }
        public List<DiscountCeiling> findAll() { return new ArrayList<>(); }
        public void deleteById(Long id) {}
        public void delete(DiscountCeiling e) {}
        public long count() { return 0; }
        public boolean existsById(Long id) { return false; }
    }

    static class FakeRuleRepo implements ApprovalChainRuleRepository {
        List<ApprovalChainRule> rules = new ArrayList<>();
        public List<ApprovalChainRule> findAllByOrderByMinRiskScoreAsc() {
            rules.sort(Comparator.comparing(ApprovalChainRule::getMinRiskScore));
            return rules;
        }
        public ApprovalChainRule save(ApprovalChainRule e) { rules.add(e); return e; }
        public Optional<ApprovalChainRule> findById(Long id) { return Optional.empty(); }
        public List<ApprovalChainRule> findAll() { return rules; }
        public void deleteById(Long id) {}
        public void delete(ApprovalChainRule e) {}
        public long count() { return rules.size(); }
        public boolean existsById(Long id) { return false; }
    }

    static class FakeLogRepo implements ApprovalLogRepository {
        List<ApprovalLog> logs = new ArrayList<>();
        public List<ApprovalLog> findByQuotationIdOrderByTimestampAsc(Long id) { return logs; }
        public ApprovalLog save(ApprovalLog e) { logs.add(e); return e; }
        public Optional<ApprovalLog> findById(Long id) { return Optional.empty(); }
        public List<ApprovalLog> findAll() { return logs; }
        public void deleteById(Long id) {}
        public void delete(ApprovalLog e) {}
        public long count() { return logs.size(); }
        public boolean existsById(Long id) { return false; }
    }

    static int passed = 0, failed = 0;
    static void check(String label, boolean condition) {
        if (condition) { System.out.println("PASS - " + label); passed++; }
        else { System.out.println("FAIL - " + label); failed++; }
    }

    // Mirrors the exact branch now in QuotationService.confirmQuotation (kept in sync by hand for
    // this standalone check, since confirmQuotation itself needs the full QuotationService wiring).
    static boolean tryConfirm(Quotation quotation, DiscountRiskService riskService, ApprovalService approvalService, String actor) {
        if (quotation.getStatus() != Quotation.Status.APPROVED && quotation.getStatus() != Quotation.Status.UNDER_NEGOTIATION) {
            throw new IllegalStateException("must be APPROVED/UNDER_NEGOTIATION");
        }
        BigDecimal score = riskService.blendedRiskScore(quotation);
        quotation.setBlendedRiskScore(score);
        if (score.compareTo(quotation.getApprovedRiskScore()) > 0) {
            approvalService.evaluateAndRoute(quotation, actor);
            if (quotation.getStatus() != Quotation.Status.APPROVED) {
                return false; // sent back for approval instead of confirming
            }
        }
        quotation.setStatus(Quotation.Status.CONFIRMED);
        return true;
    }

    public static void main(String[] args) {
        FakeCeilingRepo ceilings = new FakeCeilingRepo();
        ceilings.put(CustomerTier.GOLD, "Service", "10.00");
        ceilings.put(CustomerTier.GOLD, DiscountCeiling.DEFAULT_CATEGORY, "15.00");

        FakeRuleRepo rules = new FakeRuleRepo();
        rules.save(new ApprovalChainRule(new BigDecimal("0.00"), new BigDecimal("0.01"), false, false, "clean"));
        rules.save(new ApprovalChainRule(new BigDecimal("0.01"), new BigDecimal("10.00"), true, false, "manager"));
        rules.save(new ApprovalChainRule(new BigDecimal("10.00"), new BigDecimal("999999.00"), true, true, "manager+finance"));

        DiscountRiskService riskService = new DiscountRiskService(ceilings);
        ApprovalService approvalService = new ApprovalService(rules, new FakeLogRepo(), riskService);

        Customer customer = new Customer();
        customer.setTier(CustomerTier.GOLD);

        Product service = new Product();
        service.setCategory("Service");
        service.setPrice(new BigDecimal("300.00"));

        QuotationLine line = new QuotationLine();
        line.setProduct(service);
        line.setQuantity(2);
        line.setUnitPrice(service.getPrice());
        line.setDiscountPercent(new BigDecimal("25.00")); // 25% on a Service line, ceiling is 10% -> 15 point overage

        Quotation quotation = new Quotation();
        quotation.setCustomer(customer);
        quotation.getLines().add(line);
        quotation.setStatus(Quotation.Status.DRAFT);

        // ---- Scenario A: the reported exploit - a rep bypasses the whole submit step. A DRAFT
        // quotation with an over-ceiling line gets manually flipped to UNDER_NEGOTIATION (e.g. by
        // leaving a comment, before this session's addNegotiationMessage restriction existed) and the
        // rep calls confirm directly, never having gone through Manager.
        quotation.setStatus(Quotation.Status.UNDER_NEGOTIATION);
        boolean confirmedA = tryConfirm(quotation, riskService, approvalService, "rep1");
        check("Scenario A (never submitted, over ceiling): confirm is REFUSED", !confirmedA);
        check("Scenario A: quotation was instead routed to PENDING_APPROVAL", quotation.getStatus() == Quotation.Status.PENDING_APPROVAL);
        check("Scenario A: routed to Manager+Finance (score 15 >= 10)", quotation.getCurrentApprovalStep() == Quotation.ApprovalStep.MANAGER);

        // ---- Now walk it through REAL approval: Manager approves, then Finance approves.
        approvalService.approve(quotation, Role.SALES_MANAGER, "manager", "ok");
        check("After Manager approval, still waiting on Finance", quotation.getStatus() == Quotation.Status.PENDING_APPROVAL && quotation.getCurrentApprovalStep() == Quotation.ApprovalStep.FINANCE);
        approvalService.approve(quotation, Role.FINANCE, "finance", "ok");
        check("After Finance approval, quotation is APPROVED", quotation.getStatus() == Quotation.Status.APPROVED);
        check("approvedRiskScore recorded as 15.00", quotation.getApprovedRiskScore().compareTo(new BigDecimal("15.00")) == 0);

        // ---- Scenario B: legitimately approved deal confirms normally (no needless re-approval).
        boolean confirmedB = tryConfirm(quotation, riskService, approvalService, "rep1");
        check("Scenario B (properly approved, unchanged): confirm SUCCEEDS", confirmedB);
        check("Scenario B: status is CONFIRMED", quotation.getStatus() == Quotation.Status.CONFIRMED);

        // ---- Scenario C: a deal that was approved, then re-opened and negotiated to an even bigger
        // discount (the "corrected by sales rep" pattern also covers post-approval renegotiation).
        quotation.setStatus(Quotation.Status.APPROVED); // simulate re-opening for another round
        line.setDiscountPercent(new BigDecimal("40.00")); // now 30 points over ceiling
        quotation.setStatus(Quotation.Status.UNDER_NEGOTIATION);
        boolean confirmedC = tryConfirm(quotation, riskService, approvalService, "rep1");
        check("Scenario C (discount grew after approval): confirm is REFUSED", !confirmedC);
        check("Scenario C: routed back to PENDING_APPROVAL", quotation.getStatus() == Quotation.Status.PENDING_APPROVAL);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
