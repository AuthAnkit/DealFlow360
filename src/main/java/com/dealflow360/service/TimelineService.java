package com.dealflow360.service;

import com.dealflow360.dto.IntelligenceDtos.TimelineEventResponse;
import com.dealflow360.model.ApprovalLog;
import com.dealflow360.model.AuditEntry;
import com.dealflow360.model.NegotiationMessage;
import com.dealflow360.repository.ApprovalLogRepository;
import com.dealflow360.repository.AuditEntryRepository;
import com.dealflow360.repository.NegotiationMessageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Visual Deal Timeline: the complete story of a deal, built entirely from
 * the audit trail, approval log, and negotiation thread that already
 * exist for every other feature - no separate/parallel timeline table.
 */
@Service
public class TimelineService {

    private final AuditEntryRepository auditEntryRepository;
    private final ApprovalLogRepository approvalLogRepository;
    private final NegotiationMessageRepository negotiationMessageRepository;

    public TimelineService(AuditEntryRepository auditEntryRepository, ApprovalLogRepository approvalLogRepository,
                            NegotiationMessageRepository negotiationMessageRepository) {
        this.auditEntryRepository = auditEntryRepository;
        this.approvalLogRepository = approvalLogRepository;
        this.negotiationMessageRepository = negotiationMessageRepository;
    }

    public List<TimelineEventResponse> forQuotation(Long quotationId) {
        List<TimelineEventResponse> events = new ArrayList<>();

        for (AuditEntry entry : auditEntryRepository.findByEntityTypeAndEntityIdOrderByTimestampAsc("Quotation", quotationId)) {
            TimelineEventResponse event = new TimelineEventResponse();
            event.eventName = humanize(entry.getAction());
            event.actor = entry.getActor();
            event.timestamp = entry.getTimestamp();
            event.detail = entry.getDetails();
            event.status = entry.getAction() != null && entry.getAction().startsWith("AUTO_") ? "AUTOMATED" : "MANUAL";
            events.add(event);
        }

        for (ApprovalLog log : approvalLogRepository.findByQuotationIdOrderByTimestampAsc(quotationId)) {
            TimelineEventResponse event = new TimelineEventResponse();
            event.eventName = "Approval - " + humanize(log.getAction().name());
            event.actor = log.getActorUsername();
            event.timestamp = log.getTimestamp();
            event.detail = log.getReason();
            event.status = log.getApproverRole() != null ? log.getApproverRole().name() : null;
            events.add(event);
        }

        for (NegotiationMessage message : negotiationMessageRepository.findByQuotationIdOrderByTimestampAsc(quotationId)) {
            TimelineEventResponse event = new TimelineEventResponse();
            boolean isCounter = message.getMessageType() == NegotiationMessage.MessageType.COUNTER_DISCOUNT;
            event.eventName = (message.getSenderType() == NegotiationMessage.SenderType.CUSTOMER ? "Customer " : "Sales rep ")
                    + (isCounter ? "requested a discount change" : "sent a message");
            event.actor = message.getSenderName();
            event.timestamp = message.getTimestamp();
            event.detail = isCounter
                    ? "Proposed discount: " + message.getProposedDiscountPercent() + "%"
                            + (message.getContent() != null && !message.getContent().isBlank() ? " - " + message.getContent() : "")
                    : message.getContent();
            event.status = message.getMessageType().name();
            events.add(event);
        }

        events.sort(Comparator.comparing(e -> e.timestamp));
        return events;
    }

    private String humanize(String raw) {
        if (raw == null) return "";
        String withoutPrefix = raw.startsWith("AUTO_") ? raw.substring(5) : raw;
        String[] parts = withoutPrefix.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
