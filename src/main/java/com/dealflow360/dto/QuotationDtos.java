package com.dealflow360.dto;

import com.dealflow360.model.QuotationLine;

import com.dealflow360.model.CustomerTier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response / request shapes for the Quotation aggregate. Built explicitly
 * (instead of serializing JPA entities straight from the controller) so
 * we never hit LazyInitializationException and never leak internal-only
 * fields (e.g. cost price, passwords).
 */
public class QuotationDtos {

    public static class CreateQuotationRequest {
        public Long customerId;
        /** Optional (Admin / Sales Manager only): username of the sales rep to own the deal; defaults to the caller. */
        public String salesRepUsername;
    }

    /** Customer-portal-safe view of a product: no cost/margin, just what a shopper needs to add it to their list. */
    public static class PortalProductResponse {
        public Long id;
        public String name;
        public String category;
        public String unit;
        public BigDecimal price;
        public BigDecimal taxPercent;
        public String description;
        public String imageUrl;
        /** Subscription plans this product is sold on (empty for a one-time product) - PDF A5. */
        public List<PortalPlanResponse> plans;
    }

    public static class PortalPlanResponse {
        public Long id;
        public String name;
        public String billingCycle;
        public BigDecimal pricePerCycle;
    }

    /** One line of a customer's self-service "give us a list" request. */
    public static class PortalRequestLine {
        public Long productId;
        public int quantity = 1;
        public Long subscriptionPlanId; // optional - for a subscription product; the product's default plan is used when absent
    }

    /** PDF B8 self-service extension: customer builds their own list from the catalog and submits it,
     *  instead of only reacting to a quotation a sales rep already built. */
    public static class PortalRequestQuoteRequest {
        public List<PortalRequestLine> items;
        public String note;
    }

    public static class AddLineRequest {
        public Long productId;
        public int quantity;
        public BigDecimal discountPercent = BigDecimal.ZERO;
        public QuotationLine.LineType lineType = QuotationLine.LineType.ONE_TIME;
        public Long subscriptionPlanId; // required when lineType == RECURRING
    }

    public static class UpdateLineRequest {
        public Integer quantity;
        public BigDecimal discountPercent;
    }

    /** PDF B3 - "Apply line level or order level discounts": one discount applied to every line at once. */
    public static class OrderDiscountRequest {
        public BigDecimal discountPercent;
    }

    /** PDF A2 - tier price list row. */
    public static class PriceListEntryRequest {
        public CustomerTier tier;
        public Long productId;
        public BigDecimal price;
        public String currency;
    }

    public static class ApprovalActionRequest {
        public String reason = "";
    }

    public static class LineResponse {
        public Long id;
        public Long productId;
        public String productName;
        public String category;
        public String productImageUrl;
        public int quantity;
        public BigDecimal unitPrice;
        public BigDecimal discountPercent;
        public BigDecimal ceilingPercent;
        public boolean overCeiling;
        public String lineType;
        public Long subscriptionPlanId;
        public BigDecimal lineTotal;
        public BigDecimal marginAmount;
    }

    public static class QuotationResponse {
        public Long id;
        public Long customerId;
        public String customerName;
        public String customerTier;
        public Long salesRepId;
        public String salesRepName;
        public String status;
        public String currentApprovalStep;
        public BigDecimal blendedRiskScore;
        public BigDecimal totalAmount;
        public BigDecimal averageDiscountPercent;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public LocalDateTime confirmedAt;
        public List<LineResponse> lines;
        /** Score cleared the last time this deal reached APPROVED (0 for a never-approved deal). */
        public BigDecimal approvedRiskScore;
        /** PDF B4 - "Approval steps list: Sales Manager, and Finance (only shown when required)". */
        public boolean requiresManager;
        public boolean requiresFinance;
        public String approvalRequirementLabel;
        /** True when the current discount is riskier than what was last approved - confirming would re-route to approval. */
        public boolean needsReapproval;
        /** PDF B6 - "Accept Suggested Split": who accepted the warehouse plan, and when (null until accepted). */
        public LocalDateTime fulfillmentAcceptedAt;
        public String fulfillmentAcceptedBy;
    }

    public static class QuotationSummaryResponse {
        public Long id;
        public String customerName;
        public String customerTier;
        public String status;
        public BigDecimal totalAmount;
        public BigDecimal blendedRiskScore;
        public String salesRepName;
        public LocalDateTime updatedAt;
    }

    public static class ApprovalLogResponse {
        public Long id;
        public String approverRole;
        public String action;
        public String actorUsername;
        public String reason;
        public LocalDateTime timestamp;
    }

    public static class UpsellSuggestionResponse {
        public Long productId;
        public String productName;
        public String category;
        public String productImageUrl;
        public BigDecimal price;
        public BigDecimal marginPercent;
        public boolean promoted;
    }

    public static class FulfillmentLineSplit {
        public Long id;
        public Long productId;
        public String productName;
        public Long warehouseId;
        public String warehouseName;
        public int quantityFulfilled;
        public BigDecimal shipmentCost;
        public LocalDate expectedDeliveryDate;
        public boolean manualOverride;
        public boolean delivered;
    }

    public static class BackorderResponse {
        public Long productId;
        public String productName;
        public int quantityPending;
        public boolean resolved;
    }

    public static class FulfillmentPlanResponse {
        public List<FulfillmentLineSplit> splits;
        public List<BackorderResponse> backorders;
        public int totalShipments;
        public BigDecimal totalShippingCost;
    }

    public static class FulfillmentOverrideRequest {
        public Long productId;
        public Long warehouseId;
        public int quantity;
    }

    public static class BillingEntryResponse {
        public Long id;
        public Long quotationLineId;
        public String productName;
        public String lineType; // ONE_TIME or RECURRING - so the screen can show the two groups separately
        public LocalDate billingDate;
        public BigDecimal amount;
        public String status;
        public String entryType;
        public String note;
        public LocalDateTime paidAt;
        public String paymentReference;
    }

    /** PDF B7 / quick-test step 8 - invoice-level view: what has been billed, paid and is still outstanding. */
    public static class BillingSummaryResponse {
        public BigDecimal oneTimeInvoiceTotal;
        public BigDecimal recurringBilledToDate;
        public BigDecimal creditsAndRefunds;
        public BigDecimal paidTotal;
        public BigDecimal outstandingTotal;
        public String invoiceStatus; // NOT_INVOICED / UNPAID / PARTIALLY_PAID / PAID
        public List<BillingEntryResponse> entries;
    }

    public static class RecordPaymentRequest {
        public Long entryId;      // a single entry to pay - or null with payAll=true
        public boolean payAll;    // settle every open (BILLED) entry on this quotation at once
        public String reference = "";
    }

    public static class SubscriptionModifyRequest {
        public Long quotationLineId;
        public int newQuantity;
    }

    public static class SubscriptionCancelRequest {
        public Long quotationLineId;
        public String reason = "";
    }

    public static class NegotiationMessageRequest {
        public String content;
        public String messageType; // COMMENT or COUNTER_DISCOUNT
        public BigDecimal proposedDiscountPercent;
        public Long quotationLineId;
    }

    public static class NegotiationMessageResponse {
        public Long id;
        public String senderType;
        public String senderName;
        public String messageType;
        public String content;
        public BigDecimal proposedDiscountPercent;
        public Long quotationLineId;
        public LocalDateTime timestamp;
    }
}
