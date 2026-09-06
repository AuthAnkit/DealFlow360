package com.dealflow360.repository;

import com.dealflow360.model.AuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {
    List<AuditEntry> findByEntityTypeAndEntityIdOrderByTimestampAsc(String entityType, Long entityId);

    /** Most recent occurrence of a given action on an entity - used to rate-limit automated jobs. */
    Optional<AuditEntry> findTopByEntityTypeAndEntityIdAndActionOrderByTimestampDesc(String entityType, Long entityId, String action);

    /** Recent activity feed for the Automation page - newest first. */
    List<AuditEntry> findTop200ByOrderByTimestampDesc();

    /** Recent activity for just the automated jobs (action prefix "AUTO_") - see AutomationScheduler. */
    List<AuditEntry> findTop200ByActionStartingWithOrderByTimestampDesc(String actionPrefix);
}
