package com.dealflow360.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shapes for the "Import from PDF" automation feature: instead of typing
 * every quotation line in by hand, a sales rep uploads a customer's PDF
 * (an RFQ, a prior quote, a purchase order) and the backend extracts
 * candidate line items from it automatically. The rep reviews/edits the
 * candidates and only the ones they keep get added to the quotation - the
 * PDF text extraction is real (Apache PDFBox), the matching is a
 * transparent, explainable heuristic (see PdfImportService), not a black
 * box.
 */
public class ImportDtos {

    /** One line the parser found in the PDF, matched (or not) against the product catalog. */
    public static class CandidateLine {
        public String sourceText;          // the exact line of PDF text this came from, for transparency
        public boolean matched;            // false if no catalog product could be confidently matched
        public Long productId;
        public String productName;
        public int quantity = 1;
        public BigDecimal discountPercent = BigDecimal.ZERO;
        public String note;                // e.g. "quantity not found, defaulted to 1"
    }

    public static class ImportPreviewResponse {
        public int totalLinesScanned;
        public int matchedLines;
        public List<CandidateLine> candidates;
    }

    public static class CommitLine {
        public Long productId;
        public int quantity = 1;
        public BigDecimal discountPercent = BigDecimal.ZERO;
    }

    public static class ImportCommitRequest {
        public List<CommitLine> lines;
    }
}
