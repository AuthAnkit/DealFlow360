package com.dealflow360.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A message on the customer-facing negotiation thread (PDF B8 - "Line
 * level comment and change request tool", "Counter discount proposal
 * field").
 */
@Entity
@Table(name = "negotiation_message")
public class NegotiationMessage {

    public enum SenderType {
        SALES_REP,
        CUSTOMER
    }

    public enum MessageType {
        COMMENT,
        COUNTER_DISCOUNT
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
    private SenderType senderType;

    private String senderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageType messageType;

    @Column(length = 2000)
    private String content;

    @Column(precision = 5, scale = 2)
    private BigDecimal proposedDiscountPercent;

    @ManyToOne
    @JoinColumn(name = "quotation_line_id")
    private QuotationLine quotationLine;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    public NegotiationMessage() {
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

    public SenderType getSenderType() {
        return senderType;
    }

    public void setSenderType(SenderType senderType) {
        this.senderType = senderType;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public BigDecimal getProposedDiscountPercent() {
        return proposedDiscountPercent;
    }

    public void setProposedDiscountPercent(BigDecimal proposedDiscountPercent) {
        this.proposedDiscountPercent = proposedDiscountPercent;
    }

    public QuotationLine getQuotationLine() {
        return quotationLine;
    }

    public void setQuotationLine(QuotationLine quotationLine) {
        this.quotationLine = quotationLine;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
