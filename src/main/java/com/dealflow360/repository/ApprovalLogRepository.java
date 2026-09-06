package com.dealflow360.repository;

import com.dealflow360.model.ApprovalLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalLogRepository extends JpaRepository<ApprovalLog, Long> {
    List<ApprovalLog> findByQuotationIdOrderByTimestampAsc(Long quotationId);
}
