package com.dealflow360.service;

import com.dealflow360.model.*;
import com.dealflow360.repository.DiscountCeilingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Implements the "Blended Discount Risk Score" described in the PDF
 * (section 10). The idea, in the PDF's own words:
 * <p>
 * "Different products are allowed different discount limits, and the
 * system checks every line against its own limit, not just one overall
 * limit for the whole order... The blended score looks at the total
 * pattern across the order, not just the single worst line, so small
 * violations spread across many lines cannot slip through unnoticed."
 * <p>
 * Concretely: for every line, find the ceiling for (customer tier,
 * product category); if the line's discount exceeds that ceiling, the
 * "overage" (in percentage points) counts toward the blended score. The
 * blended score is the sum of all line overages. A score of 0 means every
 * line is within its own limit and no approval is required.
 */
@Service
public class DiscountRiskService {

    private final DiscountCeilingRepository discountCeilingRepository;

    public DiscountRiskService(DiscountCeilingRepository discountCeilingRepository) {
        this.discountCeilingRepository = discountCeilingRepository;
    }

    /** Looks up the ceiling for this tier/category, falling back to the tier's DEFAULT ceiling, then to 0. */
    public BigDecimal ceilingFor(CustomerTier tier, String category) {
        return discountCeilingRepository.findByTierAndCategory(tier, category)
                .map(DiscountCeiling::getMaxDiscountPercent)
                .orElseGet(() -> discountCeilingRepository.findByTierAndCategory(tier, DiscountCeiling.DEFAULT_CATEGORY)
                        .map(DiscountCeiling::getMaxDiscountPercent)
                        .orElse(BigDecimal.ZERO));
    }

    /** Overage (percentage points) for one line, or 0 if the line is within its ceiling. */
    public BigDecimal lineOverage(CustomerTier tier, QuotationLine line) {
        BigDecimal ceiling = ceilingFor(tier, line.getProduct().getCategory());
        BigDecimal overage = line.getDiscountPercent().subtract(ceiling);
        return overage.compareTo(BigDecimal.ZERO) > 0 ? overage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /** Sum of overages across every line on the quotation - the "blended" risk score. */
    public BigDecimal blendedRiskScore(Quotation quotation) {
        CustomerTier tier = quotation.getCustomer().getTier();
        BigDecimal total = BigDecimal.ZERO;
        for (QuotationLine line : quotation.getLines()) {
            total = total.add(lineOverage(tier, line));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
