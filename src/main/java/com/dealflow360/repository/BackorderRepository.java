package com.dealflow360.repository;

import com.dealflow360.model.Backorder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BackorderRepository extends JpaRepository<Backorder, Long> {
    List<Backorder> findByQuotationIdAndResolvedFalse(Long quotationId);

    /** All unresolved backorders across every quotation - used by the automated consolidation job. */
    List<Backorder> findByResolvedFalse();
}
