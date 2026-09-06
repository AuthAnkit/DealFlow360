package com.dealflow360.dto;

import java.time.LocalDateTime;

/** Response shape for the Automation activity feed - see AutomationController / AutomationScheduler. */
public class AutomationDtos {

    public static class ActivityEntryResponse {
        public Long id;
        public String entityType;
        public Long entityId;
        public String action;
        public String actor;
        public String details;
        public LocalDateTime timestamp;
        public boolean automated; // true when this row was produced by AutomationScheduler, not a person
    }
}
