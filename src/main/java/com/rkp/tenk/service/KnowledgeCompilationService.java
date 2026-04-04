package com.rkp.tenk.service;

import com.rkp.tenk.config.CompilationProperties;
import com.rkp.tenk.model.entity.DocumentRecord;
import com.rkp.tenk.model.entity.DocumentSummary;
import com.rkp.tenk.model.entity.KnowledgeBase;
import com.rkp.tenk.model.enums.SummaryType;
import com.rkp.tenk.repository.DocumentRecordRepository;
import com.rkp.tenk.repository.DocumentSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Synthesizes section-level summaries from raw document chunks using LLM.
 *
 * <p>Groups chunks by their detected SEC section (from metadata {@code section_name}),
 * then asks the LLM to produce a coherent summary for each section.  Summaries are
 * stored in the {@code document_summary} table and also added back to the vector store
 * with {@code chunk_type=SUMMARY} metadata so they are retrieved alongside raw chunks.</p>
 *
 * <p>Compilation is triggered after financial extraction during ingestion and is
 * completely failure-isolated — any exception leaves the RAG pipeline unaffected.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeCompilationService {

    private static final String SUMMARY_PROMPT_TEMPLATE = """
            You are a financial analyst summarizing a section of an annual report filing.
            Produce a concise, factual summary (3-6 sentences) of the following section.
            Focus on key facts, figures, strategies, or risks — whatever is most significant.
            Do not add any text before or after the summary itself.

            Section: %s

            Content:
            %s

            Summary:
            """;

    private final CompilationProperties compilationProperties;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final DocumentSummaryRepository summaryRepository;
    private final DocumentRecordRepository documentRecordRepository;

    @Value("${app.model-name:unknown}")
    private String modelName;

    /**
     * Compile section summaries for a document from its chunks.
     * Safe to call repeatedly — existing summaries are skipped.
     *
     * @param chunks          the chunks produced during ingestion
     * @param documentId      the persisted DocumentRecord UUID
     * @param knowledgeBaseId the KB this document belongs to
     */
    public void compile(List<Document> chunks, UUID documentId, UUID knowledgeBaseId) {
        if (!compilationProperties.enabled()) {
            log.debug("Knowledge compilation disabled, skipping for document {}", documentId);
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            log.debug("No chunks for document {}, skipping compilation", documentId);
            return;
        }

        DocumentRecord docRecord = documentRecordRepository.findById(documentId).orElse(null);
        if (docRecord == null) {
            log.warn("Document record not found for compilation: {}", documentId);
            return;
        }

        log.info("Starting knowledge compilation for document {}", documentId);

        // Group chunks by section
        Map<String, List<Document>> bySection = chunks.stream()
                .collect(Collectors.groupingBy(c -> {
                    Object sn = c.getMetadata().get("section_name");
                    return sn != null ? sn.toString() : "General";
                }));

        List<Document> summaryVectors = new ArrayList<>();
        int compiled = 0;

        for (Map.Entry<String, List<Document>> entry : bySection.entrySet()) {
            String sectionName = entry.getKey();
            List<Document> sectionChunks = entry.getValue();

            // Skip trivially small sections
            int totalWords = sectionChunks.stream()
                    .mapToInt(c -> c.getText() != null ? c.getText().split("\\s+").length : 0)
                    .sum();
            if (totalWords < 50) {
                log.debug("Skipping section '{}' — only {} words", sectionName, totalWords);
                continue;
            }

            SummaryType summaryType = classifySection(sectionName);

            // Skip if already compiled for this section type
            if (summaryRepository.findByDocumentRecordIdAndSummaryType(documentId, summaryType).isPresent()) {
                log.debug("Summary already exists for doc {} section type {}, skipping", documentId, summaryType);
                continue;
            }

            try {
                String summaryText = synthesizeSection(sectionName, sectionChunks);
                if (summaryText == null || summaryText.isBlank()) continue;

                DocumentSummary summary = new DocumentSummary();
                summary.setDocumentRecord(docRecord);
                KnowledgeBase kb = new KnowledgeBase();
                kb.setId(knowledgeBaseId);
                summary.setKnowledgeBase(kb);
                summary.setSummaryType(summaryType);
                summary.setContent(summaryText);
                summary.setSectionSource(sectionName);
                summary.setWordCount(summaryText.split("\\s+").length);
                summary.setModelUsed(modelName);
                summaryRepository.save(summary);

                // Also push summary into vector store for RAG retrieval
                Map<String, Object> meta = new HashMap<>();
                meta.put("knowledge_base_id", knowledgeBaseId.toString());
                meta.put("document_id", documentId.toString());
                meta.put("section_name", sectionName);
                meta.put("chunk_type", "SUMMARY");
                meta.put("summary_type", summaryType.name());
                summaryVectors.add(new Document(summaryText, meta));

                compiled++;
                log.debug("Compiled summary for section '{}' ({} words)", sectionName, summary.getWordCount());

            } catch (Exception e) {
                log.warn("Failed to compile summary for section '{}' in doc {}: {}", sectionName, documentId, e.getMessage());
            }
        }

        if (!summaryVectors.isEmpty()) {
            try {
                vectorStore.add(summaryVectors);
            } catch (Exception e) {
                log.warn("Failed to store {} summary vectors for doc {}: {}", summaryVectors.size(), documentId, e.getMessage());
            }
        }

        log.info("Knowledge compilation complete for document {}: {} summaries generated", documentId, compiled);
    }

    private String synthesizeSection(String sectionName, List<Document> chunks) {
        // Cap input to avoid context overflow
        int maxChunks = compilationProperties.maxChunksPerSection();
        List<Document> capped = chunks.size() > maxChunks ? chunks.subList(0, maxChunks) : chunks;

        String combined = capped.stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"));

        if (combined.length() > compilationProperties.maxSummaryChars()) {
            combined = combined.substring(0, compilationProperties.maxSummaryChars());
        }

        String prompt = SUMMARY_PROMPT_TEMPLATE.formatted(sectionName, combined);
        return chatClient.prompt().user(prompt).call().content();
    }

    /**
     * Map a SEC section name to a SummaryType.
     */
    private SummaryType classifySection(String sectionName) {
        if (sectionName == null) return SummaryType.GENERAL;
        String lower = sectionName.toLowerCase();
        if (lower.contains("item 1a") || lower.contains("risk")) return SummaryType.RISK_PROFILE;
        if (lower.contains("item 7") || lower.contains("management") || lower.contains("md&a")) return SummaryType.MANAGEMENT_DISCUSSION;
        if (lower.contains("item 8") || lower.contains("financial statement")) return SummaryType.FINANCIAL_HIGHLIGHTS;
        if (lower.contains("item 1") || lower.contains("business")) return SummaryType.BUSINESS_OVERVIEW;
        return SummaryType.GENERAL;
    }
}
