package com.dealflow360.controller;

import com.dealflow360.dto.QuotationDtos.*;
import com.dealflow360.model.AppUser;
import com.dealflow360.model.Customer;
import com.dealflow360.model.NegotiationMessage;
import com.dealflow360.model.Product;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.model.Role;
import com.dealflow360.repository.AppUserRepository;
import com.dealflow360.repository.CustomerRepository;
import com.dealflow360.repository.NegotiationMessageRepository;
import com.dealflow360.repository.ProductRepository;
import com.dealflow360.repository.QuotationRepository;
import com.dealflow360.service.PricingService;
import com.dealflow360.service.QuotationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF B8 - Customer Portal Negotiation Screen. This is intentionally a
 * completely separate controller (and separate role, ROLE_CUSTOMER) from
 * the internal sales workspace: a customer can only ever see and act on
 * their OWN quotations, never anyone else's, and can never reach any
 * /api/quotations, /api/config, /api/products etc. endpoint used by
 * internal staff.
 */
@RestController
@RequestMapping("/api/portal")
@PreAuthorize("hasRole('CUSTOMER')")
public class PortalController {

    private final CustomerRepository customerRepository;
    private final QuotationService quotationService;
    private final NegotiationMessageRepository negotiationMessageRepository;
    private final ProductRepository productRepository;
    private final QuotationRepository quotationRepository;
    private final AppUserRepository appUserRepository;
    private final PricingService pricingService;

    public PortalController(CustomerRepository customerRepository, QuotationService quotationService,
                             NegotiationMessageRepository negotiationMessageRepository,
                             ProductRepository productRepository, QuotationRepository quotationRepository,
                             AppUserRepository appUserRepository, PricingService pricingService) {
        this.customerRepository = customerRepository;
        this.quotationService = quotationService;
        this.negotiationMessageRepository = negotiationMessageRepository;
        this.productRepository = productRepository;
        this.quotationRepository = quotationRepository;
        this.appUserRepository = appUserRepository;
        this.pricingService = pricingService;
    }

    /** Product & Price List, shown to the customer so they can build their own list (A2, customer-safe subset - no cost/margin). */
    @GetMapping("/products")
    public List<PortalProductResponse> catalog(Authentication auth) {
        Customer customer = currentCustomer(auth);
        // PDF A2 price lists: the customer sees THEIR tier's price - the same price addLine will use.
        return productRepository.findAll().stream()
                .filter(Product::isActive)
                .map(p -> toPortalProduct(p, quotationService.priceFor(customer, p)))
                .collect(Collectors.toList());
    }

    /**
     * Self-service "give us a list" request: the customer picks products and quantities themselves
     * (like an e-commerce cart) instead of waiting for a sales rep to build the quotation first.
     * We create a real Quotation from it - routed to the rep who already knows this account, or
     * the least-busy sales rep if this is the customer's first request - and leave it in DRAFT so
     * the rep can review/adjust before submitting it through the normal approval flow. This reuses
     * the exact same pricing/line logic as the internal Quotation Builder; nothing is duplicated.
     */
    @PostMapping("/quotations")
    public QuotationResponse requestQuote(@RequestBody PortalRequestQuoteRequest request, Authentication auth) {
        Customer customer = currentCustomer(auth);
        if (request.items == null || request.items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one product to your list before submitting");
        }

        Quotation quotation = quotationService.createQuotation(customer.getId(), pickSalesRepUsername(customer));
        for (PortalRequestLine item : request.items) {
            if (item.productId == null || item.quantity < 1) continue;
            AddLineRequest lineRequest = new AddLineRequest();
            lineRequest.productId = item.productId;
            lineRequest.quantity = item.quantity;
            if (item.subscriptionPlanId != null) {
                lineRequest.lineType = QuotationLine.LineType.RECURRING;
                lineRequest.subscriptionPlanId = item.subscriptionPlanId;
            }
            // (a subscription product with no plan chosen is still added as RECURRING on its default plan - see QuotationService.addLine)
            quotation = quotationService.addLine(quotation.getId(), lineRequest);
        }
        if (quotation.getLines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "None of the selected products could be added");
        }

        if (request.note != null && !request.note.isBlank()) {
            NegotiationMessageRequest note = new NegotiationMessageRequest();
            note.content = request.note;
            note.messageType = "COMMENT";
            quotationService.addNegotiationMessage(quotation.getId(), note, "CUSTOMER", customer.getName());
        }

        return quotationService.toResponse(quotationService.getEntity(quotation.getId()));
    }

    /** Same customer -> keep the same rep for continuity; otherwise hand the request to whoever has the fewest open deals right now. */
    private String pickSalesRepUsername(Customer customer) {
        List<Quotation> priorOrders = quotationRepository.findByCustomerId(customer.getId());
        if (!priorOrders.isEmpty()) {
            return priorOrders.stream()
                    .max(Comparator.comparing(Quotation::getUpdatedAt))
                    .get().getSalesRep().getUsername();
        }

        List<AppUser> reps = appUserRepository.findByRole(Role.SALES_REP).stream()
                .filter(AppUser::isActive)
                .collect(Collectors.toList());
        if (reps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No sales rep is available to receive your request right now - please try again shortly");
        }
        AppUser leastLoaded = reps.get(0);
        long minOpen = Long.MAX_VALUE;
        for (AppUser rep : reps) {
            long open = quotationService.listForSalesRep(rep.getId()).stream()
                    .filter(q -> q.getStatus() != Quotation.Status.CONFIRMED && q.getStatus() != Quotation.Status.REJECTED)
                    .count();
            if (open < minOpen) {
                minOpen = open;
                leastLoaded = rep;
            }
        }
        return leastLoaded.getUsername();
    }

    private PortalProductResponse toPortalProduct(Product p, java.math.BigDecimal tierPrice) {
        PortalProductResponse dto = new PortalProductResponse();
        dto.id = p.getId();
        dto.name = p.getName();
        dto.category = p.getCategory();
        dto.unit = p.getUnit();
        dto.price = tierPrice;
        dto.taxPercent = p.getTaxPercent();
        dto.description = p.getDescription();
        dto.imageUrl = p.getImageUrl();
        dto.plans = pricingService.plansFor(p).stream().map(plan -> {
            PortalPlanResponse pl = new PortalPlanResponse();
            pl.id = plan.getId();
            pl.name = plan.getName();
            pl.billingCycle = plan.getBillingCycle().name();
            pl.pricePerCycle = plan.getPricePerCycle();
            return pl;
        }).collect(Collectors.toList());
        return dto;
    }

    @GetMapping("/quotations")
    public List<QuotationSummaryResponse> myQuotations(Authentication auth) {
        Customer customer = currentCustomer(auth);
        return quotationService.listAll().stream()
                .filter(q -> q.getCustomer().getId().equals(customer.getId()))
                .map(quotationService::toSummary)
                .collect(Collectors.toList());
    }

    @GetMapping("/quotations/{id}")
    public QuotationResponse view(@PathVariable Long id, Authentication auth) {
        Quotation quotation = ownedQuotation(id, auth);
        return quotationService.toResponse(quotation);
    }

    @GetMapping("/quotations/{id}/negotiation")
    public List<NegotiationMessageResponse> negotiation(@PathVariable Long id, Authentication auth) {
        ownedQuotation(id, auth); // ownership check
        return negotiationMessageRepository.findByQuotationIdOrderByTimestampAsc(id).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping("/quotations/{id}/negotiate")
    public NegotiationMessageResponse negotiate(@PathVariable Long id, @RequestBody NegotiationMessageRequest request, Authentication auth) {
        Customer customer = currentCustomer(auth);
        ownedQuotation(id, auth);
        NegotiationMessage message = quotationService.addNegotiationMessage(id, request, "CUSTOMER", customer.getName());
        return toDto(message);
    }

    /** "Confirm Quotation" button - re-checks thresholds and either confirms or re-enters approval automatically. */
    @PostMapping("/quotations/{id}/confirm")
    public QuotationResponse confirm(@PathVariable Long id, Authentication auth) {
        ownedQuotation(id, auth);
        return quotationService.toResponse(quotationService.portalConfirm(id));
    }

    private Customer currentCustomer(Authentication auth) {
        return customerRepository.findByPortalUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a customer portal account"));
    }

    private Quotation ownedQuotation(Long quotationId, Authentication auth) {
        Customer customer = currentCustomer(auth);
        Quotation quotation = quotationService.getEntity(quotationId);
        if (!quotation.getCustomer().getId().equals(customer.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This quotation does not belong to your account");
        }
        return quotation;
    }

    private NegotiationMessageResponse toDto(NegotiationMessage m) {
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
}
