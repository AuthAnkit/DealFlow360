package com.dealflow360.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Response shapes for the Deal Health & Anomaly dashboard and the reporting endpoints. */
public class DashboardDtos {

    public static class Alert {
        public Long quotationId;
        public String customerName;
        public String type; // STALLED_DEAL, DISCOUNT_ANOMALY, DELIVERY_SLIPPAGE
        public String message;
        public String severity; // LOW, MEDIUM, HIGH

        public Alert(Long quotationId, String customerName, String type, String message, String severity) {
            this.quotationId = quotationId;
            this.customerName = customerName;
            this.type = type;
            this.message = message;
            this.severity = severity;
        }
    }

    public static class DealHealthResponse {
        public List<Alert> stalledDeals;
        public List<Alert> discountAnomalies;
        public List<Alert> deliverySlippages;
        // Added anomaly categories (Deal Anomaly Detection) - same shape as the original three so the
        // existing frontend rendering helper works unchanged for all six lists.
        public List<Alert> marginAnomalies;
        public List<Alert> negotiationLoops;
        public List<Alert> approvalDelays;
        public int openQuotations;
        public BigDecimal pipelineValue;
    }

    public static class NudgeRequest {
        public String message = "";
    }

    public static class ReportFilter {
        public String fromDate;   // yyyy-MM-dd, optional
        public String toDate;     // yyyy-MM-dd, optional
        public Long salesRepId;   // optional
        public String status;     // optional
        public String category;   // optional
    }

    public static class ProductStat {
        public String productName;
        public int unitsSold;
        public BigDecimal totalDiscountGiven;
        public BigDecimal revenue;
    }

    public static class ReportResponse {
        public int totalQuotations;
        public BigDecimal totalValue;
        public BigDecimal averageDiscountPercent;
        public Map<String, Long> countByStatus;
        public List<ProductStat> topProducts;
        public List<QuotationDtos.QuotationSummaryResponse> quotations;
    }
}
