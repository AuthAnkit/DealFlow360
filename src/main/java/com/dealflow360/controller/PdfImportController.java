package com.dealflow360.controller;

import com.dealflow360.dto.ImportDtos.CommitLine;
import com.dealflow360.dto.ImportDtos.ImportCommitRequest;
import com.dealflow360.dto.ImportDtos.ImportPreviewResponse;
import com.dealflow360.dto.QuotationDtos.AddLineRequest;
import com.dealflow360.dto.QuotationDtos.QuotationResponse;
import com.dealflow360.model.QuotationLine;
import com.dealflow360.service.AuditService;
import com.dealflow360.service.PdfImportService;
import com.dealflow360.service.QuotationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * "Import from PDF" - the automation differentiator that replaces manual
 * line-by-line data entry with a PDF upload. Deliberately split into two
 * steps so nothing is ever committed to a quotation without a human
 * reviewing it first:
 * <ul>
 *   <li>{@code POST /preview} - upload a PDF, get back candidate lines
 *       (matched product, quantity, discount) with nothing saved yet.</li>
 *   <li>{@code POST /commit} - the rep sends back only the lines they
 *       kept (possibly edited), which are added exactly like any manually
 *       entered line via {@link QuotationService#addLine}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/quotations/{id}/import")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER')")
public class PdfImportController {

    private final PdfImportService pdfImportService;
    private final QuotationService quotationService;
    private final AuditService auditService;

    public PdfImportController(PdfImportService pdfImportService, QuotationService quotationService, AuditService auditService) {
        this.pdfImportService = pdfImportService;
        this.quotationService = quotationService;
        this.auditService = auditService;
    }

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    public ImportPreviewResponse preview(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        quotationService.getEntity(id); // 404s if the quotation doesn't exist
        String text = pdfImportService.extractText(file);
        return pdfImportService.parseCandidates(text);
    }

    @PostMapping("/commit")
    public QuotationResponse commit(@PathVariable Long id, @RequestBody ImportCommitRequest request, Authentication auth) {
        if (request.lines == null || request.lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No lines selected to import");
        }
        for (CommitLine line : request.lines) {
            AddLineRequest addRequest = new AddLineRequest();
            addRequest.productId = line.productId;
            addRequest.quantity = Math.max(1, line.quantity);
            addRequest.discountPercent = line.discountPercent == null ? java.math.BigDecimal.ZERO : line.discountPercent;
            addRequest.lineType = QuotationLine.LineType.ONE_TIME; // PDF import only handles physical/one-time lines
            quotationService.addLine(id, addRequest);
        }
        auditService.log("Quotation", id, "AUTO_PDF_IMPORT", auth.getName(),
                "Imported " + request.lines.size() + " line(s) from an uploaded PDF instead of manual entry");
        return quotationService.toResponse(quotationService.getEntity(id));
    }
}
