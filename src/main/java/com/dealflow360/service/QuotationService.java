package com.dealflow360.service;

import com.dealflow360.dto.QuotationDtos.*;
import com.dealflow360.dto.RecommendationDtos.RecommendationActionRequest;
import com.dealflow360.dto.RecommendationDtos.RecommendationPanelResponse;
import com.dealflow360.model.*;
import com.dealflow360.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full Quotation lifecycle described in the PDF
 * "5) Complete Flow (End-to-End)": build cart -> auto discount routing ->
 * approval -> warehouse fulfillment -> hybrid billing -> customer
 * negotiation -> confirm. Also owns all entity -> DTO mapping so
 * controllers stay thin and we never serialize a lazy JPA association by
 * accident.
 */
@Service
public class QuotationService {

    private final QuotationRepository quotationRepository;
    private final QuotationLineRepository quotationLineRepository;
    private final CustomerRepository customerRepository;
    private final AppUserRepository appUserRepository;
    private final ProductRepository productRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final NegotiationMessageRepository negotiationMessageRepository;
    private final PricingService pricingService;
    private final ApprovalLogRepository approvalLogRepository;

    private final DiscountRiskService discountRiskService;
    private final ApprovalService approvalService;
    private final FulfillmentService fulfillmentService;
    private final BillingService billingService;
    private final UpsellService upsellService;
    private final AuditService auditService;

    public QuotationService(QuotationRepository quotationRepository,
                             QuotationLineRepository quotationLineRepository,
                             CustomerRepository customerRepository,
                             AppUserRepository appUserRepository,
                             ProductRepository productRepository,
                             SubscriptionPlanRepository subscriptionPlanRepository,
                             NegotiationMessageRepository negotiationMessageRepository,
                             PricingService pricingService,
                             ApprovalLogRepository approvalLogRepository,
                             DiscountRiskService discountRiskService,
                             ApprovalService approvalService,
                             FulfillmentService fulfillmentService,
                             BillingService billingService,
                             UpsellService upsellService,
                             AuditService auditService) {
        this.quotationRepository = quotationRepository;
        this.quotationLineRepository = quotationLineRepository;
        this.customerRepository = customerRepository;
        this.appUserRepository = appUserRepository;
        this.productRepository = productRepository;
        this.subscriptionPlanRepository = subscriptionPlanRepository;
        this.negotiationMessageRepository = negotiationMessageRepository;
        this.pricingService = pricingService;
        this.approvalLogRepository = approvalLogRepository;
        this.discountRiskService = discountRiskService;
        this.approvalService = approvalService;
        this.fulfillmentService = fulfillmentService;
        this.billingService = billingService;
        this.upsellService = upsellService;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- create / edit

    @Transactional
    public Quotation createQuotation(Long customerId, String salesRepUsername) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        AppUser rep = appUserRepository.findByUsername(salesRepUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sales rep not found"));

        Quotation quotation = new Quotation();
        quotation.setCustomer(customer);
        quotation.setSalesRep(rep);
        quotation.setStatus(Quotation.Status.DRAFT);
        return quotationRepository.save(quotation);
    }

    @Transactional
    public Quotation addLine(Long quotationId, AddLineRequest request) {
        Quotation quotation = getEntity(quotationId);
        requireEditable(quotation);
        Product product = productRepository.findById(request.productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if (!product.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, product.getName() + " is inactive and can no longer be added to a quotation");
        }

        QuotationLine line = new QuotationLine();
        line.setQuotation(quotation);
        line.setProduct(product);
        line.setQuantity(Math.max(1, request.quantity));
        line.setDiscountPercent(validDiscount(request.discountPercent));
        line.setLineType(request.lineType == null ? QuotationLine.LineType.ONE_TIME : request.lineType);

        // Bug fix (subscriptions): a product that is sold on subscription plans is a subscription -
        // it used to be silently added as a ONE_TIME line at the catalog price whenever the caller
        // didn't flip the line type (the customer catalog never could), so it never got a billing
        // schedule. Any product with at least one plan is now always a RECURRING line, and if no
        // plan was chosen the product's default plan (monthly, else cheapest) is used.
        List<SubscriptionPlan> plans = pricingService.plansFor(product);
        if (!plans.isEmpty() && line.getLineType() == QuotationLine.LineType.ONE_TIME) {
            line.setLineType(QuotationLine.LineType.RECURRING);
        }

        if (line.getLineType() == QuotationLine.LineType.RECURRING) {
            if (request.subscriptionPlanId == null && plans.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        product.getName() + " has no subscription plan - add one under Backend Setup > Subscription Plans, or add it as a one-time line");
            }
            SubscriptionPlan plan = request.subscriptionPlanId == null
                    ? pricingService.defaultPlanFor(product, null).orElseThrow()
                    : subscriptionPlanRepository.findById(request.subscriptionPlanId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription plan not found"));
            if (plan.getProduct() != null && !plan.getProduct().getId().equals(product.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Plan \"" + plan.getName() + "\" belongs to " + plan.getProduct().getName() + ", not " + product.getName());
            }
            line.setSubscriptionPlan(plan);
            // Bug fix: a recurring line used to be priced at the product's catalog price while its
            // billing schedule charged the PLAN's price per cycle - so a quarterly/yearly plan quoted
            // one number and billed another. The quotation line now carries the plan's per-cycle price
            // (what the first invoice will actually be), keeping quote and billing consistent.
            line.setUnitPrice(plan.getPricePerCycle());
        } else {
            line.setUnitPrice(priceFor(quotation.getCustomer(), product));
        }

        quotation.getLines().add(line);
        quotation.setBlendedRiskScore(discountRiskService.blendedRiskScore(quotation));
        quotation.setUpdatedAt(LocalDateTime.now());
        return quotationRepository.save(quotation);
    }

    @Transactional
    public Quotation updateLine(Long quotationId, Long lineId, UpdateLineRequest request) {
        Quotation quotation = getEntity(quotationId);
        requireEditable(quotation);
        QuotationLine line = quotation.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        if (request.quantity != null) line.setQuantity(Math.max(1, request.quantity));
        if (request.discountPercent != null) line.setDiscountPercent(validDiscount(request.discountPercent));

        quotation.setBlendedRiskScore(discountRiskService.blendedRiskScore(quotation));
        quotation.setUpdatedAt(LocalDateTime.now());
        return quotationRepository.save(quotation);
    }

    /** PDF B3 - "Apply line level or order level discounts": one discount applied to every line at once. */
    @Transactional
    public Quotation applyOrderDiscount(Long quotationId, BigDecimal discountPercent, String actorUsername) {
        Quotation quotation = getEntity(quotationId);
        requireEditable(quotation);
        if (quotation.getLines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one line before applying an order-level discount");
        }
        BigDecimal discount = validDiscount(discountPercent);
        for (QuotationLine line : quotation.getLines()) {
            line.setDiscountPercent(discount);
        }
        quotation.setBlendedRiskScore(discountRiskService.blendedRiskScore(quotation));
        quotation.setUpdatedAt(LocalDateTime.now());
        auditService.log("Quotation", quotation.getId(), "ORDER_DISCOUNT_APPLIED", actorUsername,
                "Order-level discount of " + discount + "% applied to " + quotation.getLines().size() + " line(s)");
        return quotationRepository.save(quotation);
    }

    /** A discount is a percentage of the line: anything outside 0-100 is a typo, not a deal. */
    private BigDecimal validDiscount(BigDecimal discountPercent) {
        if (discountPercent == null) return BigDecimal.ZERO;
        if (discountPercent.compareTo(BigDecimal.ZERO) < 0 || discountPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discount must be between 0 and 100 percent");
        }
        return discountPercent;
    }

    /** PDF A2 price lists: the customer's tier price for this product if one is configured, else the catalog price. */
    public BigDecimal priceFor(Customer customer, Product product) {
        return pricingService.priceFor(customer, product);
    }

    public Quotation save(Quotation quotation) {
        return quotationRepository.save(quotation);
    }

    @Transactional
    public Quotation removeLine(Long quotationId, Long lineId) {
        Quotation quotation = getEntity(quotationId);
        requireEditable(quotation);
        quotation.getLines().removeIf(l -> l.getId().equals(lineId));
        quotation.setBlendedRiskScore(discountRiskService.blendedRiskScore(quotation));
        quotation.setUpdatedAt(LocalDateTime.now());
        return quotationRepository.save(quotation);
    }

    /**
     * Bug fix: none of add/update/removeLine ever checked the quotation's status, so a line - and
     * therefore its price and discount - could still be edited after approval or even after CONFIRMED
     * (when fulfillment/billing had already been generated from the old numbers). Editing is allowed
     * while DRAFT (building the quote, including a customer's own self-service "Browse & Request"
     * list before a rep has touched it) and while UNDER_NEGOTIATION (a rep needs to be able to
     * remove/adjust a line while going back and forth with the customer - this is exactly the state a
     * self-service request ends up in as soon as the customer's note is posted, so blocking edits
     * there would leave a rep unable to fix or remove anything on a customer's own request). It is
     * still blocked while PENDING_APPROVAL (don't let the numbers move under the Manager mid-review -
     * return it to Draft first), APPROVED (start a negotiation instead, so the change is visible to
     * the customer), REJECTED and CONFIRMED. Any change made here still goes through
     * confirmQuotation's own risk re-check before the deal can be locked in, so this can never be used
     * to sneak an over-ceiling discount past approval.
     */
    private void requireEditable(Quotation quotation) {
        if (quotation.getStatus() != Quotation.Status.DRAFT && quotation.getStatus() != Quotation.Status.UNDER_NEGOTIATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This quotation can no longer be edited directly (status: " + quotation.getStatus()
                            + ") - use negotiation, or return it to Draft for revision first");
        }
    }

    // ---------------------------------------------------------------- live recommendations (B5)

    public RecommendationPanelResponse recommendations(Long quotationId, UpsellRule.RecommendationType type) {
        return upsellService.recommendFor(getEntity(quotationId), type);
    }

    /**
     * The sales rep accepts a recommendation card. Nothing is ever applied automatically - this is the
     * explicit click. CROSS_SELL / UPSELL (mode ADD, ADD_BOTH) add the recommended product through the
     * same {@link #addLine} path a manual add uses; PRODUCT_UPGRADE (mode UPGRADE) replaces the source
     * line's product in place, preserving quantity and discount, so unit price, line total, quotation
     * total, margin, blended risk score and the resulting approval requirement are all recomputed from
     * the real product data. The audit trail (and therefore the Deal Timeline) records exactly what was
     * accepted and what it did to the numbers.
     */
    @Transactional
    public Quotation acceptRecommendation(Long quotationId, RecommendationActionRequest request, String actorUsername) {
        Quotation quotation = getEntity(quotationId);
        requireEditable(quotation);
        if (request.ruleId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ruleId is required");
        UpsellRule rule = upsellService.getRule(request.ruleId);
        if (!rule.isActive()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This recommendation rule is no longer active");
        Product recommended = rule.getSuggestedProduct();
        if (!recommended.isActive()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, recommended.getName() + " is inactive");

        // The source line: the one named in the request, else the first line carrying the rule's base product.
        QuotationLine source = quotation.getLines().stream()
                .filter(l -> request.sourceLineId == null ? l.getProduct().getId().equals(rule.getBaseProduct().getId()) : l.getId().equals(request.sourceLineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This recommendation no longer applies - " + rule.getBaseProduct().getName() + " is not on the quotation"));
        if (!source.getProduct().getId().equals(rule.getBaseProduct().getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected line does not carry " + rule.getBaseProduct().getName());
        }

        String mode = request.mode == null ? "ADD" : request.mode.trim().toUpperCase();
        BigDecimal totalBefore = quotation.totalAmount();
        BigDecimal marginBefore = UpsellService.quotationMargin(quotation);
        String what;

        if ("UPGRADE".equals(mode)) {
            if (rule.getRecommendationType() == UpsellRule.RecommendationType.CROSS_SELL) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A cross-sell recommendation is added alongside the existing product, not used as a replacement");
            }
            boolean alreadyThere = quotation.getLines().stream().anyMatch(l -> l.getProduct().getId().equals(recommended.getId()));
            if (alreadyThere) throw new ResponseStatusException(HttpStatus.CONFLICT, recommended.getName() + " is already on this quotation");
            int qty = source.getQuantity();
            String from = source.getProduct().getName();
            replaceLineProduct(quotation, source, recommended);
            what = "Sales Rep accepted " + rule.getRecommendationType() + " recommendation: " + from + " -> " + recommended.getName()
                    + " (" + qty + " unit(s) replaced in place, quantity and discount preserved)";
        } else if ("ADD".equals(mode) || "ADD_BOTH".equals(mode)) {
            boolean alreadyThere = quotation.getLines().stream().anyMatch(l -> l.getProduct().getId().equals(recommended.getId()));
            if (alreadyThere) throw new ResponseStatusException(HttpStatus.CONFLICT, recommended.getName() + " is already on this quotation");
            AddLineRequest add = new AddLineRequest();
            add.productId = recommended.getId();
            add.quantity = request.quantity != null && request.quantity > 0 ? request.quantity
                    : ("ADD_BOTH".equals(mode) ? source.getQuantity() : 1);
            add.discountPercent = BigDecimal.ZERO;
            add.lineType = QuotationLine.LineType.ONE_TIME; // addLine promotes it to RECURRING (default plan) if the product is a subscription
            quotation = addLine(quotationId, add);
            what = "Sales Rep accepted " + rule.getRecommendationType() + " recommendation: " + source.getProduct().getName() + " -> " + recommended.getName()
                    + " (added " + add.quantity + " unit(s)" + ("ADD_BOTH".equals(mode) ? ", original kept" : "") + ")";
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be ADD, ADD_BOTH or UPGRADE");
        }

        upsellService.clearDismissal(quotation, rule); // accepted beats an earlier dismiss
        quotation.setBlendedRiskScore(discountRiskService.blendedRiskScore(quotation));
        quotation.setUpdatedAt(LocalDateTime.now());
        quotation = quotationRepository.save(quotation);

        BigDecimal totalAfter = quotation.totalAmount();
        BigDecimal marginAfter = UpsellService.quotationMargin(quotation);
        ApprovalService.ApprovalRequirement requirement = approvalService.describeRequirement(quotation.getBlendedRiskScore());
        auditService.log("Quotation", quotation.getId(), "RECOMMENDATION_ACCEPTED", actorUsername,
                what + ". Total " + totalBefore + " -> " + totalAfter + ", margin " + marginBefore + " -> " + marginAfter
                        + ", risk score " + quotation.getBlendedRiskScore() + " (" + requirement.label + ")");
        return quotation;
    }

    /**
     * Replace the product on an existing line in place (PRODUCT_UPGRADE): quantity and discount are
     * kept, the unit price is re-derived for the new product (tier price, or the matching plan's
     * per-cycle price for a recurring line) - exactly what {@link #addLine} would have done for it.
     */
    private void replaceLineProduct(Quotation quotation, QuotationLine line, Product newProduct) {
        if (line.getLineType() == QuotationLine.LineType.RECURRING) {
            SubscriptionPlan plan = pricingService.defaultPlanFor(newProduct, line.getSubscriptionPlan())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            newProduct.getName() + " has no subscription plan, so a recurring line cannot be upgraded to it"));
            line.setSubscriptionPlan(plan);
            line.setUnitPrice(plan.getPricePerCycle());
        } else {
            line.setUnitPrice(pricingService.priceFor(quotation.getCustomer(), newProduct));
        }
        line.setProduct(newProduct);
    }

    @Transactional
    public void dismissRecommendation(Long quotationId, RecommendationActionRequest request, String actorUsername) {
        Quotation quotation = getEntity(quotationId);
        if (request.ruleId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ruleId is required");
        UpsellRule rule = upsellService.getRule(request.ruleId);
        Long sourceLineId = rule.getRecommendationType() == UpsellRule.RecommendationType.PRODUCT_UPGRADE ? request.sourceLineId : null;
        upsellService.dismiss(quotation, rule, sourceLineId, actorUsername);
    }

    @Transactional
    public int restoreRecommendations(Long quotationId, String actorUsername) {
        return upsellService.restoreDismissed(getEntity(quotationId), actorUsername);
    }

    // ---------------------------------------------------------------- approval routing

    /** "Confirm and move to approval, or straight to fulfillment if no approval is required." */
    @Transactional
    public Quotation submitForApproval(Long quotationId, String actorUsername) {
        Quotation quotation = getEntity(quotationId);
        if (quotation.getLines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot submit an empty quotation");
        }
        // Bug fix: submit had no status check at all. It can be (re)submitted from DRAFT, from
        // UNDER_NEGOTIATION (terms changed while talking to the customer) and from REJECTED (the rep
        // or customer lowered the discount after a rejection and wants it looked at again - this was
        // previously a dead end with no way forward). It must NOT be re-run while PENDING_APPROVAL
        // (it would reset a half-completed Manager->Finance chain back to the Manager step), on an
        // APPROVED deal (nothing changed - confirm it instead) or after CONFIRMED.
        Quotation.Status status = quotation.getStatus();
        if (status == Quotation.Status.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This quotation is already waiting for approval");
        }
        if (status == Quotation.Status.APPROVED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This quotation is already approved - confirm it, or reopen it to make changes");
        }
        if (status == Quotation.Status.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A confirmed order can no longer be submitted for approval");
        }
        approvalService.evaluateAndRoute(quotation, actorUsername);
        quotation.setUpdatedAt(LocalDateTime.now());
        quotationRepository.save(quotation);

        if (quotation.getStatus() == Quotation.Status.APPROVED) {
            // No approval needed - go straight to fulfillment suggestion.
            fulfillmentService.generateSuggestedSplit(quotation, actorUsername);
        }
        return quotation;
    }

    @Transactional
    public Quotation approveStep(Long quotationId, Role approverRole, String actorUsername, String reason) {
        Quotation quotation = getEntity(quotationId);
        approvalService.approve(quotation, approverRole, actorUsername, reason);
        quotationRepository.save(quotation);
        if (quotation.getStatus() == Quotation.Status.APPROVED) {
            fulfillmentService.generateSuggestedSplit(quotation, actorUsername);
        }
        return quotation;
    }

    @Transactional
    public Quotation rejectStep(Long quotationId, Role approverRole, String actorUsername, String reason) {
        Quotation quotation = getEntity(quotationId);
        approvalService.reject(quotation, approverRole, actorUsername, reason);
        // A rejected deal is not shipping as-is: give any reserved stock back to the warehouses.
        fulfillmentService.releaseAll(quotation, actorUsername);
        return quotationRepository.save(quotation);
    }

    /**
     * Sales rep "Reopen for revision": takes a REJECTED (or an APPROVED-but-not-yet-confirmed) quotation
     * back to DRAFT so its lines can be edited and it can be submitted again. Bug fix: after a
     * rejection the quotation was stuck - lines could not be edited (not DRAFT/UNDER_NEGOTIATION), and
     * neither the rep nor the customer had any "submit" left, so a deal the manager bounced for too
     * high a discount could never come back with a lower one. Reopening wipes the previous approval
     * (step back to NONE, cleared score reset) so the revised terms must go through the chain again,
     * and releases any stock the old approval had reserved.
     */
    @Transactional
    public Quotation reopenForRevision(Long quotationId, String actorUsername, String reason) {
        Quotation quotation = getEntity(quotationId);
        Quotation.Status status = quotation.getStatus();
        if (status == Quotation.Status.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A confirmed order cannot be reopened");
        }
        if (status == Quotation.Status.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This quotation is with the approver - ask them to return it for revision, or wait for their decision");
        }
        if (status == Quotation.Status.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This quotation is already a draft");
        }
        approvalLogRepository.save(new ApprovalLog(quotation, Role.SALES_REP, ApprovalLog.Action.REOPENED, actorUsername,
                (reason == null || reason.isBlank() ? "Reopened for revision" : reason) + " (was " + status + ")"));
        fulfillmentService.releaseAll(quotation, actorUsername);
        quotation.setStatus(Quotation.Status.DRAFT);
        quotation.setCurrentApprovalStep(Quotation.ApprovalStep.NONE);
        quotation.setApprovedRiskScore(BigDecimal.ZERO);
        quotation.setUpdatedAt(LocalDateTime.now());
        auditService.log("Quotation", quotation.getId(), "REOPENED_FOR_REVISION", actorUsername, "Reopened as Draft from " + status);
        return quotationRepository.save(quotation);
    }

    @Transactional
    public Quotation returnStep(Long quotationId, Role approverRole, String actorUsername, String reason) {
        Quotation quotation = getEntity(quotationId);
        approvalService.returnForRevision(quotation, approverRole, actorUsername, reason);
        return quotationRepository.save(quotation);
    }

    // ---------------------------------------------------------------- confirm (final)

    /** Final confirmation: locks the order in, (re)generates fulfillment, and starts billing schedules. */
    @Transactional
    public Quotation confirmQuotation(Long quotationId, String actorUsername) {
        Quotation quotation = getEntity(quotationId);
        if (quotation.getStatus() != Quotation.Status.APPROVED && quotation.getStatus() != Quotation.Status.UNDER_NEGOTIATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quotation must be APPROVED before it can be confirmed");
        }

        // Bug fix: a self-service request (or any DRAFT) can reach UNDER_NEGOTIATION purely from a
        // comment/note - with NO sales rep ever having priced, reviewed or submitted it - because that
        // status is also legitimately reached from an already-approved deal being renegotiated. The
        // one thing every real submission does is move currentApprovalStep off NONE (evaluateAndRoute
        // always sets MANAGER/FINANCE/DONE), so NONE here means nobody has ever run this quotation
        // through the approval chain. Without this check, a self-service list at 0% discount - always
        // "clean" - could be confirmed by the customer (or the rep) the instant it was created,
        // skipping the sales process entirely instead of waiting for a rep to review it.
        if (quotation.getCurrentApprovalStep() == Quotation.ApprovalStep.NONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This quotation hasn't been submitted for approval yet - a sales rep needs to review and submit it before it can be confirmed");
        }

        // Bug fix: UNDER_NEGOTIATION is reachable without ever going through Manager/Finance approval
        // (any negotiation message - even a plain comment - could move a quotation there), and a
        // counter-discount can also push an already-APPROVED deal's lines back over their ceiling
        // AFTER approval without ever re-running the approval chain. Previously this method trusted
        // the status alone, which let a sales rep "confirm" an over-ceiling discount straight through
        // without the manager ever seeing it. Compare the CURRENT blended risk score against the
        // score that was actually cleared last time this deal reached APPROVED
        // (Quotation.approvedRiskScore, maintained by ApprovalService): if the discount has grown
        // riskier since then, re-route it through real approval instead of trusting a status that no
        // longer reflects what was reviewed. A score that hasn't grown (including the common case of
        // an already-clean, never-discounted deal) confirms exactly as before - this never asks for a
        // second approval of the same or a smaller discount.
        BigDecimal score = discountRiskService.blendedRiskScore(quotation);
        quotation.setBlendedRiskScore(score);
        if (score.compareTo(quotation.getApprovedRiskScore()) > 0) {
            approvalService.evaluateAndRoute(quotation, actorUsername);
            quotationRepository.save(quotation);
            if (quotation.getStatus() != Quotation.Status.APPROVED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This quotation's current discount exceeds policy - it has been sent back for Manager/Finance approval instead of being confirmed");
            }
        }

        quotation.setStatus(Quotation.Status.CONFIRMED);
        quotation.setConfirmedAt(LocalDateTime.now());
        quotation.setUpdatedAt(LocalDateTime.now());
        quotationRepository.save(quotation);

        fulfillmentService.generateSuggestedSplit(quotation, actorUsername);
        quotationRepository.save(quotation);
        // PDF B7 - "one time lines and recurring lines separately within the same order": the one-time
        // lines become a single invoice (due now), each recurring line its own cycle schedule.
        for (QuotationLine line : quotation.getLines()) {
            if (line.getLineType() == QuotationLine.LineType.RECURRING) {
                billingService.generateInitialSchedule(line);
            } else {
                billingService.generateOneTimeInvoice(line);
            }
        }
        auditService.log("Quotation", quotation.getId(), "CONFIRMED", actorUsername, "Order confirmed and moved to fulfillment/billing");
        return quotation;
    }

    // ---------------------------------------------------------------- negotiation (customer portal)

    @Transactional
    public NegotiationMessage addNegotiationMessage(Long quotationId, NegotiationMessageRequest request, String senderTypeStr, String senderName) {
        Quotation quotation = getEntity(quotationId);
        if (request.messageType == null || request.messageType.isBlank()) request.messageType = "COMMENT";
        boolean isCounter = "COUNTER_DISCOUNT".equals(request.messageType);
        if (isCounter) {
            if (request.quotationLineId == null || request.proposedDiscountPercent == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a line and a proposed discount to counter");
            }
            validDiscount(request.proposedDiscountPercent);
            // Bug fix: a counter-discount used to rewrite the line's discount in ANY status - including
            // PENDING_APPROVAL (the numbers moved under the Manager mid-review, so what got approved was
            // not what was submitted) and CONFIRMED (a customer could change the price of an order that
            // was already invoiced and being shipped). Comments stay allowed everywhere; a change of
            // terms is only accepted while the deal is genuinely open for it.
            if (quotation.getStatus() == Quotation.Status.PENDING_APPROVAL) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This quotation is currently under Manager/Finance review - a counter-discount can be sent once that decision is made (a comment is fine meanwhile)");
            }
            if (quotation.getStatus() == Quotation.Status.CONFIRMED) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This order is already confirmed - its terms can no longer be changed");
            }
        }
        if ((request.content == null || request.content.isBlank()) && !isCounter) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message cannot be empty");
        }
        NegotiationMessage message = new NegotiationMessage();
        message.setQuotation(quotation);
        message.setSenderType(NegotiationMessage.SenderType.valueOf(senderTypeStr));
        message.setSenderName(senderName);
        message.setMessageType(NegotiationMessage.MessageType.valueOf(request.messageType));
        message.setContent(request.content);
        message.setProposedDiscountPercent(request.proposedDiscountPercent);

        if (message.getMessageType() == NegotiationMessage.MessageType.COUNTER_DISCOUNT && request.quotationLineId != null) {
            QuotationLine line = quotation.getLines().stream()
                    .filter(l -> l.getId().equals(request.quotationLineId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
            message.setQuotationLine(line);
            if (request.proposedDiscountPercent != null) {
                line.setDiscountPercent(request.proposedDiscountPercent);
                quotation.setBlendedRiskScore(discountRiskService.blendedRiskScore(quotation));
            }
        }

        // Bug fix: this used to flip ANY non-CONFIRMED quotation to UNDER_NEGOTIATION, including one
        // that was PENDING_APPROVAL - so a sales rep leaving so much as a comment on the thread would
        // silently pull the deal out of the Manager/Finance approval queue (it stops matching a
        // "pending approval" filter) while it was still mid-review. Only move a quotation INTO
        // negotiation from a state where that transition actually makes sense: DRAFT (chatting before
        // submission), APPROVED (negotiating after approval), or already UNDER_NEGOTIATION. A deal
        // that is PENDING_APPROVAL or REJECTED stays exactly where it is - the message still posts to
        // the same thread either way, it just no longer changes the quotation's status.
        // A COUNTER-DISCOUNT is a genuine new proposal, so it also reopens a REJECTED deal as a
        // negotiation (the customer lowering their ask after a rejection is exactly how a rejected deal
        // is meant to come back - see quick-test step 7); a plain comment never changes a rejection.
        Quotation.Status current = quotation.getStatus();
        if (current == Quotation.Status.DRAFT || current == Quotation.Status.APPROVED
                || current == Quotation.Status.UNDER_NEGOTIATION
                || (current == Quotation.Status.REJECTED && isCounter)) {
            quotation.setStatus(Quotation.Status.UNDER_NEGOTIATION);
        }
        quotation.setUpdatedAt(LocalDateTime.now());
        quotationRepository.save(quotation);
        return negotiationMessageRepository.save(message);
    }

    /**
     * Customer clicks "Confirm Quotation" in the portal. "If final terms
     * exceed approval thresholds, the quotation automatically re enters
     * the approval flow. Otherwise, the order moves directly to
     * fulfillment."
     */
    @Transactional
    public Quotation portalConfirm(Long quotationId) {
        Quotation quotation = getEntity(quotationId);
        // Bug fix: this had no status guard at all, so a customer could hit "Confirm Order" on an
        // already-REJECTED quotation and, whenever the current discount happened to be within
        // ceiling, it would go straight to APPROVED -> CONFIRMED - silently overturning a rejection a
        // Manager/Finance approver made, with no one re-reviewing that decision. REJECTED is meant to
        // be revisited through negotiation or a rep's "return for revision", not un-rejected by a
        // single click.
        if (quotation.getStatus() == Quotation.Status.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This order was not approved at the requested terms - please use \"Request a change\" or contact your sales rep instead of confirming");
        }
        // Bug fix: confirming while PENDING_APPROVAL re-ran the routing and reset a half-finished
        // Manager -> Finance chain back to the Manager step (losing the Manager's approval), and
        // confirming an already-CONFIRMED order re-generated its fulfillment/billing. Only an approved
        // or under-negotiation order can be confirmed from the portal.
        if (quotation.getStatus() == Quotation.Status.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This order is already confirmed");
        }
        if (quotation.getStatus() == Quotation.Status.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your requested terms are with the seller's approvers right now - you'll be able to confirm once they decide");
        }
        // Bug fix: a self-service "Browse & Request" order (or any other never-submitted DRAFT) sits
        // at 0% discount, which is always "clean" - so without this check the very first click of
        // "Confirm Order" would go straight through below (score <= 0 -> APPROVED -> CONFIRMED)
        // before any sales rep had ever looked at, priced or submitted it. Checking this up front
        // (rather than only inside confirmQuotation) matters here specifically because the branch
        // below sets currentApprovalStep to DONE itself before calling confirmQuotation, which would
        // otherwise silently satisfy that later check.
        if (quotation.getCurrentApprovalStep() == Quotation.ApprovalStep.NONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This order hasn't been reviewed by your sales rep yet - please wait for them to prepare it before confirming");
        }
        BigDecimal score = discountRiskService.blendedRiskScore(quotation);
        quotation.setBlendedRiskScore(score);
        String actor = quotation.getCustomer().getName() + " (customer portal)";

        // Bug fix: this used to re-enter approval whenever the score was above zero - even when the
        // Manager/Finance had ALREADY approved exactly these terms - so a customer confirming an
        // approved over-ceiling deal sent it round the approval chain a second time and nobody could
        // ever confirm it. The right test is the same one the internal confirm uses: has the discount
        // grown past what was cleared? "If final terms exceed approval thresholds, the quotation
        // automatically re enters the approval flow. Otherwise, the order moves directly to fulfillment."
        if (score.compareTo(quotation.getApprovedRiskScore()) > 0) {
            approvalService.evaluateAndRoute(quotation, actor);
            quotation.setUpdatedAt(LocalDateTime.now());
            quotationRepository.save(quotation);
            if (quotation.getStatus() != Quotation.Status.APPROVED) {
                return quotation; // now PENDING_APPROVAL - the approvers decide, the customer is told to wait
            }
            // The configured chain says these terms need no approval after all - fall through and confirm.
        }
        confirmQuotation(quotationId, actor);
        return quotation;
    }

    // ---------------------------------------------------------------- reads

    public Quotation getEntity(Long id) {
        return quotationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation not found"));
    }

    public List<Quotation> listAll() {
        return quotationRepository.findAll();
    }

    public List<Quotation> listForSalesRep(Long salesRepId) {
        return quotationRepository.findBySalesRepId(salesRepId);
    }

    public List<UpsellSuggestionResponse> upsellSuggestions(Long quotationId) {
        Quotation quotation = getEntity(quotationId);
        List<UpsellSuggestionResponse> result = new ArrayList<>();
        for (UpsellService.Suggestion s : upsellService.suggestFor(quotation)) {
            UpsellSuggestionResponse dto = new UpsellSuggestionResponse();
            dto.productId = s.product.getId();
            dto.productName = s.product.getName();
            dto.category = s.product.getCategory();
            dto.productImageUrl = s.product.getImageUrl();
            dto.price = s.product.getPrice();
            dto.marginPercent = s.marginPercent;
            dto.promoted = s.promoted;
            result.add(dto);
        }
        return result;
    }

    // ---------------------------------------------------------------- DTO mapping

    public QuotationResponse toResponse(Quotation q) {
        QuotationResponse dto = new QuotationResponse();
        dto.id = q.getId();
        dto.customerId = q.getCustomer().getId();
        dto.customerName = q.getCustomer().getName();
        dto.customerTier = q.getCustomer().getTier().name();
        dto.salesRepId = q.getSalesRep().getId();
        dto.salesRepName = q.getSalesRep().getFullName();
        dto.status = q.getStatus().name();
        dto.currentApprovalStep = q.getCurrentApprovalStep().name();
        dto.blendedRiskScore = q.getBlendedRiskScore();
        dto.totalAmount = q.totalAmount();
        dto.averageDiscountPercent = q.averageDiscountPercent();
        dto.createdAt = q.getCreatedAt();
        dto.updatedAt = q.getUpdatedAt();
        dto.confirmedAt = q.getConfirmedAt();
        dto.approvedRiskScore = q.getApprovedRiskScore();
        ApprovalService.ApprovalRequirement requirement = approvalService.describeRequirement(q.getBlendedRiskScore());
        dto.requiresManager = requirement.requiresManager;
        dto.requiresFinance = requirement.requiresFinance;
        dto.approvalRequirementLabel = requirement.label;
        dto.needsReapproval = q.getStatus() != Quotation.Status.CONFIRMED
                && q.getBlendedRiskScore().compareTo(q.getApprovedRiskScore()) > 0;
        dto.fulfillmentAcceptedAt = q.getFulfillmentAcceptedAt();
        dto.fulfillmentAcceptedBy = q.getFulfillmentAcceptedBy();

        List<LineResponse> lines = new ArrayList<>();
        for (QuotationLine line : q.getLines()) {
            LineResponse l = new LineResponse();
            l.id = line.getId();
            l.productId = line.getProduct().getId();
            l.productName = line.getProduct().getName();
            l.category = line.getProduct().getCategory();
            l.productImageUrl = line.getProduct().getImageUrl();
            l.quantity = line.getQuantity();
            l.unitPrice = line.getUnitPrice();
            l.discountPercent = line.getDiscountPercent();
            l.ceilingPercent = discountRiskService.ceilingFor(q.getCustomer().getTier(), line.getProduct().getCategory());
            l.overCeiling = discountRiskService.lineOverage(q.getCustomer().getTier(), line).compareTo(BigDecimal.ZERO) > 0;
            l.lineType = line.getLineType().name();
            l.subscriptionPlanId = line.getSubscriptionPlan() != null ? line.getSubscriptionPlan().getId() : null;
            l.lineTotal = line.lineTotal();
            l.marginAmount = line.marginAmount();
            lines.add(l);
        }
        dto.lines = lines;
        return dto;
    }

    public QuotationSummaryResponse toSummary(Quotation q) {
        QuotationSummaryResponse dto = new QuotationSummaryResponse();
        dto.id = q.getId();
        dto.customerName = q.getCustomer().getName();
        dto.customerTier = q.getCustomer().getTier().name();
        dto.status = q.getStatus().name();
        dto.totalAmount = q.totalAmount();
        dto.blendedRiskScore = q.getBlendedRiskScore();
        dto.salesRepName = q.getSalesRep().getFullName();
        dto.updatedAt = q.getUpdatedAt();
        return dto;
    }
}
