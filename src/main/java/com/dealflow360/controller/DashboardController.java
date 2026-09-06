package com.dealflow360.controller;

import com.dealflow360.dto.DashboardDtos.*;
import com.dealflow360.service.DealHealthService;
import com.dealflow360.service.ReportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** PDF B9 / A7 - Deal Health & Anomaly Dashboard and the sales reporting screen. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','FINANCE')")
public class DashboardController {

    private final DealHealthService dealHealthService;
    private final ReportService reportService;

    public DashboardController(DealHealthService dealHealthService, ReportService reportService) {
        this.dealHealthService = dealHealthService;
        this.reportService = reportService;
    }

    @GetMapping("/dashboard/deal-health")
    public DealHealthResponse dealHealth() {
        return dealHealthService.compute();
    }

    @PostMapping("/dashboard/deal-health/{quotationId}/nudge")
    public void nudge(@PathVariable Long quotationId, @RequestBody(required = false) NudgeRequest request, Authentication auth) {
        dealHealthService.nudge(quotationId, request != null ? request.message : "", auth.getName());
    }

    @GetMapping("/reports/quotations")
    public ReportResponse report(@RequestParam(required = false) String fromDate,
                                  @RequestParam(required = false) String toDate,
                                  @RequestParam(required = false) Long salesRepId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String category) {
        ReportFilter filter = new ReportFilter();
        filter.fromDate = fromDate;
        filter.toDate = toDate;
        filter.salesRepId = salesRepId;
        filter.status = status;
        filter.category = category;
        return reportService.run(filter);
    }
}
