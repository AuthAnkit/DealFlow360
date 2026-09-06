import com.dealflow360.model.*;
import com.dealflow360.repository.*;
import com.dealflow360.service.ApprovalService;
import com.dealflow360.service.DiscountRiskService;

import java.math.BigDecimal;
import java.util.*;

/**
 * Verifies the two follow-up fixes: (1) a rep can edit/remove lines on a customer's self-service
 * request even after its note flips it to UNDER_NEGOTIATION, and (2) a customer (or rep) cannot
 * confirm an order that has never actually been submitted for approval - closing the "order
 * confirms itself the moment the customer clicks it" gap. Mirrors the exact branch conditions now
 * in QuotationService (requireEditable / the currentApprovalStep==NONE guard / addNegotiationMessage's
 * status transition) since constructing the full QuotationService needs ~12 repository dependencies;
 * ApprovalService/DiscountRiskService/the Quotation model itself are the real classes.
 */
public class SelfServiceFlowVerify {

    static class FakeCeilingRepo implements DiscountCeilingRepository {
        public Optional<DiscountCeiling> findByTierAndCategory(CustomerTier t, String c) { return Optional.empty(); }
        public List<DiscountCeiling> findByTier(CustomerTier t) { return new ArrayList<>(); }
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
        public List<ApprovalChainRule> findAllByOrderByMinRiskScoreAsc() { rules.sort(Comparator.comparing(ApprovalChainRule::getMinRiskScore)); return rules; }
        public ApprovalChainRule save(ApprovalChainRule e) { rules.add(e); return e; }
        public Optional<ApprovalChainRule> findById(Long id) { return Optional.empty(); }
        public List<ApprovalChainRule> findAll() { return rules; }
        public void deleteById(Long id) {}
        public void delete(ApprovalChainRule e) {}
        public long count() { return rules.size(); }
        public boolean existsById(Long id) { return false; }
    }
    static class FakeLogRepo implements ApprovalLogRepository {
        public List<ApprovalLog> findByQuotationIdOrderByTimestampAsc(Long id) { return new ArrayList<>(); }
        public ApprovalLog save(ApprovalLog e) { return e; }
        public Optional<ApprovalLog> findById(Long id) { return Optional.empty(); }
        public List<ApprovalLog> findAll() { return new ArrayList<>(); }
        public void deleteById(Long id) {}
        public void delete(ApprovalLog e) {}
        public long count() { return 0; }
        public boolean existsById(Long id) { return false; }
    }

    static int passed = 0, failed = 0;
    static void check(String label, boolean cond) {
        if (cond) { System.out.println("PASS - " + label); passed++; }
        else { System.out.println("FAIL - " + label); failed++; }
    }

    // Mirrors QuotationService.addNegotiationMessage's status-transition rule.
    static void postNegotiationMessage(Quotation q) {
        Quotation.Status current = q.getStatus();
        if (current == Quotation.Status.DRAFT || current == Quotation.Status.APPROVED || current == Quotation.Status.UNDER_NEGOTIATION) {
            q.setStatus(Quotation.Status.UNDER_NEGOTIATION);
        }
    }

    // Mirrors QuotationService.requireEditable.
    static boolean isEditable(Quotation q) {
        return q.getStatus() == Quotation.Status.DRAFT || q.getStatus() == Quotation.Status.UNDER_NEGOTIATION;
    }

    // Mirrors the guards at the top of QuotationService.confirmQuotation.
    static boolean canConfirm(Quotation q) {
        if (q.getStatus() != Quotation.Status.APPROVED && q.getStatus() != Quotation.Status.UNDER_NEGOTIATION) return false;
        if (q.getCurrentApprovalStep() == Quotation.ApprovalStep.NONE) return false;
        return true;
    }

    public static void main(String[] args) {
        DiscountRiskService riskService = new DiscountRiskService(new FakeCeilingRepo());
        FakeRuleRepo rules = new FakeRuleRepo();
        rules.save(new ApprovalChainRule(new BigDecimal("0.00"), new BigDecimal("0.01"), false, false, "clean"));
        rules.save(new ApprovalChainRule(new BigDecimal("0.01"), new BigDecimal("999999.00"), true, false, "manager"));
        ApprovalService approvalService = new ApprovalService(rules, new FakeLogRepo(), riskService);

        Customer customer = new Customer();
        customer.setTier(CustomerTier.SILVER);
        Product mouse = new Product();
        mouse.setCategory("Hardware");
        mouse.setPrice(new BigDecimal("25.00"));

        QuotationLine line = new QuotationLine();
        line.setProduct(mouse);
        line.setQuantity(10);
        line.setUnitPrice(mouse.getPrice());
        line.setDiscountPercent(BigDecimal.ZERO);

        // ---- Step 1: a fresh self-service request, exactly as PortalController.requestQuote builds it.
        Quotation quotation = new Quotation();
        quotation.setCustomer(customer);
        quotation.getLines().add(line);
        quotation.setStatus(Quotation.Status.DRAFT); // createQuotation()
        check("Freshly created self-service request is DRAFT and editable", isEditable(quotation));

        // The customer's optional note is posted via addNegotiationMessage.
        postNegotiationMessage(quotation);
        check("Posting the note flips status to UNDER_NEGOTIATION", quotation.getStatus() == Quotation.Status.UNDER_NEGOTIATION);

        // ---- Bug being fixed #1: rep must still be able to edit/remove lines after that.
        check("Rep can still edit/remove lines after the note (bug #1 fixed)", isEditable(quotation));

        // ---- Bug being fixed #2: customer must NOT be able to confirm this yet - no rep has priced/submitted it.
        check("Customer cannot confirm an unsubmitted request (bug #2 fixed)", !canConfirm(quotation));
        check("...specifically because currentApprovalStep is still NONE", quotation.getCurrentApprovalStep() == Quotation.ApprovalStep.NONE);

        // ---- Rep now edits (e.g. removes a line) - simulate the rep removing then re-adding a line,
        // proving edits genuinely go through while UNDER_NEGOTIATION.
        quotation.getLines().remove(line);
        check("Line was actually removable", quotation.getLines().isEmpty());
        quotation.getLines().add(line);

        // ---- Rep submits for approval (0% discount -> clean -> auto-APPROVED).
        approvalService.evaluateAndRoute(quotation, "rep2");
        check("After submit, quotation is APPROVED (0% discount is clean)", quotation.getStatus() == Quotation.Status.APPROVED);
        check("currentApprovalStep is now DONE, not NONE", quotation.getCurrentApprovalStep() == Quotation.ApprovalStep.DONE);

        // ---- Now the customer CAN confirm.
        check("Customer can now confirm, after a real submit", canConfirm(quotation));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
