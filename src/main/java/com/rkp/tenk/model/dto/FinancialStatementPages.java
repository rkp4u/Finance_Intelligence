package com.rkp.tenk.model.dto;

import com.rkp.tenk.model.enums.AccountingStandard;

import java.util.List;

/**
 * Result of financial statement page detection.
 * Contains the concatenated text of identified balance sheet and income statement pages.
 */
public record FinancialStatementPages(
        String balanceSheetText,
        List<Integer> balanceSheetPageNumbers,
        String incomeStatementText,
        List<Integer> incomeStatementPageNumbers,
        String notesText,
        List<Integer> notesPageNumbers,
        AccountingStandard detectedStandard,
        boolean hasFinancialStatements
) {
    public static FinancialStatementPages empty() {
        return new FinancialStatementPages(null, List.of(), null, List.of(),
                null, List.of(), AccountingStandard.UNKNOWN, false);
    }
}
