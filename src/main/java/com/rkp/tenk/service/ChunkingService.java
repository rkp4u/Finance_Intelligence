package com.rkp.tenk.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ChunkingService {

    private static final Pattern SEC_SECTION_PATTERN = Pattern.compile(
            "(?mi)^\\s*(ITEM\\s+\\d+[A-Z]?\\.?\\s*.+)$");

    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int MIN_CHUNK_SIZE_CHARS = 350;
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;
    private static final int MAX_NUM_CHUNKS = 10000;

    private final TokenTextSplitter tokenSplitter;

    public ChunkingService() {
        this.tokenSplitter = new TokenTextSplitter(
                DEFAULT_CHUNK_SIZE,
                MIN_CHUNK_SIZE_CHARS,
                MIN_CHUNK_LENGTH_TO_EMBED,
                MAX_NUM_CHUNKS,
                true
        );
    }

    /**
     * Chunk page documents into smaller pieces suitable for embedding.
     * Detects SEC section boundaries, preserves them in metadata,
     * and applies token-based splitting within each section.
     */
    public List<Document> chunkDocuments(List<Document> pageDocuments,
                                          UUID knowledgeBaseId,
                                          UUID documentId,
                                          String language) {
        // Concatenate all pages into sections
        List<Section> sections = extractSections(pageDocuments);

        List<Document> allChunks = new ArrayList<>();
        int chunkIndex = 0;

        for (Section section : sections) {
            // Create a document for this section and split it
            Document sectionDoc = new Document(section.content());
            List<Document> sectionChunks = tokenSplitter.apply(List.of(sectionDoc));

            for (Document chunk : sectionChunks) {
                // Enrich metadata
                Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                metadata.put("knowledge_base_id", knowledgeBaseId.toString());
                metadata.put("document_id", documentId.toString());
                metadata.put("section_name", section.name());
                metadata.put("page_number", section.startPage());
                metadata.put("chunk_index", chunkIndex);
                if (language != null) {
                    metadata.put("language", language);
                }

                allChunks.add(new Document(chunk.getText(), metadata));
                chunkIndex++;
            }
        }

        log.info("Created {} chunks from {} pages (knowledgeBaseId={}, documentId={})",
                allChunks.size(), pageDocuments.size(), knowledgeBaseId, documentId);
        return allChunks;
    }

    /**
     * Extract sections from page documents by detecting SEC section headers.
     */
    private List<Section> extractSections(List<Document> pageDocuments) {
        List<Section> sections = new ArrayList<>();
        StringBuilder currentContent = new StringBuilder();
        String currentSectionName = "Preamble";
        int currentStartPage = 1;

        for (int pageIdx = 0; pageIdx < pageDocuments.size(); pageIdx++) {
            Document page = pageDocuments.get(pageIdx);
            String pageText = page.getText();
            int pageNumber = pageIdx + 1;

            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            // Check for section boundaries in this page
            Matcher matcher = SEC_SECTION_PATTERN.matcher(pageText);
            int lastEnd = 0;

            while (matcher.find()) {
                // Add text before this section header to current section
                String textBefore = pageText.substring(lastEnd, matcher.start()).trim();
                if (!textBefore.isEmpty()) {
                    currentContent.append(textBefore).append("\n");
                }

                // Save current section if it has content
                if (!currentContent.isEmpty()) {
                    sections.add(new Section(currentSectionName, currentContent.toString().trim(), currentStartPage));
                    currentContent = new StringBuilder();
                }

                // Start new section
                currentSectionName = matcher.group(1).trim();
                currentStartPage = pageNumber;
                lastEnd = matcher.end();
            }

            // Add remaining text on this page to current section
            String remainingText = pageText.substring(lastEnd).trim();
            if (!remainingText.isEmpty()) {
                currentContent.append(remainingText).append("\n");
            }
        }

        // Don't forget the last section
        if (!currentContent.isEmpty()) {
            sections.add(new Section(currentSectionName, currentContent.toString().trim(), currentStartPage));
        }

        // If no sections were detected, treat entire document as one section
        if (sections.isEmpty() && !pageDocuments.isEmpty()) {
            StringBuilder fullText = new StringBuilder();
            for (Document page : pageDocuments) {
                if (page.getText() != null) {
                    fullText.append(page.getText()).append("\n");
                }
            }
            sections.add(new Section("Full Document", fullText.toString().trim(), 1));
        }

        log.debug("Detected {} sections in document", sections.size());
        return sections;
    }

    private record Section(String name, String content, int startPage) {}
}
