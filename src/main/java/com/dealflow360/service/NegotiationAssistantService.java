package com.dealflow360.service;

import com.dealflow360.dto.IntelligenceDtos.NegotiationAlternativesResponse;
import com.dealflow360.dto.IntelligenceDtos.NegotiationOptionResponse;
import com.dealflow360.dto.IntelligenceDtos.ScenarioLineChange;
import com.dealflow360.dto.IntelligenceDtos.ScenarioRequest;
import com.dealflow360.dto.IntelligenceDtos.ScenarioSnapshot;
import com.dealflow360.model.CustomerTier;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Smart Negotiation Assistant: when a customer asks for a bigger discount,
 * this computes real, calculated alternatives instead of a single
 * take-it-or-leave-it number. Every option is produced by asking {@link
 * ScenarioService} "what would this discount actually do to margin, risk,
 * and approval?" - the same What-If engine the rep uses directly - so
 * nothing here is an invented number. This assistant is advisory only: it
 * never changes the quotation itself, and it never bypasses approval -
 * accepting an option still goes through the normal Cart-tab edit +
 * Submit-for-approval flow, which re-evaluates and re-routes exactly as
 * it always has.
 */
@Service
public class NegotiationAssistantService {

    private final QuotationService quotationService;
    private final ScenarioService scenarioService;
    private final DiscountRiskService discountRiskService;
    private final UpsellService upsellService;

    public NegotiationAssistantService(QuotationService quotationService, ScenarioService scenarioService,
                                        DiscountRiskService discountRiskService, UpsellService upsellService) {
        this.quotationService = quotationService;
        this.scenarioService = scenarioService;
        this.discountRiskService = discountRiskService;
        this.upsellService = upsellService;
    }

    public NegotiationAlternativesResponse generateAlternatives(Long quotationId, Long lineId, BigDecimal requestedDiscountPercent) {
        Quotation quotation = quotationService.getEntity(quotationId);
        QuotationLine line = quotation.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation line not found"));

        CustomerTier tier = quotation.getCustomer().getTier();
        BigDecimal ceiling = discountRiskService.ceilingFor(tier, line.getProduct().getCategory());
        BigDecimal currentDiscount = line.getDiscountPercent();

        List<NegotiationOptionResponse> options = new ArrayList<>();

        // Option A: reduce to the category ceiling - stays within policy, no extra approval for this line.
        BigDecimal withinCeilingDiscount = ceiling.min(requestedDiscountPercent);
        options.add(buildOption("Reduce discount to category limit", quotationId, line, withinCeilingDiscount, null, null,
                "Reduces the discount from the customer's requested " + requestedDiscountPercent + "% to " + withinCeilingDiscount
                        + "% - the " + tier + "/" + line.getProduct().getCategory() + " category ceiling - so this line no longer "
                        + "contributes to the blended risk score."));

        // Option B: a smaller discount plus a real, eligible add-on/bundle product instead of the full ask.
        List<UpsellService.Suggestion> suggestions = upsellService.suggestFor(quotation);
        if (!suggestions.isEmpty()) {
            UpsellService.Suggestion addOn = suggestions.get(0);
            options.add(buildOption("Smaller discount + complimentary add-on", quotationId, line, withinCeilingDiscount,
                    addOn.product.getId(), addOn.product.getName(),
                    "Offers " + withinCeilingDiscount + "% on " + line.getProduct().getName() + " plus a complimentary "
                            + addOn.product.getName() + " instead of the full " + requestedDiscountPercent + "% discount - "
                            + "often preserves more margin than a straight discount of that size."));
        }

        // Option C: accept the customer's full request, with the real consequence shown plainly.
        options.add(buildOption("Accept requested discount", quotationId, line, requestedDiscountPercent, null, null,
                "Accepts the customer's requested " + requestedDiscountPercent + "% discount as-is. This line's discount "
                        + "exceeds the category ceiling of " + ceiling + "%, so the quotation will require the approval shown below."));

        NegotiationAlternativesResponse response = new NegotiationAlternativesResponse();
        response.customerRequestSummary = "Customer requested " + requestedDiscountPercent + "% on " + line.getProduct().getName()
                + " (currently " + currentDiscount + "%).";
        response.options = options;
        return response;
    }

    /** Runs one candidate discount (and optional add-on) through the real What-If engine and packages the result. */
    private NegotiationOptionResponse buildOption(String label, Long quotationId, QuotationLine line, BigDecimal discountPercent,
                                                   Long addOnProductId, String addOnProductName, String narrative) {
        ScenarioRequest request = new ScenarioRequest();
        List<ScenarioLineChange> changes = new ArrayList<>();

        ScenarioLineChange lineChange = new ScenarioLineChange();
        lineChange.lineId = line.getId();
        lineChange.discountPercent = discountPercent;
        changes.add(lineChange);

        if (addOnProductId != null) {
            ScenarioLineChange addOnChange = new ScenarioLineChange();
            addOnChange.productId = addOnProductId;
            addOnChange.quantity = 1;
            addOnChange.discountPercent = BigDecimal.valueOf(100); // "complimentary" - given at no charge
            changes.add(addOnChange);
        }

        request.changes = changes;
        ScenarioSnapshot snapshot = scenarioService.simulate(quotationId, request).scenario;

        NegotiationOptionResponse option = new NegotiationOptionResponse();
        option.label = label;
        option.discountPercent = discountPercent;
        option.addOnProductId = addOnProductId;
        option.addOnProductName = addOnProductName;
        option.marginAmount = snapshot.marginAmount;
        option.marginPercent = snapshot.marginPercent;
        option.riskScore = snapshot.blendedRiskScore;
        option.requiresManager = snapshot.requiresManager;
        option.requiresFinance = snapshot.requiresFinance;
        option.approvalLabel = snapshot.approvalLabel;
        option.narrative = narrative;
        return option;
    }
}
