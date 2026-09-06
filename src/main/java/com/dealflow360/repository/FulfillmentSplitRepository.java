package com.dealflow360.repository;

import com.dealflow360.model.FulfillmentSplit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FulfillmentSplitRepository extends JpaRepository<FulfillmentSplit, Long> {
    List<FulfillmentSplit> findByQuotationId(Long quotationId);
    List<FulfillmentSplit> findByDeliveredFalseAndExpectedDeliveryDateBefore(java.time.LocalDate date);
}
