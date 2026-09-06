package com.dealflow360.controller;

import com.dealflow360.dto.IntelligenceDtos.ApprovalExplanationResponse;
import com.dealflow360.dto.IntelligenceDtos.DealHealthScoreResponse;
import com.dealflow360.dto.IntelligenceDtos.DealIntelligenceResponse;
import com.dealflow360.dto.IntelligenceDtos.ScenarioRequest;
import com.dealflow360.dto.IntelligenceDtos.ScenarioResponse;
import com.dealflow360.dto.IntelligenceDtos.TimelineEventResponse;
import com.dealflow360.dto.QuotationDtos.QuotationResponse;
import com.dealflow360.model.Quotation;
import com.dealflow360.service.DealCopilotService;
import com.dealflow360.service.DealHealthService;
import com.dealflow360.service.QuotationService;
import com.dealflow360.service.ScenarioService;
import com.dealflow360.service.TimelineService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * "Deal Intelligence" - the self-governing decision-support layer added
 * on top of the existing quotation workflow: the Deal Copilot, the
 * explainable-approval breakdown, the What-If Deal Simulator, the Deal
 * Timeline, and the per-deal Deal Health Score. Every response is built
 * from the same quotation the Cart/Approval/Fulfillment/Billing tabs
 * already use, via the same calculators - this controller only wires
 * HTTP to those calculators, it does not compute anything itself.
 */
@RestController
@RequestMapping("/api/quotations/{id}")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class IntelligenceController {

    private final QuotationService quotationService;
    private final DealCopilotService dealCopilotService;
    private final ScenarioService scenarioService;
    private final TimelineService timelineService;
    private final DealHealthService dealHealthService;

    public IntelligenceController(QuotationService quotationService, DealCopilotService dealCopilotService,
                                   ScenarioService scenarioService, TimelineService timelineService,
                                   DealHealthService dealHealthService) {
        this.quotationService = quotationService;
        this.dealCopilotService = dealCopilotService;
        this.scenarioService = scenarioService;
        this.timelineService = timelineService;
        this.dealHealthService = dealHealthService;
    }

    /** Deal Copilot - real-time insights (warnings, risks, recommendations, positive signals). */
    @GetMapping("/intelligence")
    public DealIntelligenceResponse intelligence(@PathVariable Long id) {
        return dealCopilotService.analyze(quotationService.getEntity(id));
    }

    /** Explainable approval decision: per-line allowed vs. applied discount and why approval is/isn't required. */
    @GetMapping("/approval-explanation")
    public ApprovalExplanationResponse approvalExplanation(@PathVariable Long id) {
        return dealCopilotService.explainApproval(quotationService.getEntity(id));
    }

    /** What-If Deal Simulator - compares the real quotation against a hypothetical scenario. Nothing is saved. */
    @PostMapping("/scenario/simulate")
    public ScenarioResponse simulateScenario(@PathVariable Long id, @RequestBody ScenarioRequest request) {
        return scenarioService.simulate(id, request);
    }

    /** Commits a chosen scenario to the real quotation via the normal add/update/remove-line logic. */
    @PostMapping("/scenario/apply")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
    public QuotationResponse applyScenario(@PathVariable Long id, @RequestBody ScenarioRequest request, Authentication auth) {
        Quotation quotation = scenarioService.apply(id, request, auth.getName());
        return quotationService.toResponse(quotation);
    }

    /** Visual Deal Timeline - the complete, real event history of this deal. */
    @GetMapping("/timeline")
    public List<TimelineEventResponse> timeline(@PathVariable Long id) {
        quotationService.getEntity(id); // 404s if missing
        return timelineService.forQuotation(id);
    }

    /** Deal Health Score (0-100) with a fully explained factor breakdown and recommended next actions. */
    @GetMapping("/health-score")
    public DealHealthScoreResponse healthScore(@PathVariable Long id) {
        return dealHealthService.computeScore(quotationService.getEntity(id));
    }
}
