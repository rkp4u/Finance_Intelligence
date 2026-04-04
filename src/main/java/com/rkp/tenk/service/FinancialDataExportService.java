package com.rkp.tenk.service;

import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.enums.ExtractionStatus;
import com.rkp.tenk.repository.FinancialDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Exports financial data as CSV for analyst download.
 */
@Service
@RequiredArgsConstructor
public class FinancialDataExportService {

    private final FinancialDataRepository financialDataRepository;

    private static final String CSV_HEADER =
            "company_name,fiscal_year,fiscal_year_end_date,currency_code,accounting_standard,amounts_in_unit," +
            "total_assets,current_assets,total_liabilities,current_liabilities,total_equity," +
            "cash_and_equivalents,trade_receivables,trade_payables,accumulated_profit," +
            "revenue,cost_of_sales,gross_profit,net_income," +
            "extraction_status,validation_status,extraction_confidence\n";

    /**
     * Build a CSV string for all completed financial data in a knowledge base.
     */
    public String exportKnowledgeBase(UUID kbId) {
        List<FinancialData> data = financialDataRepository
                .findByKnowledgeBaseIdAndExtractionStatus(kbId, ExtractionStatus.COMPLETED);

        StringBuilder sb = new StringBuilder(CSV_HEADER);
        for (FinancialData fd : data) {
            sb.append(csvRow(fd));
        }
        return sb.toString();
    }

    private String csvRow(FinancialData fd) {
        return String.join(",",
                q(fd.getCompanyName()),
                q(fd.getFiscalYear()),
                q(fd.getFiscalYearEndDate() != null ? fd.getFiscalYearEndDate().toString() : null),
                q(fd.getCurrencyCode()),
                q(fd.getAccountingStandard() != null ? fd.getAccountingStandard().name() : null),
                q(fd.getAmountsInUnit()),
                n(fd.getTotalAssets()),
                n(fd.getCurrentAssets()),
                n(fd.getTotalLiabilities()),
                n(fd.getCurrentLiabilities()),
                n(fd.getTotalEquity()),
                n(fd.getCashAndEquivalents()),
                n(fd.getTradeReceivables()),
                n(fd.getTradePayables()),
                n(fd.getAccumulatedProfit()),
                n(fd.getRevenue()),
                n(fd.getCostOfSales()),
                n(fd.getGrossProfit()),
                n(fd.getNetIncome()),
                q(fd.getExtractionStatus().name()),
                q(fd.getValidationStatus() != null ? fd.getValidationStatus().name() : null),
                fd.getExtractionConfidence() != null ? String.valueOf(fd.getExtractionConfidence()) : ""
        ) + "\n";
    }

    /** Quote a string value for CSV, escaping embedded quotes and formula injection. */
    private String q(String value) {
        if (value == null) return "";
        // Prevent CSV formula injection: prefix with single-quote if starts with =, +, -, @
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Format a numeric value for CSV. */
    private String n(BigDecimal value) {
        return value != null ? value.toPlainString() : "";
    }
}
