package com.rkp.tenk.controller;

import com.rkp.tenk.model.dto.OrchestratorResult;
import com.rkp.tenk.model.dto.QueryRequest;
import com.rkp.tenk.model.dto.QueryResponse;
import com.rkp.tenk.model.dto.QueryResponse.AgenticMetadata;
import com.rkp.tenk.model.dto.QueryResponse.QueryMetadata;
import com.rkp.tenk.model.dto.QueryResponse.SourceChunk;
import com.rkp.tenk.service.AgenticRagOrchestrator;
import com.rkp.tenk.service.KnowledgeBaseService;
import com.rkp.tenk.service.QueryClassifier;
import com.rkp.tenk.service.StructuredQueryService;
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
    private final AgenticRagOrchestrator orchestrator;
    private final QueryClassifier queryClassifier;
    private final StructuredQueryService structuredQueryService;

    @Value("${app.model-name:unknown}")
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

        // Step 1: Classify the query
        QueryClassifier.ClassificationResult classification = queryClassifier.classify(request.question());
        log.info("Query classified as {} with fields {}", classification.type(), classification.detectedFields());

        // Step 2: Try structured answer for financial data queries
        if (classification.type() != QueryClassifier.QueryType.NARRATIVE) {
            QueryResponse structured = structuredQueryService.answer(
                    request.question(), kbId, classification);
            if (structured != null) {
                log.info("Answered via structured lookup in {}ms", structured.metadata().retrievalTimeMs());
                return ResponseEntity.ok(structured);
            }
            log.info("No structured data available, falling through to RAG");
        }

        // Step 3: Fall through to RAG pipeline
        OrchestratorResult result = orchestrator.execute(
                request.question(), kbId, request.topK(),
                request.similarityThreshold(), request.agenticMode());

        // Build source chunks for response
        List<SourceChunk> sources = result.sourceDocuments().stream()
                .map(this::toSourceChunk)
                .toList();

        AgenticMetadata agenticMetadata = result.agenticMode()
                ? new AgenticMetadata(
                        result.decompositionTimeMs(),
                        result.evaluationTimeMs(),
                        result.evaluationRounds(),
                        result.subQueriesUsed())
                : null;

        QueryMetadata metadata = new QueryMetadata(
                result.sourceDocuments().size(),
                result.retrievalTimeMs(),
                result.generationTimeMs(),
                modelName,
                "RAG",
                agenticMetadata
        );

        return ResponseEntity.ok(new QueryResponse(result.answer(), sources, metadata));
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
