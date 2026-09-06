package com.dealflow360.controller;

import com.dealflow360.dto.IntelligenceDtos.NegotiationAlternativesRequest;
import com.dealflow360.dto.IntelligenceDtos.NegotiationAlternativesResponse;
import com.dealflow360.dto.QuotationDtos.*;
import com.dealflow360.dto.RecommendationDtos.RecommendationActionRequest;
import com.dealflow360.dto.RecommendationDtos.RecommendationPanelResponse;
import com.dealflow360.model.UpsellRule;
import com.dealflow360.model.AppUser;
import com.dealflow360.model.NegotiationMessage;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.Role;
import com.dealflow360.repository.AppUserRepository;
import com.dealflow360.repository.NegotiationMessageRepository;
import com.dealflow360.service.NegotiationAssistantService;
import com.dealflow360.service.QuotationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF B1-B5: the sales rep's Quotation Builder workspace - create a
 * quotation, add/edit lines, see live upsell suggestions, and submit for
 * (automatic) discount approval routing.
 */
@RestController
@RequestMapping("/api/quotations")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class QuotationController {

    private final QuotationService quotationService;
    private final AppUserRepository appUserRepository;
    private final NegotiationMessageRepository negotiationMessageRepository;
    private final NegotiationAssistantService negotiationAssistantService;

    public QuotationController(QuotationService quotationService, AppUserRepository appUserRepository,
                                NegotiationMessageRepository negotiationMessageRepository,
                                NegotiationAssistantService negotiationAssistantService) {
        this.quotationService = quotationService;
        this.appUserRepository = appUserRepository;
        this.negotiationMessageRepository = negotiationMessageRepository;
        this.negotiationAssistantService = negotiationAssistantService;
    }

    /**
     * Smart Negotiation Assistant: given what the customer is asking for on one line, computes real
     * (not invented) alternative options - reduced discount, reduced discount + add-on, and the
     * requested discount as-is - each run through the actual pricing/margin/risk/approval logic.
     * Advisory only: applying a chosen option is a normal line edit through the existing endpoints,
     * so it never bypasses approval routing.
     */
    @PostMapping("/{id}/negotiation/alternatives")
    public NegotiationAlternativesResponse negotiationAlternatives(@PathVariable Long id, @RequestBody NegotiationAlternativesRequest request) {
        return negotiationAssistantService.generateAlternatives(id, request.quotationLineId, request.requestedDiscountPercent);
    }

    @GetMapping
    public List<QuotationSummaryResponse> list(Authentication auth) {
        AppUser current = currentUser(auth);
        List<Quotation> quotations = current.getRole() == Role.SALES_REP
                ? quotationService.listForSalesRep(current.getId())
                : quotationService.listAll();
        return quotations.stream().map(quotationService::toSummary).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public QuotationResponse get(@PathVariable Long id) {
        return quotationService.toResponse(quotationService.getEntity(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse create(@RequestBody CreateQuotationRequest request, Authentication auth) {
        // A Sales Manager or Admin can build a quotation themselves, or open it on behalf of one of
        // their reps (the rep then owns it: it shows in that rep's list and history). A rep always
        // owns what they create.
        AppUser current = currentUser(auth);
        String owner = auth.getName();
        if (request.salesRepUsername != null && !request.salesRepUsername.isBlank() && !request.salesRepUsername.equals(owner)) {
            if (current.getRole() == Role.SALES_REP) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A sales rep can only create quotations for themselves");
            }
            AppUser assignee = appUserRepository.findByUsername(request.salesRepUsername.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sales rep not found"));
            if (!assignee.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, assignee.getFullName() + " is inactive and cannot own a quotation");
            }
            owner = assignee.getUsername();
        }
        Quotation quotation = quotationService.createQuotation(request.customerId, owner);
        return quotationService.toResponse(quotation);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse addLine(@PathVariable Long id, @RequestBody AddLineRequest request) {
        return quotationService.toResponse(quotationService.addLine(id, request));
    }

    @PutMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse updateLine(@PathVariable Long id, @PathVariable Long lineId, @RequestBody UpdateLineRequest request) {
        return quotationService.toResponse(quotationService.updateLine(id, lineId, request));
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse removeLine(@PathVariable Long id, @PathVariable Long lineId) {
        return quotationService.toResponse(quotationService.removeLine(id, lineId));
    }

    /** PDF B3 - order-level discount: applies one discount % to every line (goes through the same editability + validation rules). */
    @PutMapping("/{id}/order-discount")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse orderDiscount(@PathVariable Long id, @RequestBody OrderDiscountRequest request, Authentication auth) {
        return quotationService.toResponse(quotationService.applyOrderDiscount(id, request.discountPercent, auth.getName()));
    }

    /**
     * Sales rep reopens a REJECTED (or APPROVED-but-unconfirmed) quotation as a Draft so it can be
     * revised and submitted again - the previous decision is logged as superseded, nothing is deleted.
     */
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse reopen(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest request, Authentication auth) {
        String reason = request != null ? request.reason : "";
        return quotationService.toResponse(quotationService.reopenForRevision(id, auth.getName(), reason));
    }

    // ------------------------------------------------------------ Live Product Recommendation Engine (B5)

    /** Ranked live recommendations for the quotation (CROSS_SELL / UPSELL / PRODUCT_UPGRADE), optionally filtered by type. */
    @GetMapping("/{id}/recommendations")
    public RecommendationPanelResponse recommendations(@PathVariable Long id, @RequestParam(required = false) UpsellRule.RecommendationType type) {
        return quotationService.recommendations(id, type);
    }

    /** Sales rep accepts a recommendation: ADD / ADD_BOTH adds the product, UPGRADE replaces the source line (quantity preserved). */
    @PostMapping("/{id}/recommendations/accept")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse acceptRecommendation(@PathVariable Long id, @RequestBody RecommendationActionRequest request, Authentication auth) {
        return quotationService.toResponse(quotationService.acceptRecommendation(id, request, auth.getName()));
    }

    /** Sales rep dismisses a recommendation for this quotation (the rule stays active for every other deal). */
    @PostMapping("/{id}/recommendations/dismiss")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public RecommendationPanelResponse dismissRecommendation(@PathVariable Long id, @RequestBody RecommendationActionRequest request, Authentication auth) {
        quotationService.dismissRecommendation(id, request, auth.getName());
        return quotationService.recommendations(id, null);
    }

    /** Brings back every recommendation dismissed on this quotation. */
    @PostMapping("/{id}/recommendations/restore")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public RecommendationPanelResponse restoreRecommendations(@PathVariable Long id, Authentication auth) {
        quotationService.restoreRecommendations(id, auth.getName());
        return quotationService.recommendations(id, null);
    }

    @GetMapping("/{id}/upsell-suggestions")
    public List<UpsellSuggestionResponse> upsell(@PathVariable Long id) {
        return quotationService.upsellSuggestions(id);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse submit(@PathVariable Long id, Authentication auth) {
        return quotationService.toResponse(quotationService.submitForApproval(id, auth.getName()));
    }

    /** Internal confirm - used when no customer-portal negotiation step is needed. */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse confirm(@PathVariable Long id, Authentication auth) {
        return quotationService.toResponse(quotationService.confirmQuotation(id, auth.getName()));
    }

    /** Internal (sales-rep-side) view of the same negotiation thread the customer sees in the portal. */
    @GetMapping("/{id}/negotiation")
    public List<NegotiationMessageResponse> negotiation(@PathVariable Long id) {
        return negotiationMessageRepository.findByQuotationIdOrderByTimestampAsc(id).stream()
                .map(this::toNegotiationDto).collect(Collectors.toList());
    }

    /** Sales rep replies on the negotiation thread (comment only - reps don't self-approve their own counter-discounts). */
    @PostMapping("/{id}/negotiation/reply")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public NegotiationMessageResponse reply(@PathVariable Long id, @RequestBody NegotiationMessageRequest request, Authentication auth) {
        request.messageType = "COMMENT"; // reps comment; only the customer counter-proposes a discount from the portal
        AppUser current = currentUser(auth);
        NegotiationMessage saved = quotationService.addNegotiationMessage(id, request, "SALES_REP", current.getFullName());
        return toNegotiationDto(saved);
    }

    private NegotiationMessageResponse toNegotiationDto(NegotiationMessage m) {
        NegotiationMessageResponse dto = new NegotiationMessageResponse();
        dto.id = m.getId();
        dto.senderType = m.getSenderType().name();
        dto.senderName = m.getSenderName();
        dto.messageType = m.getMessageType().name();
        dto.content = m.getContent();
        dto.proposedDiscountPercent = m.getProposedDiscountPercent();
        dto.quotationLineId = m.getQuotationLine() != null ? m.getQuotationLine().getId() : null;
        dto.timestamp = m.getTimestamp();
        return dto;
    }

    private AppUser currentUser(Authentication auth) {
        return appUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an internal user"));
    }
}
