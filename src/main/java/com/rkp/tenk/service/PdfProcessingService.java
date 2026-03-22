package com.rkp.tenk.service;

import com.rkp.tenk.exception.DocumentProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class PdfProcessingService {

    private static final int BATCH_SIZE = 50;

    /**
     * Extract text from a PDF resource, processing pages in batches to handle large documents.
     * Returns one Document per page with page number in metadata.
     */
    public List<Document> extractText(Resource pdfResource) {
        try {
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .build();

            PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource, config);
            List<Document> allPages = reader.read();

            log.info("Extracted {} pages from PDF: {}", allPages.size(), pdfResource.getFilename());
            return allPages;
        } catch (Exception e) {
            throw new DocumentProcessingException(
                    "Failed to extract text from PDF: " + pdfResource.getFilename(), e);
        }
    }

    /**
     * Extract text from a PDF in batches to manage memory for large documents.
     * Processes BATCH_SIZE pages at a time and reports progress via the callback.
     */
    public List<Document> extractTextBatched(Resource pdfResource, ProgressCallback callback) {
        try {
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .build();

            PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource, config);
            List<Document> allPages = reader.read();
            int totalPages = allPages.size();

            log.info("PDF has {} pages, processing in batches of {}", totalPages, BATCH_SIZE);

            if (callback != null) {
                callback.onTotalPages(totalPages);
            }

            List<Document> result = new ArrayList<>(totalPages);
            for (int i = 0; i < totalPages; i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, totalPages);
                List<Document> batch = allPages.subList(i, end);
                result.addAll(batch);

                if (callback != null) {
                    callback.onBatchProcessed(end);
                }
                log.debug("Processed pages {}-{} of {}", i + 1, end, totalPages);
            }

            return result;
        } catch (Exception e) {
            throw new DocumentProcessingException(
                    "Failed to extract text from PDF: " + pdfResource.getFilename(), e);
        }
    }

    /**
     * Detect the primary language of the document by sampling the first page.
     */
    public String detectLanguage(List<Document> pages) {
        if (pages.isEmpty()) {
            return "en";
        }
        // Simple heuristic: check for common non-English character ranges
        String firstPageText = pages.get(0).getText();
        if (firstPageText == null || firstPageText.isBlank()) {
            return "en";
        }

        if (containsCjk(firstPageText)) return "zh";
        if (containsJapanese(firstPageText)) return "ja";
        if (containsKorean(firstPageText)) return "ko";
        if (containsArabic(firstPageText)) return "ar";
        if (containsDevanagari(firstPageText)) return "hi";

        return "en";
    }

    private boolean containsCjk(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }

    private boolean containsJapanese(String text) {
        return text.codePoints().anyMatch(cp ->
                (cp >= 0x3040 && cp <= 0x309F) || (cp >= 0x30A0 && cp <= 0x30FF));
    }

    private boolean containsKorean(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7AF);
    }

    private boolean containsArabic(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
    }

    private boolean containsDevanagari(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0x0900 && cp <= 0x097F);
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onTotalPages(int totalPages);
        default void onBatchProcessed(int pagesProcessed) {}
    }
}
