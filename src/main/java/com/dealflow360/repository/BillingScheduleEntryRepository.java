package com.dealflow360.repository;

import com.dealflow360.model.BillingScheduleEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingScheduleEntryRepository extends JpaRepository<BillingScheduleEntry, Long> {
    List<BillingScheduleEntry> findByQuotationLineIdOrderByBillingDateAsc(Long quotationLineId);
    List<BillingScheduleEntry> findByQuotationLine_Quotation_IdOrderByBillingDateAsc(Long quotationId);
}
