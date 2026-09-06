package com.dealflow360.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Immutable audit trail entry for a discount approval decision (PDF A3
 * Notes - "All approvals, rejections, and edits must be logged with user,
 * timestamp, and reason").
 */
@Entity
@Table(name = "approval_log")
public class ApprovalLog {

    public enum Action {
        SUBMITTED,
        APPROVE,
        REJECT,
        RETURN_FOR_REVISION,
        /** A sales rep reopened a rejected/approved quotation as a Draft to revise it - the previous decision no longer applies. */
        REOPENED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "quotation_id")
    private Quotation quotation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role approverRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    private String actorUsername;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    public ApprovalLog() {
    }

    public ApprovalLog(Quotation quotation, Role approverRole, Action action, String actorUsername, String reason) {
        this.quotation = quotation;
        this.approverRole = approverRole;
        this.action = action;
        this.actorUsername = actorUsername;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Quotation getQuotation() {
        return quotation;
    }

    public void setQuotation(Quotation quotation) {
        this.quotation = quotation;
    }

    public Role getApproverRole() {
        return approverRole;
    }

    public void setApproverRole(Role approverRole) {
        this.approverRole = approverRole;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
