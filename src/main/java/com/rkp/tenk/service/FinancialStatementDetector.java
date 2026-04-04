package com.rkp.tenk.service;

import com.rkp.tenk.model.dto.FinancialStatementPages;
import com.rkp.tenk.model.enums.AccountingStandard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Identifies which pages in a document contain financial statements
 * (balance sheet, income statement) using keyword-based scoring.
 */
@Service
@Slf4j
public class FinancialStatementDetector {

    private static final List<String> BALANCE_SHEET_KEYWORDS = List.of(
            "consolidated balance sheet",
            "consolidated balance sheets",
            "statement of financial position",
            "statements of financial position",
            "total assets",
            "total liabilities",
            "stockholders' equity",
            "shareholders' equity",
            "total equity",
            "current assets",
            "current liabilities",
            "non-current assets",
            "noncurrent assets"
    );

    private static final List<String> INCOME_STATEMENT_KEYWORDS = List.of(
            "consolidated statement of operations",
            "consolidated statements of operations",
            "statement of profit and loss",
            "statement of profit or loss",
            "statement of comprehensive income",
            "statements of income",
            "income statement",
            "total revenue",
            "net revenue",
            "net sales",
            "revenue from operations",
            "cost of goods sold",
            "cost of sales",
            "cost of revenue",
            "gross profit",
            "gross margin",
            "net income",
            "net profit",
            "profit for the year",
            "profit for the period",
            "operating revenue",
            "service revenue",
            "turnover",
            "revenue from operations"
    );

    // Notes pages that contain breakdowns of receivables, payables, etc.
    // Lower threshold (score >= 1) is used for notes since these keywords are very specific
    private static final List<String> NOTES_KEYWORDS = List.of(
            "trade receivables",
            "accounts receivable",
            "trade payables",
            "accounts payable",
            "allowance for doubtful",
            "receivables consisted of",
            "receivables comprise",
            "payables consisted of",
            "payables comprise",
            "government incentives",
            "other receivables",
            "accrued expenses",
            "other payables"
    );

    @Value("${financial-extraction.max-pages-per-statement:5}")
    private int maxPagesPerStatement;

    /**
     * Detect financial statement pages from the extracted document pages.
     * Returns the text and page numbers for balance sheet and income statement sections.
     */
    public FinancialStatementPages detect(List<Document> pages) {
        if (pages == null || pages.isEmpty()) {
            log.warn("No pages provided for financial statement detection");
            return FinancialStatementPages.empty();
        }

        List<ScoredPage> balanceSheetCandidates = new ArrayList<>();
        List<ScoredPage> incomeStatementCandidates = new ArrayList<>();
        List<ScoredPage> notesCandidates = new ArrayList<>();
        AccountingStandard detectedStandard = AccountingStandard.UNKNOWN;

        for (int i = 0; i < pages.size(); i++) {
            Document page = pages.get(i);
            String text = page.getText();
            if (text == null || text.isBlank()) continue;

            String lowerText = text.toLowerCase();
            int pageNumber = extractPageNumber(page, i + 1);

            // Score for balance sheet
            int bsScore = scoreText(lowerText, BALANCE_SHEET_KEYWORDS);
            if (bsScore >= 2) {
                balanceSheetCandidates.add(new ScoredPage(pageNumber, bsScore, text));
            }

            // Score for income statement
            int isScore = scoreText(lowerText, INCOME_STATEMENT_KEYWORDS);
            if (isScore >= 2) {
                incomeStatementCandidates.add(new ScoredPage(pageNumber, isScore, text));
            }

            // Score for notes with receivables/payables breakdowns (lower threshold — keywords are specific)
            int notesScore = scoreText(lowerText, NOTES_KEYWORDS);
            if (notesScore >= 1) {
                notesCandidates.add(new ScoredPage(pageNumber, notesScore, text));
            }

            // Detect accounting standard (once, from any page)
            if (detectedStandard == AccountingStandard.UNKNOWN) {
                detectedStandard = detectStandard(lowerText);
            }
        }

        // Sort by score descending, take top N pages
        balanceSheetCandidates.sort(Comparator.comparingInt(ScoredPage::score).reversed());
        incomeStatementCandidates.sort(Comparator.comparingInt(ScoredPage::score).reversed());
        notesCandidates.sort(Comparator.comparingInt(ScoredPage::score).reversed());

        List<ScoredPage> bsPages = balanceSheetCandidates.stream()
                .limit(maxPagesPerStatement).toList();
        List<ScoredPage> isPages = incomeStatementCandidates.stream()
                .limit(maxPagesPerStatement).toList();
        // Exclude pages already in BS/IS to avoid duplicate context
        Set<Integer> bsIsPageNums = new HashSet<>();
        bsPages.forEach(p -> bsIsPageNums.add(p.pageNumber()));
        isPages.forEach(p -> bsIsPageNums.add(p.pageNumber()));

        List<ScoredPage> notesPages = notesCandidates.stream()
                .filter(p -> !bsIsPageNums.contains(p.pageNumber()))
                .limit(maxPagesPerStatement).toList();

        boolean hasFinancialStatements = !bsPages.isEmpty() || !isPages.isEmpty();

        String bsText = bsPages.isEmpty() ? null :
                String.join("\n\n--- Page Break ---\n\n",
                        bsPages.stream().map(ScoredPage::text).toList());
        String isText = isPages.isEmpty() ? null :
                String.join("\n\n--- Page Break ---\n\n",
                        isPages.stream().map(ScoredPage::text).toList());
        String ntText = notesPages.isEmpty() ? null :
                String.join("\n\n--- Page Break ---\n\n",
                        notesPages.stream().map(ScoredPage::text).toList());

        List<Integer> bsPageNums = bsPages.stream().map(ScoredPage::pageNumber).toList();
        List<Integer> isPageNums = isPages.stream().map(ScoredPage::pageNumber).toList();
        List<Integer> ntPageNums = notesPages.stream().map(ScoredPage::pageNumber).toList();

        log.info("Financial statement detection: BS pages={}, IS pages={}, Notes pages={}, standard={}",
                bsPageNums, isPageNums, ntPageNums, detectedStandard);

        return new FinancialStatementPages(bsText, bsPageNums, isText, isPageNums,
                ntText, ntPageNums, detectedStandard, hasFinancialStatements, null, null, null);
    }

    private int scoreText(String lowerText, List<String> keywords) {
        // Normalize whitespace: PDFBox often inserts extra spaces in extracted text
        String normalized = lowerText.replaceAll("\\s+", " ");
        int score = 0;
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private AccountingStandard detectStandard(String lowerText) {
        // Check for US GAAP indicators
        if (lowerText.contains("10-k") || lowerText.contains("form 10-k")
                || lowerText.contains("securities and exchange commission")
                || lowerText.contains("u.s. gaap") || lowerText.contains("us gaap")) {
            return AccountingStandard.US_GAAP;
        }

        // Check for Ind-AS indicators
        if (lowerText.contains("ind as") || lowerText.contains("ind-as")
                || lowerText.contains("companies act, 2013")
                || lowerText.contains("indian accounting standard")) {
            return AccountingStandard.IND_AS;
        }

        // Check for IFRS indicators
        if (lowerText.contains("international financial reporting standards")
                || lowerText.contains("ifrs")) {
            return AccountingStandard.IFRS;
        }

        return AccountingStandard.UNKNOWN;
    }

    private int extractPageNumber(Document page, int fallback) {
        Object pageNum = page.getMetadata().get("page_number");
        if (pageNum instanceof Number n) return n.intValue();
        if (pageNum instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { /* ignore */ }
        }
        return fallback;
    }

    private record ScoredPage(int pageNumber, int score, String text) {}
}
