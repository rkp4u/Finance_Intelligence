package com.rkp.tenk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkp.tenk.model.dto.FinancialStatementPages;
import com.rkp.tenk.model.dto.LlmExtractionSchema;
import com.rkp.tenk.model.dto.ValidationCheckResult;
import com.rkp.tenk.model.entity.DocumentRecord;
import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.entity.KnowledgeBase;
import com.rkp.tenk.model.enums.AccountingStandard;
import com.rkp.tenk.model.enums.ExtractionStatus;
import com.rkp.tenk.repository.DocumentRecordRepository;
import com.rkp.tenk.repository.FinancialDataRepository;
import com.rkp.tenk.service.FinancialValidationService.ValidationResult;
import com.rkp.tenk.util.LlmOutputUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Extracts structured financial data from PDF pages using LLM.
 * Runs during document ingestion alongside (not replacing) the vector RAG pipeline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialExtractionService {

    /**
     * System prompt used when input is raw PDFBox text (columns may be misaligned).
     */
    private static final String SYSTEM_PROMPT_RAW = """
            You are a financial data extraction specialist. Extract structured financial
            data from the provided financial statement text. The text comes from an
            annual report filing and may have formatting issues from PDF extraction
            (columns may be misaligned, headers may be separated from values).

            Rules:
            - Extract ONLY values explicitly stated in the text. Do not calculate or infer.
            - All monetary values must be in the same unit as stated in the document
              (e.g., if amounts are "in millions", report the number as stated, like 25111 for $25,111 million).
            - Report the unit in the "amountsInUnit" field. Use one of:
              MILLIONS, BILLIONS, THOUSANDS, CRORES, LAKHS, or UNITS.
              Indian filings typically report in Crores (₹ Crore / ₹ Cr) — use CRORES.
              If the document says "in millions" use MILLIONS, "in thousands" use THOUSANDS, etc.
            - Use null for any field not found in the text.
            - For fiscal year, extract the most recent year shown (not comparatives).
            - IMPORTANT: When a line item has sub-items, prefer the NARROWEST specific sub-item:
              * For receivables: prefer "Trade receivables" or "Accounts receivable" (the narrow sub-line)
                over "Receivables, net" or "Trade and other receivables" (the broader total).
                If "Trade receivables" is listed separately from "Other receivables", use only "Trade receivables".
              * For payables: prefer "Trade payables" or "Accounts payable" (the narrow sub-line)
                over "Accounts payable and accrued expenses" or "Trade and other payables" (the broader total).
                If "Accounts payable" is listed separately from "Accrued expenses", use only "Accounts payable".
            - Map terminology to the standard field names regardless of accounting standard:
              * "Revenue" / "Net revenue" / "Net sales" / "Revenue from operations"
                / "Revenue from contracts with customers" / "Operating revenue"
                / "Service revenue" / "Total revenue" / "Turnover"
                / "Revenue from services" -> revenue
              * "Cost of goods sold" / "Cost of revenue" / "Cost of sales"
                / "Cost of materials consumed" / "Operating expenses" (ONLY if no
                separate cost-of-sales line exists) -> costOfSales
              * "Retained earnings" / "Accumulated profit" / "Surplus"
                / "Accumulated deficit" -> accumulatedProfit (use negative for deficit)
              * "Trade receivables" / "Accounts receivable" (NOT total receivables) -> tradeReceivables
              * "Trade payables" / "Accounts payable" (NOT total payables or accrued expenses) -> tradePayables
              * "Cash and cash equivalents" / "Cash and bank balances" -> cashAndEquivalents
            - Return ONLY valid JSON in the exact schema below. No other text.
            - The input may contain Markdown table formatting (pipe-delimited rows) — read
              these as structured tables where each column corresponds to a year or label.

            {
              "companyName": "string",
              "fiscalYear": "string",
              "fiscalYearEndDate": "YYYY-MM-DD or null",
              "currencyCode": "string (ISO 4217: USD, INR, SGD, etc.)",
              "accountingStandard": "US_GAAP | IFRS | IND_AS | UNKNOWN",
              "amountsInUnit": "BILLIONS | MILLIONS | THOUSANDS | CRORES | LAKHS | UNITS",
              "totalAssets": number or null,
              "currentAssets": number or null,
              "totalLiabilities": number or null,
              "currentLiabilities": number or null,
              "totalEquity": number or null,
              "cashAndEquivalents": number or null,
              "tradeReceivables": number or null,
              "tradePayables": number or null,
              "accumulatedProfit": number or null,
              "revenue": number or null,
              "costOfSales": number or null,
              "grossProfit": number or null,
              "netIncome": number or null,
              "confidenceScore": number (0.0-1.0)
            }
            """;

    /**
     * Single user prompt template used for both PDFBox and Docling paths.
     * Each section is labeled so the LLM knows which table is the balance sheet vs income statement.
     * When Docling is enabled, the section text contains pipe-delimited Markdown tables;
     * when disabled, it contains raw PDFBox text. The instructions cover both cases.
     */
    private static final String USER_PROMPT_TEMPLATE = """
            Extract financial data from the following financial statements.
            Each section is labeled. Sections may contain raw text or structured Markdown tables
            (pipe-delimited) — read both accurately.

            %s

            %s

            %s

            IMPORTANT — NOTES OVERRIDE BALANCE SHEET for tradeReceivables and tradePayables:
            1. First check the NOTES section for a breakdown of receivables/payables.
            2. If the notes show sub-lines like "Trade receivables: 5,419" and "Other: 1,196"
               totaling "Receivables: 6,615", use the NARROW sub-line value (5,419), NOT the total (6,615).
            3. The balance sheet often shows only the aggregated total. The notes show the breakdown.
               Always prefer the notes breakdown value over the balance sheet total.
            4. Same rule applies to payables: use "Trade payables" or "Accounts payable" from notes,
               NOT "Accounts payable and accrued expenses" from the balance sheet.
            5. For Markdown tables: the leftmost data column is the most recent fiscal year.
               If a row is a subtotal/net (value equals sum of rows above), use the sub-lines instead.

            Return the JSON extraction only.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final LlmOutputUtil llmOutputUtil;
    private final FinancialStatementDetector detector;
    private final FinancialValidationService validationService;
    private final DoclingTableService doclingTableService;
    private final CompanyResolutionService companyResolutionService;
    private final FinancialDataRepository financialDataRepository;
    private final DocumentRecordRepository documentRecordRepository;

    @Value("${app.model-name:unknown}")
    private String modelName;

    @Value("${financial-extraction.enabled:true}")
    private boolean extractionEnabled;

    /**
     * Main entry point: detect financial pages, extract data via LLM, validate, and persist.
     *
     * @param pages          one {@link Document} per PDF page (from PagePdfDocumentReader)
     * @param documentId     the persisted DocumentRecord UUID
     * @param knowledgeBaseId the KB this document belongs to
     * @param pdfBytes       raw PDF bytes for optional Docling enrichment (may be null)
     */
    public void extractAndStore(List<Document> pages, UUID documentId, UUID knowledgeBaseId,
                                byte[] pdfBytes) {
        if (!extractionEnabled) {
            log.info("Financial extraction disabled, skipping for document {}", documentId);
            return;
        }

        log.info("Starting financial extraction for document {}", documentId);

        DocumentRecord docRecord = documentRecordRepository.findById(documentId).orElse(null);
        if (docRecord == null) {
            log.warn("Document record not found: {}", documentId);
            return;
        }

        // Create or get existing FinancialData record
        FinancialData financialData = financialDataRepository.findByDocumentRecordId(documentId)
                .orElseGet(() -> {
                    FinancialData fd = new FinancialData();
                    fd.setDocumentRecord(docRecord);
                    KnowledgeBase kb = new KnowledgeBase();
                    kb.setId(knowledgeBaseId);
                    fd.setKnowledgeBase(kb);
                    return fd;
                });

        financialData.setExtractionStatus(ExtractionStatus.EXTRACTING);
        financialData = financialDataRepository.save(financialData);

        try {
            // Step 1: Detect financial statement pages
            FinancialStatementPages fsPages = detector.detect(pages);

            if (!fsPages.hasFinancialStatements()) {
                log.info("No financial statement pages detected for document {}", documentId);
                financialData.setExtractionStatus(ExtractionStatus.SKIPPED);
                financialData.setExtractionError("No financial statement pages detected");
                financialDataRepository.save(financialData);
                return;
            }

            // Step 1b: Optionally enrich each section with Docling structured tables.
            // Falls back to PDFBox text per-section if Docling is disabled or fails.
            fsPages = doclingTableService.enrich(fsPages, pdfBytes);

            // Step 2: Build the 3-section labeled prompt using the best available text per section.
            // effective*Text() returns Docling markdown when available, raw PDFBox otherwise.
            if (fsPages.hasAnyEnrichedText()) {
                log.info("Using Docling-enriched text for document {} (BS={}, IS={}, Notes={})",
                        documentId,
                        fsPages.enrichedBalanceSheetText() != null ? "enriched" : "PDFBox",
                        fsPages.enrichedIncomeStatementText() != null ? "enriched" : "PDFBox",
                        fsPages.enrichedNotesText() != null ? "enriched" : "PDFBox");
            }

            String balanceSheetSection = fsPages.effectiveBalanceSheetText() != null
                    ? "BALANCE SHEET / STATEMENT OF FINANCIAL POSITION:\n" + fsPages.effectiveBalanceSheetText()
                    : "BALANCE SHEET: Not found in document.";
            String incomeStatementSection = fsPages.effectiveIncomeStatementText() != null
                    ? "INCOME STATEMENT / STATEMENT OF OPERATIONS:\n" + fsPages.effectiveIncomeStatementText()
                    : "INCOME STATEMENT: Not found in document.";
            String notesSection = fsPages.effectiveNotesText() != null
                    ? "NOTES TO FINANCIAL STATEMENTS (receivables/payables breakdowns):\n" + fsPages.effectiveNotesText()
                    : "";

            String userPrompt = USER_PROMPT_TEMPLATE.formatted(
                    balanceSheetSection, incomeStatementSection, notesSection);

            // Step 3: Call LLM
            long start = System.currentTimeMillis();
            String rawResponse = chatClient.prompt()
                    .system(SYSTEM_PROMPT_RAW)
                    .user(userPrompt)
                    .call()
                    .content();
            long llmTimeMs = System.currentTimeMillis() - start;

            log.info("LLM extraction completed in {}ms for document {}", llmTimeMs, documentId);

            // Store raw response for debugging
            financialData.setRawLlmResponse(rawResponse);
            financialData.setExtractionModel(modelName);

            // Step 4: Parse LLM response
            String stripped = llmOutputUtil.stripThinkingBlock(rawResponse);
            LlmExtractionSchema extracted = parseExtraction(stripped);

            if (extracted == null) {
                financialData.setExtractionStatus(ExtractionStatus.FAILED);
                financialData.setExtractionError("Failed to parse LLM response as JSON");
                financialDataRepository.save(financialData);
                return;
            }

            // Step 5: Map to entity
            mapToEntity(financialData, extracted, fsPages.detectedStandard());

            // Step 5b: Resolve/create Company record
            try {
                var company = companyResolutionService.resolveOrCreate(extracted.companyName());
                financialData.setCompany(company);
            } catch (Exception e) {
                log.warn("Company resolution failed for '{}', continuing without FK: {}",
                        extracted.companyName(), e.getMessage());
            }

            // Step 6: Validate
            ValidationResult validationResult = validationService.validate(financialData);
            financialData.setValidationStatus(validationResult.status());
            financialData.setValidationDetails(
                    objectMapper.writeValueAsString(validationResult.checks()));
            financialData.setValidatedAt(Instant.now());

            // Step 7: Mark complete
            financialData.setExtractionStatus(ExtractionStatus.COMPLETED);
            financialData.setExtractedAt(Instant.now());
            financialDataRepository.save(financialData);

            log.info("Financial extraction complete for document {}: company={}, standard={}, validation={}",
                    documentId, extracted.companyName(), extracted.accountingStandard(),
                    validationResult.status());

        } catch (Exception e) {
            log.error("Financial extraction failed for document {}", documentId, e);
            financialData.setExtractionStatus(ExtractionStatus.FAILED);
            financialData.setExtractionError(truncate(e.getMessage(), 1000));
            financialData.setExtractedAt(Instant.now());
            try {
                financialDataRepository.save(financialData);
            } catch (Exception saveEx) {
                log.error("Failed to save extraction error for document {}", documentId, saveEx);
            }
        }
    }

    /**
     * Parse LLM response with 3-tier fallback.
     */
    private LlmExtractionSchema parseExtraction(String content) {
        // Tier 1: Direct parse
        try {
            return objectMapper.readValue(content, LlmExtractionSchema.class);
        } catch (Exception ignored) {
            // fall through
        }

        // Tier 2: Extract JSON object from response
        String jsonObject = llmOutputUtil.extractJsonObject(content);
        if (jsonObject != null) {
            try {
                return objectMapper.readValue(jsonObject, LlmExtractionSchema.class);
            } catch (Exception ignored) {
                // fall through
            }
        }

        // Tier 3: Failed
        log.warn("Could not parse financial extraction response. Raw: {}",
                truncate(content, 300));
        return null;
    }

    /**
     * Map parsed LLM output to the FinancialData entity.
     */
    private void mapToEntity(FinancialData entity, LlmExtractionSchema schema,
                             AccountingStandard detectedStandard) {
        entity.setCompanyName(schema.companyName());
        entity.setFiscalYear(schema.fiscalYear());
        entity.setCurrencyCode(schema.currencyCode());
        entity.setAmountsInUnit(schema.amountsInUnit());
        entity.setExtractionConfidence(schema.confidenceScore());

        // Parse fiscal year end date
        if (schema.fiscalYearEndDate() != null && !schema.fiscalYearEndDate().isBlank()
                && !"null".equalsIgnoreCase(schema.fiscalYearEndDate())) {
            try {
                entity.setFiscalYearEndDate(LocalDate.parse(schema.fiscalYearEndDate()));
            } catch (DateTimeParseException e) {
                log.debug("Could not parse fiscal year end date: {}", schema.fiscalYearEndDate());
            }
        }

        // Accounting standard: prefer LLM's answer, fall back to detector
        AccountingStandard standard = parseAccountingStandard(schema.accountingStandard());
        if (standard == AccountingStandard.UNKNOWN && detectedStandard != AccountingStandard.UNKNOWN) {
            standard = detectedStandard;
        }
        entity.setAccountingStandard(standard);

        // Balance sheet fields
        entity.setTotalAssets(schema.totalAssets());
        entity.setCurrentAssets(schema.currentAssets());
        entity.setTotalLiabilities(schema.totalLiabilities());
        entity.setCurrentLiabilities(schema.currentLiabilities());
        entity.setTotalEquity(schema.totalEquity());
        entity.setCashAndEquivalents(schema.cashAndEquivalents());
        entity.setTradeReceivables(schema.tradeReceivables());
        entity.setTradePayables(schema.tradePayables());
        entity.setAccumulatedProfit(schema.accumulatedProfit());

        // Income statement fields
        entity.setRevenue(schema.revenue());
        entity.setCostOfSales(schema.costOfSales());
        entity.setGrossProfit(schema.grossProfit());
        entity.setNetIncome(schema.netIncome());
    }

    private AccountingStandard parseAccountingStandard(String value) {
        if (value == null) return AccountingStandard.UNKNOWN;
        try {
            return AccountingStandard.valueOf(value.toUpperCase().replace("-", "_").replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return AccountingStandard.UNKNOWN;
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
