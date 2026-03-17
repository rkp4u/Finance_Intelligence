package com.rkp.tenk.controller;

import com.rkp.tenk.model.dto.QueryRequest;
import com.rkp.tenk.model.dto.QueryResponse;
import com.rkp.tenk.model.dto.QueryResponse.QueryMetadata;
import com.rkp.tenk.model.dto.QueryResponse.SourceChunk;
import com.rkp.tenk.service.GenerationService;
import com.rkp.tenk.service.KnowledgeBaseService;
import com.rkp.tenk.service.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/query")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Query", description = "RAG query endpoints for asking questions against knowledge bases")
public class QueryController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final GenerationService generationService;

    @Value("${spring.ai.ollama.chat.model:unknown}")
    private String modelName;

    @PostMapping
    @Operation(summary = "Query a knowledge base with a natural language question")
    public ResponseEntity<QueryResponse> query(
            @PathVariable UUID kbId,
            @Valid @RequestBody QueryRequest request) {

        // Validate knowledge base exists
        knowledgeBaseService.findOrThrow(kbId);

        log.info("Query received for knowledge base {}: {}", kbId,
                request.question().substring(0, Math.min(100, request.question().length())));

        // Retrieve relevant chunks
        long retrievalStart = System.currentTimeMillis();
        List<Document> relevantChunks = retrievalService.retrieveRelevantChunks(
                request.question(), kbId, request.topK(), request.similarityThreshold());
        long retrievalTimeMs = System.currentTimeMillis() - retrievalStart;

        // Generate answer
        long generationStart = System.currentTimeMillis();
        String answer = generationService.generateAnswer(request.question(), relevantChunks);
        long generationTimeMs = System.currentTimeMillis() - generationStart;

        // Build source chunks for response
        List<SourceChunk> sources = relevantChunks.stream()
                .map(this::toSourceChunk)
                .toList();

        QueryMetadata metadata = new QueryMetadata(
                relevantChunks.size(),
                retrievalTimeMs,
                generationTimeMs,
                modelName
        );

        return ResponseEntity.ok(new QueryResponse(answer, sources, metadata));
    }

    private SourceChunk toSourceChunk(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        return new SourceChunk(
                truncateContent(doc.getText(), 500),
                getMetadataString(meta, "section_name"),
                getMetadataInt(meta, "page_number"),
                doc.getScore() != null ? doc.getScore().doubleValue() : null,
                getMetadataUUID(meta, "document_id")
        );
    }

    private String truncateContent(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private String getMetadataString(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getMetadataInt(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private UUID getMetadataUUID(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value instanceof UUID u) return u;
        if (value instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }
}
