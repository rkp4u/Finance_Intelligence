package com.rkp.tenk.service;

import com.rkp.tenk.model.dto.QueryResponse;
import com.rkp.tenk.model.dto.QueryResponse.QueryMetadata;
import com.rkp.tenk.model.dto.QueryResponse.SourceChunk;
import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.enums.ExtractionStatus;
import com.rkp.tenk.repository.FinancialDataRepository;
import com.rkp.tenk.service.QueryClassifier.ClassificationResult;
import com.rkp.tenk.service.QueryClassifier.QueryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Answers financial data queries directly from the database — no vector search, no LLM.
 * Returns the same QueryResponse format as the RAG pipeline for seamless integration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StructuredQueryService {

    private final FinancialDataRepository financialDataRepository;

    private static final Map<String, String> CURRENCY_SYMBOLS = Map.ofEntries(
            Map.entry("USD", "$"), Map.entry("INR", "₹"), Map.entry("SGD", "S$"),
            Map.entry("EUR", "€"), Map.entry("GBP", "£"), Map.entry("JPY", "¥"),
            Map.entry("CNY", "¥"), Map.entry("HKD", "HK$"), Map.entry("KRW", "₩")
    );

    /**
     * Try to answer a financial data query from the structured database.
     * Returns null if no data is available (caller should fall through to RAG).
     */
    public QueryResponse answer(String question, UUID kbId, ClassificationResult classification) {
        long start = System.currentTimeMillis();

        List<FinancialData> allData = financialDataRepository
                .findByKnowledgeBaseIdAndExtractionStatus(kbId, ExtractionStatus.COMPLETED);

        if (allData.isEmpty()) {
            log.debug("No completed financial data for KB {}, falling through to RAG", kbId);
            return null;
        }

        long queryTimeMs = System.currentTimeMillis() - start;

        String answer;
        if (classification.type() == QueryType.TREND) {
            answer = formatTrend(allData, question, classification.detectedFields());
        } else if (classification.type() == QueryType.COMPARISON) {
            answer = formatComparison(allData, classification.detectedFields());
        } else if (allData.size() == 1) {
            answer = formatSingleCompany(allData.get(0), classification.detectedFields());
        } else {
            // Multiple companies in KB but not a comparison/trend query — pick the most relevant
            FinancialData best = findBestMatch(allData, question);
            answer = formatSingleCompany(best, classification.detectedFields());
        }

        QueryMetadata metadata = new QueryMetadata(
                0, queryTimeMs, 0, "structured-lookup", "STRUCTURED", null);

        log.info("Structured query answered in {}ms for KB {}", queryTimeMs, kbId);
        return new QueryResponse(answer, List.of(), metadata);
    }

    /**
     * Format a single company's financial data as a readable response.
     */
    private String formatSingleCompany(FinancialData fd, List<String> requestedFields) {
        String sym = currencySymbol(fd.getCurrencyCode());
        String unit = fd.getAmountsInUnit() != null ? fd.getAmountsInUnit() : "";
        String header = String.format("## %s — FY%s (%s, %s)\n\n",
                fd.getCompanyName(), fd.getFiscalYear(), fd.getCurrencyCode(), unit);

        boolean showAll = requestedFields.contains("ALL") || requestedFields.size() > 3;

        StringBuilder sb = new StringBuilder(header);

        if (showAll || hasAnyField(requestedFields, "totalAssets", "currentAssets", "totalLiabilities",
                "currentLiabilities", "totalEquity", "cashAndEquivalents", "tradeReceivables",
                "tradePayables", "accumulatedProfit")) {
            sb.append("**Balance Sheet:**\n");
            appendField(sb, "Total Assets", fd.getTotalAssets(), sym, showAll || requestedFields.contains("totalAssets"));
            appendField(sb, "Current Assets", fd.getCurrentAssets(), sym, showAll || requestedFields.contains("currentAssets"));
            appendField(sb, "Total Liabilities", fd.getTotalLiabilities(), sym, showAll || requestedFields.contains("totalLiabilities"));
            appendField(sb, "Current Liabilities", fd.getCurrentLiabilities(), sym, showAll || requestedFields.contains("currentLiabilities"));
            appendField(sb, "Total Equity", fd.getTotalEquity(), sym, showAll || requestedFields.contains("totalEquity"));
            appendField(sb, "Cash & Equivalents", fd.getCashAndEquivalents(), sym, showAll || requestedFields.contains("cashAndEquivalents"));
            appendField(sb, "Trade Receivables", fd.getTradeReceivables(), sym, showAll || requestedFields.contains("tradeReceivables"));
            appendField(sb, "Trade Payables", fd.getTradePayables(), sym, showAll || requestedFields.contains("tradePayables"));
            appendField(sb, "Retained Earnings", fd.getAccumulatedProfit(), sym, showAll || requestedFields.contains("accumulatedProfit"));
            sb.append("\n");
        }

        if (showAll || hasAnyField(requestedFields, "revenue", "costOfSales", "grossProfit", "netIncome")) {
            sb.append("**Income Statement:**\n");
            appendField(sb, "Revenue", fd.getRevenue(), sym, showAll || requestedFields.contains("revenue"));
            appendField(sb, "Cost of Sales", fd.getCostOfSales(), sym, showAll || requestedFields.contains("costOfSales"));
            appendField(sb, "Gross Profit", fd.getGrossProfit(), sym, showAll || requestedFields.contains("grossProfit"));
            appendField(sb, "Net Income", fd.getNetIncome(), sym, showAll || requestedFields.contains("netIncome"));
            sb.append("\n");
        }

        // Key ratios (always show if summary or ratio requested)
        if (showAll || requestedFields.stream().anyMatch(f -> f.contains("ratio") || f.equals("ALL"))) {
            sb.append("**Key Ratios:**\n");
            appendRatio(sb, "Current Ratio", fd.getCurrentAssets(), fd.getCurrentLiabilities(), "x");
            appendRatio(sb, "Debt / Equity", fd.getTotalLiabilities(), fd.getTotalEquity(), "x");
            appendMargin(sb, "Gross Margin", fd.getGrossProfit(), fd.getRevenue());
            appendMargin(sb, "Net Margin", fd.getNetIncome(), fd.getRevenue());
            sb.append("\n");
        }

        // Source line
        sb.append(String.format("*Source: %s filing (%s). Confidence: %d%%. Validation: %s.*",
                fd.getAccountingStandard() != null ? fd.getAccountingStandard().name() : "Unknown",
                fd.getCurrencyCode(),
                fd.getExtractionConfidence() != null ? Math.round(fd.getExtractionConfidence() * 100) : 0,
                fd.getValidationStatus() != null ? fd.getValidationStatus().name() : "NOT_RUN"));

        return sb.toString();
    }

    /**
     * Format a comparison table across multiple companies.
     */
    private String formatComparison(List<FinancialData> data, List<String> requestedFields) {
        boolean showAll = requestedFields.contains("ALL") || requestedFields.isEmpty();

        // Build field list to show
        List<FieldDef> fields = new ArrayList<>();
        addFieldIfNeeded(fields, "Total Assets", "totalAssets", showAll, requestedFields);
        addFieldIfNeeded(fields, "Current Assets", "currentAssets", showAll, requestedFields);
        addFieldIfNeeded(fields, "Total Liabilities", "totalLiabilities", showAll, requestedFields);
        addFieldIfNeeded(fields, "Current Liabilities", "currentLiabilities", showAll, requestedFields);
        addFieldIfNeeded(fields, "Total Equity", "totalEquity", showAll, requestedFields);
        addFieldIfNeeded(fields, "Cash & Equivalents", "cashAndEquivalents", showAll, requestedFields);
        addFieldIfNeeded(fields, "Trade Receivables", "tradeReceivables", showAll, requestedFields);
        addFieldIfNeeded(fields, "Trade Payables", "tradePayables", showAll, requestedFields);
        addFieldIfNeeded(fields, "Revenue", "revenue", showAll, requestedFields);
        addFieldIfNeeded(fields, "Cost of Sales", "costOfSales", showAll, requestedFields);
        addFieldIfNeeded(fields, "Gross Profit", "grossProfit", showAll, requestedFields);
        addFieldIfNeeded(fields, "Net Income", "netIncome", showAll, requestedFields);

        if (fields.isEmpty()) {
            // Default to key metrics
            fields.add(new FieldDef("Revenue", "revenue"));
            fields.add(new FieldDef("Net Income", "netIncome"));
            fields.add(new FieldDef("Total Assets", "totalAssets"));
            fields.add(new FieldDef("Total Equity", "totalEquity"));
        }

        // Build markdown table
        StringBuilder sb = new StringBuilder("## Financial Comparison\n\n");
        sb.append("| Metric |");
        for (FinancialData fd : data) {
            sb.append(String.format(" %s |", fd.getCompanyName()));
        }
        sb.append("\n|---|");
        for (int i = 0; i < data.size(); i++) sb.append("---|");
        sb.append("\n");

        // Currency/unit row
        sb.append("| *Currency / Unit* |");
        for (FinancialData fd : data) {
            sb.append(String.format(" %s / %s |", fd.getCurrencyCode(), fd.getAmountsInUnit()));
        }
        sb.append("\n");

        // Data rows
        for (FieldDef field : fields) {
            sb.append(String.format("| %s |", field.label));
            for (FinancialData fd : data) {
                BigDecimal value = getFieldValue(fd, field.fieldName);
                String sym = currencySymbol(fd.getCurrencyCode());
                sb.append(String.format(" %s |", value != null ? sym + formatNumber(value) : "—"));
            }
            sb.append("\n");
        }

        sb.append("\n*Source: Extracted from annual filings. Structured data lookup — no LLM interpretation.*");
        return sb.toString();
    }

    /**
     * Format a multi-year trend table for a single company.
     * Groups all available years for the best-matching company in the KB.
     */
    private String formatTrend(List<FinancialData> allData, String question, List<String> requestedFields) {
        // Find all records for the best-matching company, ordered by fiscal year
        FinancialData representative = findBestMatch(allData, question);
        String companyName = representative.getCompanyName();

        List<FinancialData> companyData = allData.stream()
                .filter(fd -> companyName != null && companyName.equalsIgnoreCase(fd.getCompanyName()))
                .sorted(Comparator.comparing(fd -> fd.getFiscalYear() != null ? fd.getFiscalYear() : ""))
                .toList();

        if (companyData.size() == 1) {
            // Only one year available — fall back to single-company format with a note
            return formatSingleCompany(companyData.get(0), requestedFields) +
                    "\n\n*Only one fiscal year is available. Upload more annual filings to see trends.*";
        }

        // Determine which fields to show
        boolean showAll = requestedFields.contains("ALL") || requestedFields.isEmpty();
        List<FieldDef> fields = new ArrayList<>();
        addFieldIfNeeded(fields, "Revenue", "revenue", showAll, requestedFields);
        addFieldIfNeeded(fields, "Gross Profit", "grossProfit", showAll, requestedFields);
        addFieldIfNeeded(fields, "Net Income", "netIncome", showAll, requestedFields);
        addFieldIfNeeded(fields, "Total Assets", "totalAssets", showAll, requestedFields);
        addFieldIfNeeded(fields, "Total Equity", "totalEquity", showAll, requestedFields);
        addFieldIfNeeded(fields, "Cash & Equivalents", "cashAndEquivalents", showAll, requestedFields);
        if (fields.isEmpty()) {
            fields.add(new FieldDef("Revenue", "revenue"));
            fields.add(new FieldDef("Net Income", "netIncome"));
            fields.add(new FieldDef("Total Assets", "totalAssets"));
        }

        FinancialData first = companyData.get(0);
        String sym = currencySymbol(first.getCurrencyCode());
        String unit = first.getAmountsInUnit() != null ? first.getAmountsInUnit() : "";
        StringBuilder sb = new StringBuilder(String.format("## %s — Multi-Year Trend (%s, %s)\n\n",
                companyName, first.getCurrencyCode(), unit));

        // Header row: Metric | FY2022 | FY2023 | FY2024
        sb.append("| Metric |");
        for (FinancialData fd : companyData) sb.append(String.format(" FY%s |", fd.getFiscalYear()));
        sb.append("\n|---|");
        for (int i = 0; i < companyData.size(); i++) sb.append("---|");
        sb.append("\n");

        for (FieldDef field : fields) {
            sb.append(String.format("| %s |", field.label));
            BigDecimal prev = null;
            for (FinancialData fd : companyData) {
                BigDecimal value = getFieldValue(fd, field.fieldName);
                if (value != null) {
                    String cell = sym + formatNumber(value);
                    if (prev != null && prev.signum() != 0) {
                        double pctChange = value.subtract(prev)
                                .divide(prev.abs(), 4, RoundingMode.HALF_UP)
                                .doubleValue() * 100;
                        cell += String.format(" (%+.1f%%)", pctChange);
                    }
                    sb.append(String.format(" %s |", cell));
                    prev = value;
                } else {
                    sb.append(" — |");
                    prev = null;
                }
            }
            sb.append("\n");
        }

        sb.append("\n*Source: Extracted from annual filings. YoY % changes shown in parentheses.*");
        return sb.toString();
    }

    /**
     * Find the best matching company for a query by checking if the company name appears in the question.
     */
    private FinancialData findBestMatch(List<FinancialData> data, String question) {
        String q = question.toLowerCase();
        for (FinancialData fd : data) {
            if (fd.getCompanyName() != null) {
                // Check if company name or key parts appear in query
                String[] nameParts = fd.getCompanyName().toLowerCase().split("\\s+");
                for (String part : nameParts) {
                    if (part.length() > 2 && q.contains(part)) {
                        return fd;
                    }
                }
            }
        }
        // Default to first
        return data.get(0);
    }

    private BigDecimal getFieldValue(FinancialData fd, String fieldName) {
        return switch (fieldName) {
            case "totalAssets" -> fd.getTotalAssets();
            case "currentAssets" -> fd.getCurrentAssets();
            case "totalLiabilities" -> fd.getTotalLiabilities();
            case "currentLiabilities" -> fd.getCurrentLiabilities();
            case "totalEquity" -> fd.getTotalEquity();
            case "cashAndEquivalents" -> fd.getCashAndEquivalents();
            case "tradeReceivables" -> fd.getTradeReceivables();
            case "tradePayables" -> fd.getTradePayables();
            case "accumulatedProfit" -> fd.getAccumulatedProfit();
            case "revenue" -> fd.getRevenue();
            case "costOfSales" -> fd.getCostOfSales();
            case "grossProfit" -> fd.getGrossProfit();
            case "netIncome" -> fd.getNetIncome();
            default -> null;
        };
    }

    private void appendField(StringBuilder sb, String label, BigDecimal value, String sym, boolean include) {
        if (!include) return;
        if (value != null) {
            sb.append(String.format("- %s: %s%s\n", label, sym, formatNumber(value)));
        }
    }

    private void appendRatio(StringBuilder sb, String label, BigDecimal numerator, BigDecimal denominator, String suffix) {
        if (numerator != null && denominator != null && denominator.signum() != 0) {
            double ratio = numerator.divide(denominator, 4, RoundingMode.HALF_UP).doubleValue();
            sb.append(String.format("- %s: %.2f%s\n", label, ratio, suffix));
        }
    }

    private void appendMargin(StringBuilder sb, String label, BigDecimal numerator, BigDecimal denominator) {
        if (numerator != null && denominator != null && denominator.signum() != 0) {
            double pct = numerator.divide(denominator, 4, RoundingMode.HALF_UP).doubleValue() * 100;
            sb.append(String.format("- %s: %.1f%%\n", label, pct));
        }
    }

    private boolean hasAnyField(List<String> fields, String... names) {
        for (String name : names) {
            if (fields.contains(name)) return true;
        }
        return false;
    }

    private void addFieldIfNeeded(List<FieldDef> fields, String label, String fieldName,
                                   boolean showAll, List<String> requested) {
        if (showAll || requested.contains(fieldName)) {
            fields.add(new FieldDef(label, fieldName));
        }
    }

    private String currencySymbol(String code) {
        return CURRENCY_SYMBOLS.getOrDefault(code, code != null ? code + " " : "");
    }

    private String formatNumber(BigDecimal value) {
        return String.format("%,.0f", value);
    }

    private record FieldDef(String label, String fieldName) {}
}
