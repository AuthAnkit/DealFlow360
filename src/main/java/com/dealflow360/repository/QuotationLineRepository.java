package com.dealflow360.repository;

import com.dealflow360.model.QuotationLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuotationLineRepository extends JpaRepository<QuotationLine, Long> {
    List<QuotationLine> findByQuotationId(Long quotationId);
}
