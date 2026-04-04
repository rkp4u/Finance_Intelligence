package com.rkp.tenk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkp.tenk.config.DoclingProperties;
import com.rkp.tenk.model.dto.FinancialStatementPages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enriches each financial statement section (BS, IS, Notes) independently with
 * structured Markdown tables from Docling Serve.
 *
 * <p>Docling uses IBM's TableFormer ML model which achieves 94-98% accuracy on
 * financial tables. It returns pipe-delimited Markdown preserving column alignment
 * that PDFBox flattens into unstructured text.</p>
 *
 * <p>Each section is enriched via a separate Docling call using only the pages
 * belonging to that section. This preserves the 3-section prompt structure that
 * the LLM relies on to know which table is the balance sheet vs income statement vs notes.</p>
 *
 * <p>When a section fails or Docling is disabled, that section falls back to PDFBox
 * text independently — other sections are unaffected.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DoclingTableService {

    private static final String CONVERT_ENDPOINT = "/v1/convert/source";

    /**
     * Maximum characters of Docling markdown per section before truncation.
     * At ~4 chars/token this is ~4.5K tokens per section, ~13.5K total for all three —
     * well within model context budgets while covering the financial tables completely.
     */
    private static final int MAX_MARKDOWN_CHARS_PER_SECTION = 18_000;

    private final DoclingProperties doclingProperties;
    private final ObjectMapper objectMapper;

    /**
     * Enrich each financial statement section independently using Docling Serve.
     * Each section uses only its own detected pages, preserving the BS/IS/Notes separation.
     *
     * @param pages    the detected pages from {@link FinancialStatementDetector}
     * @param pdfBytes raw PDF bytes (stored in DocumentRecord.pdfContent)
     * @return enriched pages with per-section Docling text, or original pages if Docling is disabled
     */
    public FinancialStatementPages enrich(FinancialStatementPages pages, byte[] pdfBytes) {
        if (!doclingProperties.enabled()) {
            log.debug("Docling disabled, skipping table enrichment");
            return pages;
        }
        if (pdfBytes == null || pdfBytes.length == 0) {
            log.warn("No PDF bytes available for Docling enrichment");
            return pages;
        }
        if (!pages.hasFinancialStatements()) {
            return pages;
        }

        // Enrich each section independently — failures are isolated per section
        String enrichedBS    = enrichSection("BalanceSheet",     pages.balanceSheetPageNumbers(),    pdfBytes);
        String enrichedIS    = enrichSection("IncomeStatement",  pages.incomeStatementPageNumbers(), pdfBytes);
        String enrichedNotes = enrichSection("Notes",            pages.notesPageNumbers(),           pdfBytes);

        if (enrichedBS == null && enrichedIS == null && enrichedNotes == null) {
            log.warn("Docling enrichment returned nothing for any section, falling back to PDFBox text entirely");
            return pages;
        }

        int enrichedCount = (enrichedBS != null ? 1 : 0) + (enrichedIS != null ? 1 : 0) + (enrichedNotes != null ? 1 : 0);
        log.info("Docling enriched {}/3 sections (BS={}, IS={}, Notes={})",
                enrichedCount,
                enrichedBS != null ? enrichedBS.length() + " chars" : "fallback",
                enrichedIS != null ? enrichedIS.length() + " chars" : "fallback",
                enrichedNotes != null ? enrichedNotes.length() + " chars" : "fallback");

        return pages.withSectionEnrichments(enrichedBS, enrichedIS, enrichedNotes);
    }

    /**
     * Check if Docling Serve is reachable. Used for health/diagnostics.
     */
    public boolean isAvailable() {
        if (!doclingProperties.enabled()) return false;
        try {
            HttpClient client = buildHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(doclingProperties.baseUrl() + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Docling health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Enrich a single section (BS, IS, or Notes) by calling Docling with only that section's pages.
     *
     * @param sectionName human-readable name for logging
     * @param pageNumbers 1-indexed page numbers detected for this section
     * @param pdfBytes    raw PDF bytes
     * @return Docling markdown for this section, or null if empty/failed (caller falls back to PDFBox)
     */
    private String enrichSection(String sectionName, List<Integer> pageNumbers, byte[] pdfBytes) {
        if (pageNumbers == null || pageNumbers.isEmpty()) {
            log.debug("Docling: no pages for section {}, skipping", sectionName);
            return null;
        }

        List<Integer> capped = pageNumbers.stream()
                .sorted()
                .distinct()
                .limit(doclingProperties.maxPagesPerRequest())
                .collect(Collectors.toList());

        log.debug("Docling: enriching section {} with pages {}", sectionName, capped);
        long start = System.currentTimeMillis();

        try {
            String markdown = callDoclingServe(pdfBytes, capped);
            if (markdown == null || markdown.isBlank()) {
                log.warn("Docling: empty response for section {}", sectionName);
                return null;
            }

            if (markdown.length() > MAX_MARKDOWN_CHARS_PER_SECTION) {
                log.warn("Docling: section {} markdown truncated from {} to {} chars",
                        sectionName, markdown.length(), MAX_MARKDOWN_CHARS_PER_SECTION);
                markdown = markdown.substring(0, MAX_MARKDOWN_CHARS_PER_SECTION);
            }

            log.info("Docling: section {} enriched in {}ms ({} chars)",
                    sectionName, System.currentTimeMillis() - start, markdown.length());
            return markdown;

        } catch (Exception e) {
            log.warn("Docling: section {} enrichment failed ({}), falling back to PDFBox text",
                    sectionName, e.getMessage());
            return null;
        }
    }

    private String callDoclingServe(byte[] pdfBytes, List<Integer> pageNumbers) throws Exception {
        String requestBody = buildRequestJson(pdfBytes, pageNumbers);

        HttpClient client = buildHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(doclingProperties.baseUrl() + CONVERT_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(doclingProperties.timeoutSeconds()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Docling Serve returned HTTP {}: {}", response.statusCode(),
                    truncate(response.body(), 200));
            return null;
        }

        return extractMarkdownFromResponse(response.body());
    }

    /**
     * Builds the JSON payload for Docling Serve /v1/convert/source.
     *
     * <pre>
     * {
     *   "base64_source": { "data": "<base64>", "filename": "doc.pdf" },
     *   "options": { "to_formats": ["md"], "page_range": "3,4,5" }
     * }
     * </pre>
     */
    private String buildRequestJson(byte[] pdfBytes, List<Integer> pageNumbers) throws Exception {
        String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);
        String pageRange = pageNumbers.stream().map(String::valueOf).collect(Collectors.joining(","));

        var base64Source = objectMapper.createObjectNode()
                .put("data", base64Pdf)
                .put("filename", "financial_statements.pdf");

        var options = objectMapper.createObjectNode()
                .put("page_range", pageRange);
        options.putArray("to_formats").add("md");

        var root = objectMapper.createObjectNode();
        root.set("base64_source", base64Source);
        root.set("options", options);

        return objectMapper.writeValueAsString(root);
    }

    private String extractMarkdownFromResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // Docling Serve response: { "document": { "md_content": "..." } }
        JsonNode document = root.path("document");
        if (!document.isMissingNode()) {
            JsonNode mdContent = document.path("md_content");
            if (!mdContent.isMissingNode() && !mdContent.isNull()) {
                return mdContent.asText();
            }
        }

        // Alternate shapes
        for (String field : new String[]{"output", "content", "markdown"}) {
            JsonNode node = root.path(field);
            if (!node.isMissingNode() && !node.isNull() && node.isTextual()) {
                return node.asText();
            }
        }

        log.warn("Could not find markdown content in Docling response. Available keys: {}",
                root.fieldNames());
        return null;
    }

    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
