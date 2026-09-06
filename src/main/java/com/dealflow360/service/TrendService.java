package com.dealflow360.service;

import com.dealflow360.dto.TrendDtos.Dimension;
import com.dealflow360.dto.TrendDtos.TrendFilter;
import com.dealflow360.dto.TrendDtos.TrendPoint;
import com.dealflow360.dto.TrendDtos.TrendResponse;
import com.dealflow360.model.AppUser;
import com.dealflow360.model.Product;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.repository.AppUserRepository;
import com.dealflow360.repository.ProductRepository;
import com.dealflow360.repository.QuotationRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Last N months of data for some particular thing, as numbers and a graph."
 * Buckets quotations/lines by calendar month for a chosen dimension
 * (overall business / one product / one category / one sales rep) so the
 * frontend can render both the headline numbers and a simple chart from a
 * single call - no separate "give me the graph" endpoint needed.
 */
@Service
public class TrendService {

    private static final int MAX_MONTHS = 24;
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    private final QuotationRepository quotationRepository;
    private final ProductRepository productRepository;
    private final AppUserRepository appUserRepository;

    public TrendService(QuotationRepository quotationRepository, ProductRepository productRepository,
                         AppUserRepository appUserRepository) {
        this.quotationRepository = quotationRepository;
        this.productRepository = productRepository;
        this.appUserRepository = appUserRepository;
    }

    public TrendResponse run(TrendFilter filter) {
        int months = filter.months <= 0 ? 3 : Math.min(filter.months, MAX_MONTHS);
        Dimension dimension = filter.dimension == null ? Dimension.OVERALL : filter.dimension;

        String dimensionLabel = "Overall business";
        Product product = null;
        AppUser salesRep = null;

        if (dimension == Dimension.PRODUCT) {
            if (filter.dimensionId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dimensionId (productId) is required for PRODUCT trends");
            }
            product = productRepository.findById(filter.dimensionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
            dimensionLabel = product.getName();
        } else if (dimension == Dimension.CATEGORY) {
            if (filter.category == null || filter.category.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category is required for CATEGORY trends");
            }
            dimensionLabel = filter.category;
        } else if (dimension == Dimension.SALES_REP) {
            if (filter.dimensionId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dimensionId (salesRepId) is required for SALES_REP trends");
            }
            salesRep = appUserRepository.findById(filter.dimensionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sales rep not found"));
            dimensionLabel = salesRep.getFullName();
        }

        // Build the ordered list of month buckets: oldest -> newest, ending with the current month.
        LocalDate today = LocalDate.now();
        Map<String, TrendPoint> buckets = new LinkedHashMap<>();
        for (int i = months - 1; i >= 0; i--) {
            LocalDate monthStart = today.withDayOfMonth(1).minusMonths(i);
            TrendPoint point = new TrendPoint();
            point.monthKey = monthStart.format(MONTH_KEY);
            point.monthLabel = monthStart.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + monthStart.getYear();
            buckets.put(point.monthKey, point);
        }
        String earliestKey = today.withDayOfMonth(1).minusMonths(months - 1).format(MONTH_KEY);

        List<Quotation> allQuotations = quotationRepository.findAll();
        int totalQuotations = 0;
        int totalUnitsSold = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalDiscountSum = BigDecimal.ZERO;
        int totalDiscountLineCount = 0;

        for (Quotation q : allQuotations) {
            if (dimension == Dimension.SALES_REP) {
                if (q.getSalesRep() == null || !q.getSalesRep().getId().equals(salesRep.getId())) continue;
            }

            LocalDateTime createdAt = q.getCreatedAt();
            if (createdAt == null) continue;
            String key = createdAt.format(MONTH_KEY);
            if (key.compareTo(earliestKey) < 0) continue; // older than the requested window
            TrendPoint point = buckets.get(key);
            if (point == null) continue; // outside the window (e.g. future-dated test data)

            List<QuotationLine> matchingLines = new ArrayList<>();
            for (QuotationLine line : q.getLines()) {
                if (dimension == Dimension.PRODUCT) {
                    if (line.getProduct() == null || !line.getProduct().getId().equals(product.getId())) continue;
                } else if (dimension == Dimension.CATEGORY) {
                    if (line.getProduct() == null || !filter.category.equalsIgnoreCase(line.getProduct().getCategory())) continue;
                }
                matchingLines.add(line);
            }

            // For OVERALL/SALES_REP every quotation counts (even one with zero lines yet); for
            // PRODUCT/CATEGORY only a quotation that actually contains a matching line counts.
            boolean countsForThisDimension = dimension == Dimension.OVERALL || dimension == Dimension.SALES_REP
                    || !matchingLines.isEmpty();

            if (!countsForThisDimension) continue;

            List<QuotationLine> linesForTotals = (dimension == Dimension.PRODUCT || dimension == Dimension.CATEGORY)
                    ? matchingLines : q.getLines();

            point.quotationsCreated++;
            totalQuotations++;
            if (q.getConfirmedAt() != null && q.getConfirmedAt().format(MONTH_KEY).equals(key)) {
                point.quotationsConfirmed++;
            }

            for (QuotationLine line : linesForTotals) {
                point.unitsSold += line.getQuantity();
                point.revenue = point.revenue.add(line.lineTotal());
                totalUnitsSold += line.getQuantity();
                totalRevenue = totalRevenue.add(line.lineTotal());
                totalDiscountSum = totalDiscountSum.add(line.getDiscountPercent());
                totalDiscountLineCount++;
            }
        }

        // Average discount per month, computed from the lines already attributed to that month above.
        Map<String, BigDecimal> monthDiscountSum = new LinkedHashMap<>();
        Map<String, Integer> monthDiscountCount = new LinkedHashMap<>();
        for (Quotation q : allQuotations) {
            if (dimension == Dimension.SALES_REP && (q.getSalesRep() == null || !q.getSalesRep().getId().equals(salesRep.getId()))) continue;
            LocalDateTime createdAt = q.getCreatedAt();
            if (createdAt == null) continue;
            String key = createdAt.format(MONTH_KEY);
            if (!buckets.containsKey(key)) continue;
            for (QuotationLine line : q.getLines()) {
                if (dimension == Dimension.PRODUCT && (line.getProduct() == null || !line.getProduct().getId().equals(product.getId()))) continue;
                if (dimension == Dimension.CATEGORY && (line.getProduct() == null || !filter.category.equalsIgnoreCase(line.getProduct().getCategory()))) continue;
                monthDiscountSum.merge(key, line.getDiscountPercent(), BigDecimal::add);
                monthDiscountCount.merge(key, 1, Integer::sum);
            }
        }
        for (TrendPoint point : buckets.values()) {
            int count = monthDiscountCount.getOrDefault(point.monthKey, 0);
            if (count > 0) {
                point.averageDiscountPercent = monthDiscountSum.get(point.monthKey)
                        .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            }
        }

        TrendResponse response = new TrendResponse();
        response.dimensionLabel = dimensionLabel;
        response.months = months;
        response.points = new ArrayList<>(buckets.values());
        response.totalQuotations = totalQuotations;
        response.totalUnitsSold = totalUnitsSold;
        response.totalRevenue = totalRevenue;
        response.averageDiscountPercent = totalDiscountLineCount == 0 ? BigDecimal.ZERO
                : totalDiscountSum.divide(BigDecimal.valueOf(totalDiscountLineCount), 2, RoundingMode.HALF_UP);

        // Momentum: compare the last two points' revenue so the UI can show an up/down/flat cue.
        if (response.points.size() >= 2) {
            BigDecimal last = response.points.get(response.points.size() - 1).revenue;
            BigDecimal prev = response.points.get(response.points.size() - 2).revenue;
            if (prev.compareTo(BigDecimal.ZERO) == 0) {
                response.momentum = last.compareTo(BigDecimal.ZERO) > 0 ? "UP" : "FLAT";
                response.momentumPercent = last.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
            } else {
                BigDecimal changePercent = last.subtract(prev).divide(prev, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                response.momentumPercent = changePercent.setScale(1, RoundingMode.HALF_UP);
                if (changePercent.compareTo(BigDecimal.valueOf(1)) > 0) response.momentum = "UP";
                else if (changePercent.compareTo(BigDecimal.valueOf(-1)) < 0) response.momentum = "DOWN";
                else response.momentum = "FLAT";
            }
        } else {
            response.momentum = "N/A";
        }

        return response;
    }
}
