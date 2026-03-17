package com.rkp.tenk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    private ChunkingService chunkingService;
    private UUID knowledgeBaseId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        chunkingService = new ChunkingService();
        knowledgeBaseId = UUID.randomUUID();
        documentId = UUID.randomUUID();
    }

    @Test
    void shouldChunkSimpleDocument() {
        Document page1 = new Document("This is page one of the document with some financial data.");
        Document page2 = new Document("This is page two with more financial information and analysis.");

        List<Document> chunks = chunkingService.chunkDocuments(
                List.of(page1, page2), knowledgeBaseId, documentId, "en");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getMetadata())
                .containsEntry("knowledge_base_id", knowledgeBaseId.toString())
                .containsEntry("document_id", documentId.toString())
                .containsEntry("language", "en");
    }

    @Test
    void shouldDetectSecSectionBoundaries() {
        String text = """
                Some preamble text about the company.

                Item 1. Business
                We are a global technology company providing services.

                Item 1A. Risk Factors
                Our business faces several significant risks.
                """;
        Document page = new Document(text);

        List<Document> chunks = chunkingService.chunkDocuments(
                List.of(page), knowledgeBaseId, documentId, "en");

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);

        List<String> sectionNames = chunks.stream()
                .map(c -> (String) c.getMetadata().get("section_name"))
                .distinct()
                .toList();

        assertThat(sectionNames).contains("Preamble");
    }

    @Test
    void shouldHandleEmptyDocument() {
        Document emptyPage = new Document("");

        List<Document> chunks = chunkingService.chunkDocuments(
                List.of(emptyPage), knowledgeBaseId, documentId, "en");

        // Should produce at least an empty or minimal result, not throw
        assertThat(chunks).isNotNull();
    }

    @Test
    void shouldEnrichMetadataWithChunkIndex() {
        String longText = "A".repeat(5000) + " " + "B".repeat(5000);
        Document page = new Document(longText);

        List<Document> chunks = chunkingService.chunkDocuments(
                List.of(page), knowledgeBaseId, documentId, "en");

        if (chunks.size() > 1) {
            int firstIndex = (int) chunks.get(0).getMetadata().get("chunk_index");
            int secondIndex = (int) chunks.get(1).getMetadata().get("chunk_index");
            assertThat(secondIndex).isGreaterThan(firstIndex);
        }
    }

    @Test
    void shouldHandleNullLanguage() {
        Document page = new Document("Some text content.");

        List<Document> chunks = chunkingService.chunkDocuments(
                List.of(page), knowledgeBaseId, documentId, null);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getMetadata()).doesNotContainKey("language");
    }
}
