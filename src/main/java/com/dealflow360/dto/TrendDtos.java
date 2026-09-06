package com.dealflow360.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request/response shapes for the Trends & Analytics screen: "last N months
 * of data for some particular thing, as numbers and a graph" - the feature
 * requested on top of the original PDF scope. Kept as its own file so it is
 * obvious in a demo/judging Q&A that this is an added differentiator, not a
 * PDF-required section.
 */
public class TrendDtos {

    /** Which slice of the business the trend line is about. */
    public enum Dimension {
        OVERALL, PRODUCT, CATEGORY, SALES_REP
    }

    public static class TrendFilter {
        public int months = 3;              // window size - "last N months", defaults to 3
        public Dimension dimension = Dimension.OVERALL;
        public Long dimensionId;            // productId or salesRepId, depending on dimension
        public String category;             // used when dimension == CATEGORY
    }

    /** One bucket in the trend line - one calendar month. */
    public static class TrendPoint {
        public String monthLabel;           // e.g. "Jul 2026"
        public String monthKey;             // e.g. "2026-07" - stable sort key
        public int quotationsCreated;
        public int quotationsConfirmed;
        public int unitsSold;
        public BigDecimal revenue = BigDecimal.ZERO;
        public BigDecimal averageDiscountPercent = BigDecimal.ZERO;
    }

    public static class TrendResponse {
        public String dimensionLabel;       // human-readable, e.g. "Laptop Pro" or "Hardware" or "Overall business"
        public int months;
        public List<TrendPoint> points;

        // Rolled-up totals across the whole window, for the "give me the number" half of the request
        public int totalQuotations;
        public int totalUnitsSold;
        public BigDecimal totalRevenue = BigDecimal.ZERO;
        public BigDecimal averageDiscountPercent = BigDecimal.ZERO;

        // Simple month-over-month trend indicator: compares the last point to the one before it
        public String momentum;             // "UP", "DOWN", "FLAT", or "N/A" if not enough data
        public BigDecimal momentumPercent = BigDecimal.ZERO;
    }
}
