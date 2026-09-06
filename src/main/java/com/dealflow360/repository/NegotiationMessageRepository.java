package com.dealflow360.repository;

import com.dealflow360.model.NegotiationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NegotiationMessageRepository extends JpaRepository<NegotiationMessage, Long> {
    List<NegotiationMessage> findByQuotationIdOrderByTimestampAsc(Long quotationId);
}
