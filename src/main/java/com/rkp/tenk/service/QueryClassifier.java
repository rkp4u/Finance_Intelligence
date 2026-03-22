package com.rkp.tenk.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Keyword-based query classifier that routes questions to either
 * the structured financial data lookup or the vector RAG pipeline.
 * No LLM call — must be instant (<1ms).
 */
@Service
@Slf4j
public class QueryClassifier {

    public enum QueryType { FINANCIAL_DATA, COMPARISON, NARRATIVE }

    public record ClassificationResult(
            QueryType type,
            List<String> detectedFields,
            List<String> detectedCompanies
    ) {}

    /**
     * Financial metric keywords → FinancialData entity field names.
     * Ordered longest-first so "total assets" matches before "assets".
     */
    private static final LinkedHashMap<String, String> KEYWORD_TO_FIELD = new LinkedHashMap<>();
    static {
        // Balance sheet (longest phrases first)
        KEYWORD_TO_FIELD.put("cash and cash equivalents", "cashAndEquivalents");
        KEYWORD_TO_FIELD.put("cash and equivalents", "cashAndEquivalents");
        KEYWORD_TO_FIELD.put("total assets", "totalAssets");
        KEYWORD_TO_FIELD.put("current assets", "currentAssets");
        KEYWORD_TO_FIELD.put("total liabilities", "totalLiabilities");
        KEYWORD_TO_FIELD.put("current liabilities", "currentLiabilities");
        KEYWORD_TO_FIELD.put("total equity", "totalEquity");
        KEYWORD_TO_FIELD.put("shareholders equity", "totalEquity");
        KEYWORD_TO_FIELD.put("shareholder equity", "totalEquity");
        KEYWORD_TO_FIELD.put("stockholders equity", "totalEquity");
        KEYWORD_TO_FIELD.put("trade receivables", "tradeReceivables");
        KEYWORD_TO_FIELD.put("accounts receivable", "tradeReceivables");
        KEYWORD_TO_FIELD.put("trade payables", "tradePayables");
        KEYWORD_TO_FIELD.put("accounts payable", "tradePayables");
        KEYWORD_TO_FIELD.put("retained earnings", "accumulatedProfit");
        KEYWORD_TO_FIELD.put("accumulated profit", "accumulatedProfit");

        // Income statement
        KEYWORD_TO_FIELD.put("cost of goods sold", "costOfSales");
        KEYWORD_TO_FIELD.put("cost of sales", "costOfSales");
        KEYWORD_TO_FIELD.put("cost of revenue", "costOfSales");
        KEYWORD_TO_FIELD.put("gross profit", "grossProfit");
        KEYWORD_TO_FIELD.put("gross margin", "grossProfit");
        KEYWORD_TO_FIELD.put("net income", "netIncome");
        KEYWORD_TO_FIELD.put("net profit", "netIncome");
        KEYWORD_TO_FIELD.put("net sales", "revenue");

        // Shorter keywords (checked after longer ones)
        KEYWORD_TO_FIELD.put("revenue", "revenue");
        KEYWORD_TO_FIELD.put("sales", "revenue");
        KEYWORD_TO_FIELD.put("turnover", "revenue");
        KEYWORD_TO_FIELD.put("assets", "totalAssets");
        KEYWORD_TO_FIELD.put("liabilities", "totalLiabilities");
        KEYWORD_TO_FIELD.put("equity", "totalEquity");
        KEYWORD_TO_FIELD.put("cash", "cashAndEquivalents");
        KEYWORD_TO_FIELD.put("receivables", "tradeReceivables");
        KEYWORD_TO_FIELD.put("payables", "tradePayables");
        KEYWORD_TO_FIELD.put("cogs", "costOfSales");
        KEYWORD_TO_FIELD.put("profit", "netIncome");
    }

    /**
     * Computed ratio keywords — these don't map to a single field
     * but are answered from multiple fields.
     */
    private static final Set<String> RATIO_KEYWORDS = Set.of(
            "current ratio", "debt to equity", "debt-to-equity",
            "gross margin", "net margin", "profit margin",
            "key ratios", "financial ratios", "key metrics",
            "financial summary", "financial overview", "financial highlights"
    );

    /**
     * Narrative indicators — if these appear AND no financial keywords found,
     * route to RAG.
     */
    private static final Set<String> NARRATIVE_KEYWORDS = Set.of(
            "risk", "risks", "strategy", "outlook", "guidance",
            "why", "explain", "describe", "how does", "what factors",
            "management discussion", "mda", "business overview",
            "competition", "competitive", "regulatory", "legal",
            "segment", "segments", "operations", "employees"
    );

    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "(?i)(compare|versus|vs\\.?|difference between|higher|lower|bigger|smaller|more than|less than)"
    );

    /**
     * Classify a user question into FINANCIAL_DATA, COMPARISON, or NARRATIVE.
     */
    public ClassificationResult classify(String question) {
        String normalized = question.toLowerCase().trim();

        // Detect financial metric fields
        List<String> detectedFields = new ArrayList<>();
        Set<String> fieldNames = new LinkedHashSet<>();

        for (Map.Entry<String, String> entry : KEYWORD_TO_FIELD.entrySet()) {
            if (normalized.contains(entry.getKey()) && !fieldNames.contains(entry.getValue())) {
                fieldNames.add(entry.getValue());
                detectedFields.add(entry.getValue());
            }
        }

        // Check for ratio keywords
        boolean hasRatioKeyword = RATIO_KEYWORDS.stream().anyMatch(normalized::contains);

        // Check for "key metrics" / "financial summary" type queries
        boolean isSummaryQuery = normalized.contains("key metrics") ||
                normalized.contains("financial metrics") ||
                normalized.contains("financial summary") ||
                normalized.contains("financial overview") ||
                normalized.contains("financial highlights") ||
                normalized.contains("financial data") ||
                normalized.contains("financial position") ||
                normalized.contains("balance sheet") ||
                normalized.contains("income statement");

        // Check for comparison patterns
        boolean isComparison = COMPARISON_PATTERN.matcher(normalized).find();

        // Check for narrative indicators
        boolean hasNarrativeKeyword = NARRATIVE_KEYWORDS.stream().anyMatch(normalized::contains);

        // Decision logic
        boolean hasFinancialSignal = !detectedFields.isEmpty() || hasRatioKeyword || isSummaryQuery;

        if (!hasFinancialSignal) {
            // No financial keywords at all → narrative
            log.debug("Query classified as NARRATIVE (no financial keywords): {}", truncate(question));
            return new ClassificationResult(QueryType.NARRATIVE, List.of(), List.of());
        }

        if (hasNarrativeKeyword && !hasFinancialSignal) {
            // Pure narrative question
            log.debug("Query classified as NARRATIVE (narrative keywords): {}", truncate(question));
            return new ClassificationResult(QueryType.NARRATIVE, List.of(), List.of());
        }

        // If summary query with no specific fields, include all fields
        if (detectedFields.isEmpty() && (hasRatioKeyword || isSummaryQuery)) {
            detectedFields = List.of("ALL");
        }

        QueryType type = isComparison ? QueryType.COMPARISON : QueryType.FINANCIAL_DATA;
        log.debug("Query classified as {} with fields {}: {}", type, detectedFields, truncate(question));
        return new ClassificationResult(type, detectedFields, List.of());
    }

    private String truncate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
