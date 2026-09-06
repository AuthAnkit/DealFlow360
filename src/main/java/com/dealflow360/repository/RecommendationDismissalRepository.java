package com.dealflow360.repository;

import com.dealflow360.model.RecommendationDismissal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecommendationDismissalRepository extends JpaRepository<RecommendationDismissal, Long> {
    List<RecommendationDismissal> findByQuotationId(Long quotationId);
    void deleteByQuotationIdAndRuleId(Long quotationId, Long ruleId);
}
