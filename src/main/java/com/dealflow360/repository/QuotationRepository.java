package com.dealflow360.repository;

import com.dealflow360.model.Quotation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    List<Quotation> findBySalesRepId(Long salesRepId);
    List<Quotation> findByCustomerId(Long customerId);
    List<Quotation> findByStatus(Quotation.Status status);
}
