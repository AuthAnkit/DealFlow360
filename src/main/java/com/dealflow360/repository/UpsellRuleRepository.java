package com.dealflow360.repository;

import com.dealflow360.model.UpsellRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UpsellRuleRepository extends JpaRepository<UpsellRule, Long> {
    List<UpsellRule> findByBaseProductId(Long baseProductId);
    List<UpsellRule> findBySuggestedProductId(Long suggestedProductId);
}
