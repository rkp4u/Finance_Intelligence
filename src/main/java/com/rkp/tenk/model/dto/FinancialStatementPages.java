package com.rkp.tenk.model.dto;

import com.rkp.tenk.model.enums.AccountingStandard;

import java.util.List;

/**
 * Result of financial statement page detection.
 * Contains the concatenated text of identified balance sheet, income statement, and notes pages,
 * plus optional per-section Docling enrichments (structured Markdown tables).
 *
 * <p>When Docling is enabled, each section is enriched independently. If Docling fails for one
 * section, that section falls back to PDFBox text while the others remain enriched. Use the
 * {@code effective*Text()} methods to transparently get the best available text per section.</p>
 */
public record FinancialStatementPages(
        String balanceSheetText,
        List<Integer> balanceSheetPageNumbers,
        String incomeStatementText,
        List<Integer> incomeStatementPageNumbers,
        String notesText,
        List<Integer> notesPageNumbers,
        AccountingStandard detectedStandard,
        boolean hasFinancialStatements,
        String enrichedBalanceSheetText,
        String enrichedIncomeStatementText,
        String enrichedNotesText
) {
    public static FinancialStatementPages empty() {
        return new FinancialStatementPages(null, List.of(), null, List.of(),
                null, List.of(), AccountingStandard.UNKNOWN, false, null, null, null);
    }

    /**
     * Returns a copy with per-section Docling enrichments set.
     * Any parameter may be null — that section will fall back to PDFBox text.
     */
    public FinancialStatementPages withSectionEnrichments(
            String enrichedBS, String enrichedIS, String enrichedNotes) {
        return new FinancialStatementPages(
                balanceSheetText, balanceSheetPageNumbers,
                incomeStatementText, incomeStatementPageNumbers,
                notesText, notesPageNumbers,
                detectedStandard, hasFinancialStatements,
                enrichedBS, enrichedIS, enrichedNotes
        );
    }

    /** True if at least one section has Docling-enriched text. */
    public boolean hasAnyEnrichedText() {
        return isNonBlank(enrichedBalanceSheetText)
                || isNonBlank(enrichedIncomeStatementText)
                || isNonBlank(enrichedNotesText);
    }

    /**
     * Best available balance sheet text: Docling-enriched (pipe-delimited tables) if present,
     * otherwise raw PDFBox text.
     */
    public String effectiveBalanceSheetText() {
        return isNonBlank(enrichedBalanceSheetText) ? enrichedBalanceSheetText : balanceSheetText;
    }

    /**
     * Best available income statement text: Docling-enriched if present, otherwise PDFBox.
     */
    public String effectiveIncomeStatementText() {
        return isNonBlank(enrichedIncomeStatementText) ? enrichedIncomeStatementText : incomeStatementText;
    }

    /**
     * Best available notes text: Docling-enriched if present, otherwise PDFBox.
     */
    public String effectiveNotesText() {
        return isNonBlank(enrichedNotesText) ? enrichedNotesText : notesText;
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
