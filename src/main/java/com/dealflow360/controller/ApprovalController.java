package com.dealflow360.controller;

import com.dealflow360.dto.QuotationDtos.ApprovalActionRequest;
import com.dealflow360.dto.QuotationDtos.ApprovalLogResponse;
import com.dealflow360.dto.QuotationDtos.QuotationResponse;
import com.dealflow360.model.ApprovalLog;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.Role;
import com.dealflow360.service.ApprovalService;
import com.dealflow360.service.QuotationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF B4 - Discount Approval Screen: approve / reject / return for
 * revision, plus the full audit trail. Whoever is asked to act is
 * whichever role the quotation's currentApprovalStep points at (Sales
 * Manager first, then Finance if the risk score demands it) - Admin can
 * always act as an override.
 */
@RestController
@RequestMapping("/api/quotations/{id}/approval")
@PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','FINANCE')")
public class ApprovalController {

    private final QuotationService quotationService;
    private final ApprovalService approvalService;

    public ApprovalController(QuotationService quotationService, ApprovalService approvalService) {
        this.quotationService = quotationService;
        this.approvalService = approvalService;
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','FINANCE','SALES_REP')")
    public List<ApprovalLogResponse> history(@PathVariable Long id) {
        return approvalService.history(id).stream().map(this::toResponse).collect(Collectors.toList());
    }

    @PostMapping("/approve")
    public QuotationResponse approve(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest request, Authentication auth) {
        Role role = requiredStepRole(id, auth);
        String reason = request != null ? request.reason : "";
        return quotationService.toResponse(quotationService.approveStep(id, role, auth.getName(), reason));
    }

    @PostMapping("/reject")
    public QuotationResponse reject(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest request, Authentication auth) {
        Role role = requiredStepRole(id, auth);
        String reason = request != null ? request.reason : "";
        return quotationService.toResponse(quotationService.rejectStep(id, role, auth.getName(), reason));
    }

    @PostMapping("/return")
    public QuotationResponse returnForRevision(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest request, Authentication auth) {
        Role role = requiredStepRole(id, auth);
        String reason = request != null ? request.reason : "";
        return quotationService.toResponse(quotationService.returnStep(id, role, auth.getName(), reason));
    }

    /** Confirms the quotation is actually waiting on this approver's role (Admin may always act) and returns the acting role to log. */
    private Role requiredStepRole(Long quotationId, Authentication auth) {
        Quotation quotation = quotationService.getEntity(quotationId);
        boolean isAdmin = hasRole(auth, "ADMIN");

        // Bug fix: only the approval STEP was checked, never the status - after a rejection the step
        // still read MANAGER, so a Manager could "approve" a REJECTED quotation straight to APPROVED,
        // and a reopened/negotiated deal could be acted on out of turn. Approval actions apply to a
        // quotation that is actually waiting in the approval queue, nothing else.
        if (quotation.getStatus() != Quotation.Status.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This quotation is not waiting for approval (status: " + quotation.getStatus() + ")");
        }

        Role expected = switch (quotation.getCurrentApprovalStep()) {
            case MANAGER -> Role.SALES_MANAGER;
            case FINANCE -> Role.FINANCE;
            default -> null;
        };

        if (expected == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This quotation is not currently waiting for approval");
        }
        if (!isAdmin && !hasRole(auth, expected.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This quotation currently requires " + expected + " approval");
        }
        // Managers can now build quotations themselves - but nobody approves their own deal.
        if (!isAdmin && quotation.getSalesRep().getUsername().equals(auth.getName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You own this quotation - it has to be approved by another " + expected.name().replace('_', ' ').toLowerCase());
        }
        return isAdmin && !hasRole(auth, expected.name()) ? Role.ADMIN : expected;
    }

    private boolean hasRole(Authentication auth, String role) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (authority.getAuthority().equals("ROLE_" + role)) return true;
        }
        return false;
    }

    private ApprovalLogResponse toResponse(ApprovalLog log) {
        ApprovalLogResponse dto = new ApprovalLogResponse();
        dto.id = log.getId();
        dto.approverRole = log.getApproverRole().name();
        dto.action = log.getAction().name();
        dto.actorUsername = log.getActorUsername();
        dto.reason = log.getReason();
        dto.timestamp = log.getTimestamp();
        return dto;
    }
}
