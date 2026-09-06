package com.dealflow360.service;

import com.dealflow360.dto.DashboardDtos;
import com.dealflow360.dto.IntelligenceDtos.DealHealthScoreResponse;
import com.dealflow360.dto.IntelligenceDtos.ScoreFactor;
import com.dealflow360.model.ApprovalLog;
import com.dealflow360.model.Backorder;
import com.dealflow360.model.FulfillmentSplit;
import com.dealflow360.model.NegotiationMessage;
import com.dealflow360.model.Quotation;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.repository.ApprovalLogRepository;
import com.dealflow360.repository.BackorderRepository;
import com.dealflow360.repository.FulfillmentSplitRepository;
import com.dealflow360.repository.NegotiationMessageRepository;
import com.dealflow360.repository.QuotationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deal Health & Anomaly Dashboard logic (PDF B9): stalled deals,
 * discount anomalies vs. a rep's own historical average, and delivery
 * slippage - each backed by live data, not static numbers.
 * <p>
 * Also owns the (added) per-deal 0-100 Deal Health Score and the
 * portfolio-wide margin-anomaly / negotiation-loop / approval-delay
 * anomaly detectors - all deterministic and explainable, reusing the same
 * repositories and calculators as the rest of the app rather than a
 * separate "AI" black box.
 */
@Service
public class DealHealthService {

    /** A quotation with no activity for this many days (and not yet closed) is considered "stalled". */
    private static final long STALLED_THRESHOLD_DAYS = 3;

    /** A quotation's average discount more than this many points above the rep's own historical average is an anomaly. */
    private static final BigDecimal ANOMALY_THRESHOLD_POINTS = BigDecimal.valueOf(8);

    /** Recommended minimum deal margin - also shown by the Deal Copilot. */
    private static final BigDecimal MARGIN_TARGET_PERCENT = BigDecimal.valueOf(20);

    /** A deal's margin this many points below the rep's own historical average margin is a margin anomaly. */
    private static final BigDecimal MARGIN_ANOMALY_THRESHOLD_POINTS = BigDecimal.valueOf(10);

    /** This many or more customer counter-discount rounds on one quotation counts as a "negotiation loop". */
    private static final int NEGOTIATION_LOOP_THRESHOLD = 3;

    /** A quotation waiting this many days or more in PENDING_APPROVAL is flagged as an approval delay. */
    private static final long APPROVAL_DELAY_THRESHOLD_DAYS = 2;

    private final QuotationRepository quotationRepository;
    private final FulfillmentSplitRepository fulfillmentSplitRepository;
    private final BackorderRepository backorderRepository;
    private final ApprovalLogRepository approvalLogRepository;
    private final NegotiationMessageRepository negotiationMessageRepository;
    private final AuditService auditService;

    public DealHealthService(QuotationRepository quotationRepository,
                              FulfillmentSplitRepository fulfillmentSplitRepository,
                              BackorderRepository backorderRepository,
                              ApprovalLogRepository approvalLogRepository,
                              NegotiationMessageRepository negotiationMessageRepository,
                              AuditService auditService) {
        this.quotationRepository = quotationRepository;
        this.fulfillmentSplitRepository = fulfillmentSplitRepository;
        this.backorderRepository = backorderRepository;
        this.approvalLogRepository = approvalLogRepository;
        this.negotiationMessageRepository = negotiationMessageRepository;
        this.auditService = auditService;
    }

    public DashboardDtos.DealHealthResponse compute() {
        List<Quotation> all = quotationRepository.findAll();
        DashboardDtos.DealHealthResponse response = new DashboardDtos.DealHealthResponse();
        response.stalledDeals = new ArrayList<>();
        response.discountAnomalies = new ArrayList<>();
        response.deliverySlippages = new ArrayList<>();
        response.marginAnomalies = new ArrayList<>();
        response.negotiationLoops = new ArrayList<>();
        response.approvalDelays = new ArrayList<>();

        int open = 0;
        BigDecimal pipelineValue = BigDecimal.ZERO;

        for (Quotation q : all) {
            boolean isOpen = q.getStatus() != Quotation.Status.CONFIRMED && q.getStatus() != Quotation.Status.REJECTED;
            if (isOpen) {
                open++;
                pipelineValue = pipelineValue.add(q.totalAmount());

                long daysSinceUpdate = ChronoUnit.DAYS.between(q.getUpdatedAt(), LocalDateTime.now());
                if (daysSinceUpdate >= STALLED_THRESHOLD_DAYS) {
                    response.stalledDeals.add(new DashboardDtos.Alert(q.getId(), q.getCustomer().getName(),
                            "STALLED_DEAL",
                            "No activity for " + daysSinceUpdate + " day(s) (status: " + q.getStatus() + ")",
                            daysSinceUpdate >= STALLED_THRESHOLD_DAYS * 2 ? "HIGH" : "MEDIUM"));
                }

                if (q.getStatus() == Quotation.Status.PENDING_APPROVAL) {
                    long daysPending = ChronoUnit.DAYS.between(q.getUpdatedAt(), LocalDateTime.now());
                    if (daysPending >= APPROVAL_DELAY_THRESHOLD_DAYS) {
                        response.approvalDelays.add(new DashboardDtos.Alert(q.getId(), q.getCustomer().getName(),
                                "APPROVAL_DELAY",
                                "Waiting on " + q.getCurrentApprovalStep() + " approval for " + daysPending + " day(s)",
                                daysPending >= APPROVAL_DELAY_THRESHOLD_DAYS * 2 ? "HIGH" : "MEDIUM"));
                    }
                }
            }

            BigDecimal discountAnomaly = discountAnomalyOverage(q);
            if (discountAnomaly != null) {
                response.discountAnomalies.add(new DashboardDtos.Alert(q.getId(), q.getCustomer().getName(),
                        "DISCOUNT_ANOMALY",
                        "Average discount " + q.averageDiscountPercent() + "% is " + discountAnomaly + " points above "
                                + q.getSalesRep().getFullName() + "'s historical average",
                        discountAnomaly.compareTo(BigDecimal.valueOf(15)) > 0 ? "HIGH" : "MEDIUM"));
            }

            BigDecimal marginAnomaly = marginAnomalyShortfall(q);
            if (marginAnomaly != null) {
                response.marginAnomalies.add(new DashboardDtos.Alert(q.getId(), q.getCustomer().getName(),
                        "MARGIN_ANOMALY",
                        "Margin " + marginPercent(q) + "% is " + marginAnomaly + " points below "
                                + q.getSalesRep().getFullName() + "'s historical average margin",
                        marginAnomaly.compareTo(BigDecimal.valueOf(15)) > 0 ? "HIGH" : "MEDIUM"));
            }

            int counterRounds = countCounterDiscountRounds(q.getId());
            if (counterRounds >= NEGOTIATION_LOOP_THRESHOLD) {
                response.negotiationLoops.add(new DashboardDtos.Alert(q.getId(), q.getCustomer().getName(),
                        "NEGOTIATION_LOOP",
                        counterRounds + " counter-discount rounds on this quotation - consider a structured alternative",
                        counterRounds >= NEGOTIATION_LOOP_THRESHOLD * 2 ? "HIGH" : "MEDIUM"));
            }
        }

        List<FulfillmentSplit> slipped = fulfillmentSplitRepository.findByDeliveredFalseAndExpectedDeliveryDateBefore(LocalDate.now());
        for (FulfillmentSplit split : slipped) {
            response.deliverySlippages.add(new DashboardDtos.Alert(split.getQuotation().getId(),
                    split.getQuotation().getCustomer().getName(),
                    "DELIVERY_SLIPPAGE",
                    split.getProduct().getName() + " from " + split.getWarehouse().getName()
                            + " was due " + split.getExpectedDeliveryDate() + " and is not yet marked delivered",
                    "HIGH"));
        }

        response.openQuotations = open;
        response.pipelineValue = pipelineValue;
        return response;
    }

    /** Returns the overage in points if this quotation's discount is anomalous for its rep, otherwise null. */
    private BigDecimal discountAnomalyOverage(Quotation quotation) {
        List<Quotation> repHistory = quotationRepository.findBySalesRepId(quotation.getSalesRep().getId()).stream()
                .filter(q -> q.getStatus() == Quotation.Status.CONFIRMED)
                .filter(q -> !q.getId().equals(quotation.getId()))
                .toList();

        if (repHistory.size() < 2 || quotation.getLines().isEmpty()) return null; // not enough history yet

        BigDecimal historicalAverage = repHistory.stream()
                .map(Quotation::averageDiscountPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(repHistory.size()), 2, RoundingMode.HALF_UP);

        BigDecimal current = quotation.averageDiscountPercent();
        BigDecimal overage = current.subtract(historicalAverage);
        return overage.compareTo(ANOMALY_THRESHOLD_POINTS) > 0 ? overage.setScale(2, RoundingMode.HALF_UP) : null;
    }

    /** Returns the shortfall in points if this quotation's margin is anomalously low for its rep, otherwise null. */
    private BigDecimal marginAnomalyShortfall(Quotation quotation) {
        if (quotation.getLines().isEmpty()) return null;

        List<Quotation> repHistory = quotationRepository.findBySalesRepId(quotation.getSalesRep().getId()).stream()
                .filter(q -> q.getStatus() == Quotation.Status.CONFIRMED)
                .filter(q -> !q.getId().equals(quotation.getId()))
                .filter(q -> !q.getLines().isEmpty())
                .toList();

        if (repHistory.size() < 2) return null; // not enough history yet

        BigDecimal historicalAverage = repHistory.stream()
                .map(this::marginPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(repHistory.size()), 2, RoundingMode.HALF_UP);

        BigDecimal current = marginPercent(quotation);
        BigDecimal shortfall = historicalAverage.subtract(current);
        return shortfall.compareTo(MARGIN_ANOMALY_THRESHOLD_POINTS) > 0 ? shortfall.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private int countCounterDiscountRounds(Long quotationId) {
        return (int) negotiationMessageRepository.findByQuotationIdOrderByTimestampAsc(quotationId).stream()
                .filter(m -> m.getMessageType() == NegotiationMessage.MessageType.COUNTER_DISCOUNT)
                .count();
    }

    /** Deal margin as a percentage of total amount - 0 for an empty or zero-value quotation. */
    private BigDecimal marginPercent(Quotation quotation) {
        BigDecimal total = quotation.totalAmount();
        if (total.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal margin = quotation.getLines().stream().map(QuotationLine::marginAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return margin.divide(total, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Deterministic 0-100 Deal Health Score. Every deduction is a plain, explainable rule over real
     * data (no randomness, no "AI" pretense) - see the returned factor breakdown for exactly why the
     * score is what it is.
     */
    public DealHealthScoreResponse computeScore(Quotation quotation) {
        List<ScoreFactor> factors = new ArrayList<>();
        int score = 100;

        BigDecimal risk = quotation.getBlendedRiskScore();
        if (risk.compareTo(BigDecimal.ZERO) > 0) {
            int deduction = Math.min(25, risk.intValue());
            score -= deduction;
            factors.add(factor("Blended discount risk score is " + risk, -deduction));
        }

        BigDecimal margin = marginPercent(quotation);
        if (!quotation.getLines().isEmpty() && margin.compareTo(MARGIN_TARGET_PERCENT) < 0) {
            int deduction = Math.min(20, MARGIN_TARGET_PERCENT.subtract(margin).intValue());
            score -= deduction;
            factors.add(factor("Margin " + margin + "% is below the recommended " + MARGIN_TARGET_PERCENT + "% threshold", -deduction));
        }

        long daysSinceUpdate = ChronoUnit.DAYS.between(quotation.getUpdatedAt(), LocalDateTime.now());
        if (daysSinceUpdate >= STALLED_THRESHOLD_DAYS) {
            int deduction = (int) Math.min(15, daysSinceUpdate * 2);
            score -= deduction;
            factors.add(factor("Inactive for " + daysSinceUpdate + " day(s)", -deduction));
        }

        List<ApprovalLog> approvalHistory = approvalLogRepository.findByQuotationIdOrderByTimestampAsc(quotation.getId());
        long approvalCycles = approvalHistory.stream()
                .filter(l -> l.getAction() == ApprovalLog.Action.REJECT || l.getAction() == ApprovalLog.Action.RETURN_FOR_REVISION)
                .count();
        if (approvalCycles > 0) {
            int deduction = (int) Math.min(15, approvalCycles * 5);
            score -= deduction;
            factors.add(factor(approvalCycles + " approval rejection/revision cycle(s)", -deduction));
        }

        if (quotation.getStatus() == Quotation.Status.PENDING_APPROVAL) {
            long daysPending = ChronoUnit.DAYS.between(quotation.getUpdatedAt(), LocalDateTime.now());
            if (daysPending >= APPROVAL_DELAY_THRESHOLD_DAYS) {
                int deduction = (int) Math.min(10, daysPending * 3);
                score -= deduction;
                factors.add(factor("Waiting " + daysPending + " day(s) for " + quotation.getCurrentApprovalStep() + " approval", -deduction));
            }
        }

        int counterRounds = countCounterDiscountRounds(quotation.getId());
        if (counterRounds > 0) {
            int deduction = Math.min(12, counterRounds * 3);
            score -= deduction;
            factors.add(factor(counterRounds + " customer negotiation round(s)", -deduction));
        }

        List<Backorder> unresolvedBackorders = backorderRepository.findByQuotationIdAndResolvedFalse(quotation.getId());
        if (!unresolvedBackorders.isEmpty()) {
            int units = unresolvedBackorders.stream().mapToInt(Backorder::getQuantityPending).sum();
            int deduction = 8;
            score -= deduction;
            factors.add(factor(units + " unit(s) on backorder", -deduction));
        }

        long warehousesUsed = fulfillmentSplitRepository.findByQuotationId(quotation.getId()).stream()
                .map(s -> s.getWarehouse().getId()).distinct().count();
        if (warehousesUsed > 1) {
            int deduction = 3;
            score -= deduction;
            factors.add(factor("Requires fulfillment from " + warehousesUsed + " warehouses", -deduction));
        }

        boolean hasSlippage = fulfillmentSplitRepository.findByDeliveredFalseAndExpectedDeliveryDateBefore(LocalDate.now()).stream()
                .anyMatch(s -> s.getQuotation().getId().equals(quotation.getId()));
        if (hasSlippage) {
            int deduction = 10;
            score -= deduction;
            factors.add(factor("At least one shipment has slipped past its expected delivery date", -deduction));
        }

        BigDecimal discountAnomaly = discountAnomalyOverage(quotation);
        if (discountAnomaly != null) {
            int deduction = Math.min(15, discountAnomaly.intValue());
            score -= deduction;
            factors.add(factor("Discount is " + discountAnomaly + " points above this rep's historical average", -deduction));
        }

        score = Math.max(0, Math.min(100, score));

        DealHealthScoreResponse response = new DealHealthScoreResponse();
        response.quotationId = quotation.getId();
        response.score = score;
        response.band = score >= 80 ? "HEALTHY" : score >= 50 ? "ATTENTION_NEEDED" : "AT_RISK";
        response.factors = factors;
        response.recommendedActions = recommendActions(quotation, factors, score);
        return response;
    }

    private ScoreFactor factor(String label, int points) {
        ScoreFactor f = new ScoreFactor();
        f.label = label;
        f.points = points;
        return f;
    }

    private List<String> recommendActions(Quotation quotation, List<ScoreFactor> factors, int score) {
        List<String> actions = new ArrayList<>();
        for (ScoreFactor f : factors) {
            if (f.label.startsWith("Blended discount risk") || f.label.contains("historical average")) {
                actions.add("Consider reducing the discount, or flag for manager attention.");
            } else if (f.label.startsWith("Margin")) {
                actions.add("Consider reducing the discount or adding a higher-margin line to improve margin.");
            } else if (f.label.startsWith("Inactive")) {
                actions.add("Follow up with the customer.");
            } else if (f.label.contains("rejection/revision")) {
                actions.add("Review why this quotation was rejected or returned before resubmitting.");
            } else if (f.label.contains("Waiting") && f.label.contains("approval")) {
                actions.add("Request manager/finance attention to unblock the pending approval.");
            } else if (f.label.contains("negotiation round")) {
                actions.add("Use the Negotiation Assistant to propose a structured alternative and close the loop.");
            } else if (f.label.contains("backorder") || f.label.contains("warehouses") || f.label.contains("delivery date")) {
                actions.add("Review warehouse availability and the fulfillment plan.");
            }
        }
        if (actions.isEmpty()) {
            actions.add("No action needed - this deal is healthy.");
        }
        return actions.stream().distinct().toList();
    }

    /** "An automated nudge or escalation action can be triggered from an alert." Logged, no real email is sent in this build. */
    public void nudge(Long quotationId, String message, String actorUsername) {
        auditService.log("Quotation", quotationId, "NUDGE_SENT", actorUsername,
                message == null || message.isBlank() ? "Reminder nudge sent to sales rep" : message);
    }
}
