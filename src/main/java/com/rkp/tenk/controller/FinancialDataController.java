package com.rkp.tenk.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkp.tenk.exception.ResourceNotFoundException;
import com.rkp.tenk.model.dto.FinancialDataComparisonResponse;
import com.rkp.tenk.model.dto.FinancialDataResponse;
import com.rkp.tenk.model.dto.ValidationCheckResult;
import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.enums.ExtractionStatus;
import com.rkp.tenk.model.entity.DocumentRecord;
import com.rkp.tenk.model.enums.DocumentStatus;
import com.rkp.tenk.repository.DocumentRecordRepository;
import com.rkp.tenk.repository.FinancialDataRepository;
import com.rkp.tenk.service.FinancialExtractionService;
import com.rkp.tenk.service.FinancialValidationService;
import com.rkp.tenk.service.FinancialValidationService.ValidationResult;
import com.rkp.tenk.service.KnowledgeBaseService;
import com.rkp.tenk.service.PdfProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Financial Data", description = "Structured financial data extraction and validation endpoints")
public class FinancialDataController {

    private final FinancialDataRepository financialDataRepository;
    private final FinancialValidationService validationService;
    private final FinancialExtractionService financialExtractionService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRecordRepository documentRecordRepository;
    private final PdfProcessingService pdfProcessingService;
    private final ObjectMapper objectMapper;

    @GetMapping("/documents/{docId}/financial-data")
    @Operation(summary = "Get extracted financial data for a document")
    public ResponseEntity<FinancialDataResponse> getFinancialData(
            @PathVariable UUID kbId,
            @PathVariable UUID docId) {

        knowledgeBaseService.findOrThrow(kbId);
        FinancialData data = financialDataRepository.findByDocumentRecordId(docId)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialData", docId));

        return ResponseEntity.ok(toResponse(data));
    }

    @GetMapping("/financial-data/compare")
    @Operation(summary = "Compare financial data across multiple documents")
    public ResponseEntity<FinancialDataComparisonResponse> compare(
            @PathVariable UUID kbId,
            @RequestParam List<UUID> documentIds) {

        knowledgeBaseService.findOrThrow(kbId);

        List<FinancialDataResponse> results = documentIds.stream()
                .map(docId -> financialDataRepository.findByDocumentRecordId(docId).orElse(null))
                .filter(fd -> fd != null && fd.getExtractionStatus() == ExtractionStatus.COMPLETED)
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(new FinancialDataComparisonResponse(results, results.size()));
    }

    @PostMapping("/documents/{docId}/financial-data/validate")
    @Operation(summary = "Re-run validation on extracted financial data")
    public ResponseEntity<List<ValidationCheckResult>> validate(
            @PathVariable UUID kbId,
            @PathVariable UUID docId) {

        knowledgeBaseService.findOrThrow(kbId);
        FinancialData data = financialDataRepository.findByDocumentRecordId(docId)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialData", docId));

        ValidationResult result = validationService.validate(data);
        try {
            data.setValidationStatus(result.status());
            data.setValidationDetails(objectMapper.writeValueAsString(result.checks()));
            data.setValidatedAt(Instant.now());
            financialDataRepository.save(data);
        } catch (Exception e) {
            log.error("Failed to save validation results for document {}", docId, e);
        }

        return ResponseEntity.ok(result.checks());
    }

    @PostMapping("/documents/{docId}/financial-data/re-extract")
    @Operation(summary = "Force re-extraction of financial data from a document")
    public ResponseEntity<FinancialDataResponse> reExtract(
            @PathVariable UUID kbId,
            @PathVariable UUID docId) {

        knowledgeBaseService.findOrThrow(kbId);
        DocumentRecord docRecord = documentRecordRepository.findById(docId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", docId));

        if (docRecord.getStatus() != DocumentStatus.READY) {
            return ResponseEntity.badRequest().build();
        }

        if (docRecord.getPdfContent() == null) {
            log.warn("No stored PDF bytes for document {}. Re-upload required.", docId);
            return ResponseEntity.unprocessableEntity().build();
        }

        // Delete existing financial data if present
        financialDataRepository.findByDocumentRecordId(docId)
                .ifPresent(financialDataRepository::delete);

        // Re-read the PDF from stored bytes and run extraction
        String originalFilename = docRecord.getOriginalFilename();
        Resource pdfResource = new ByteArrayResource(docRecord.getPdfContent()) {
            @Override
            public String getFilename() {
                return originalFilename;
            }
        };

        List<Document> pages = pdfProcessingService.extractText(pdfResource);
        financialExtractionService.extractAndStore(pages, docId, kbId);

        FinancialData result = financialDataRepository.findByDocumentRecordId(docId)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialData", docId));

        log.info("Re-extraction completed for document {}: status={}", docId, result.getExtractionStatus());
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping("/financial-data")
    @Operation(summary = "List all financial data in a knowledge base")
    public ResponseEntity<List<FinancialDataResponse>> listAll(@PathVariable UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        List<FinancialDataResponse> results = financialDataRepository.findByKnowledgeBaseId(kbId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(results);
    }

    private FinancialDataResponse toResponse(FinancialData data) {
        List<ValidationCheckResult> checks = null;
        if (data.getValidationDetails() != null) {
            try {
                checks = objectMapper.readValue(data.getValidationDetails(),
                        new TypeReference<List<ValidationCheckResult>>() {});
            } catch (Exception e) {
                log.debug("Could not parse validation details JSON", e);
            }
        }

        return new FinancialDataResponse(
                data.getId(),
                data.getDocumentRecord().getId(),
                data.getCompanyName(),
                data.getFiscalYear(),
                data.getFiscalYearEndDate(),
                data.getCurrencyCode(),
                data.getAccountingStandard() != null ? data.getAccountingStandard().name() : null,
                data.getAmountsInUnit(),
                data.getTotalAssets(),
                data.getCurrentAssets(),
                data.getTotalLiabilities(),
                data.getCurrentLiabilities(),
                data.getTotalEquity(),
                data.getCashAndEquivalents(),
                data.getTradeReceivables(),
                data.getTradePayables(),
                data.getAccumulatedProfit(),
                data.getRevenue(),
                data.getCostOfSales(),
                data.getGrossProfit(),
                data.getNetIncome(),
                data.getExtractionStatus().name(),
                data.getExtractionModel(),
                data.getExtractionConfidence(),
                data.getExtractionError(),
                data.getValidationStatus() != null ? data.getValidationStatus().name() : null,
                checks,
                data.getExtractedAt(),
                data.getValidatedAt()
        );
    }
}
