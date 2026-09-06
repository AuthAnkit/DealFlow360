package com.dealflow360.service;

import com.dealflow360.dto.DashboardDtos;
import com.dealflow360.dto.QuotationDtos;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.repository.QuotationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Sales reporting with the filters called out in the PDF (A7): Period,
 * Sales Team/Rep, Approval Status, Product/Category.
 */
@Service
public class ReportService {

    private final QuotationRepository quotationRepository;

    public ReportService(QuotationRepository quotationRepository) {
        this.quotationRepository = quotationRepository;
    }

    public DashboardDtos.ReportResponse run(DashboardDtos.ReportFilter filter) {
        List<Quotation> quotations = quotationRepository.findAll();

        LocalDate from = (filter.fromDate != null && !filter.fromDate.isBlank()) ? LocalDate.parse(filter.fromDate) : null;
        LocalDate to = (filter.toDate != null && !filter.toDate.isBlank()) ? LocalDate.parse(filter.toDate) : null;

        List<Quotation> filtered = quotations.stream()
                .filter(q -> from == null || !q.getCreatedAt().toLocalDate().isBefore(from))
                .filter(q -> to == null || !q.getCreatedAt().toLocalDate().isAfter(to))
                .filter(q -> filter.salesRepId == null || filter.salesRepId.equals(q.getSalesRep().getId()))
                .filter(q -> filter.status == null || filter.status.isBlank() || q.getStatus().name().equalsIgnoreCase(filter.status))
                .filter(q -> filter.category == null || filter.category.isBlank()
                        || q.getLines().stream().anyMatch(l -> l.getProduct().getCategory().equalsIgnoreCase(filter.category)))
                .toList();

        DashboardDtos.ReportResponse response = new DashboardDtos.ReportResponse();
        response.totalQuotations = filtered.size();
        response.totalValue = filtered.stream().map(Quotation::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        response.averageDiscountPercent = filtered.isEmpty() ? BigDecimal.ZERO
                : filtered.stream().map(Quotation::averageDiscountPercent).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(filtered.size()), 2, RoundingMode.HALF_UP);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Quotation q : filtered) {
            byStatus.merge(q.getStatus().name(), 1L, Long::sum);
        }
        response.countByStatus = byStatus;

        Map<String, DashboardDtos.ProductStat> productStats = new LinkedHashMap<>();
        for (Quotation q : filtered) {
            for (QuotationLine line : q.getLines()) {
                DashboardDtos.ProductStat stat = productStats.computeIfAbsent(line.getProduct().getName(), name -> {
                    DashboardDtos.ProductStat s = new DashboardDtos.ProductStat();
                    s.productName = name;
                    s.unitsSold = 0;
                    s.totalDiscountGiven = BigDecimal.ZERO;
                    s.revenue = BigDecimal.ZERO;
                    return s;
                });
                stat.unitsSold += line.getQuantity();
                stat.revenue = stat.revenue.add(line.lineTotal());
                BigDecimal discountAmount = line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()))
                        .multiply(line.getDiscountPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                stat.totalDiscountGiven = stat.totalDiscountGiven.add(discountAmount);
            }
        }
        List<DashboardDtos.ProductStat> topProducts = new ArrayList<>(productStats.values());
        topProducts.sort((a, b) -> b.revenue.compareTo(a.revenue));
        response.topProducts = topProducts;

        List<QuotationDtos.QuotationSummaryResponse> summaries = new ArrayList<>();
        for (Quotation q : filtered) {
            QuotationDtos.QuotationSummaryResponse s = new QuotationDtos.QuotationSummaryResponse();
            s.id = q.getId();
            s.customerName = q.getCustomer().getName();
            s.customerTier = q.getCustomer().getTier().name();
            s.status = q.getStatus().name();
            s.totalAmount = q.totalAmount();
            s.blendedRiskScore = q.getBlendedRiskScore();
            s.salesRepName = q.getSalesRep().getFullName();
            s.updatedAt = q.getUpdatedAt();
            summaries.add(s);
        }
        summaries.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
        response.quotations = summaries;

        return response;
    }
}
