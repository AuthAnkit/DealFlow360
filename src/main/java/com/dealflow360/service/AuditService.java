package com.dealflow360.service;

import com.dealflow360.model.AuditEntry;
import com.dealflow360.repository.AuditEntryRepository;
import org.springframework.stereotype.Service;

/** Small helper so every service can log a state change in one line. */
@Service
public class AuditService {

    private final AuditEntryRepository auditEntryRepository;

    public AuditService(AuditEntryRepository auditEntryRepository) {
        this.auditEntryRepository = auditEntryRepository;
    }

    public void log(String entityType, Long entityId, String action, String actor, String details) {
        auditEntryRepository.save(new AuditEntry(entityType, entityId, action, actor, details));
    }
}
