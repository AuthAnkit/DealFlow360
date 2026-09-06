package com.dealflow360.repository;

import com.dealflow360.model.ApprovalChainRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalChainRuleRepository extends JpaRepository<ApprovalChainRule, Long> {
    List<ApprovalChainRule> findAllByOrderByMinRiskScoreAsc();
}
