package com.rkp.tenk.controller;

import com.rkp.tenk.model.dto.DocumentSummaryResponse;
import com.rkp.tenk.model.entity.DocumentSummary;
import com.rkp.tenk.repository.DocumentSummaryRepository;
import com.rkp.tenk.service.KnowledgeBaseService;
import com.rkp.tenk.service.KnowledgeCompilationService;
import com.rkp.tenk.repository.DocumentRecordRepository;
import com.rkp.tenk.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}")
@RequiredArgsConstructor
@Tag(name = "Summaries", description = "Document knowledge compilation summaries")
public class SummaryController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentSummaryRepository summaryRepository;

    @GetMapping("/summaries")
    @Operation(summary = "List all compiled summaries in a knowledge base")
    public ResponseEntity<List<DocumentSummaryResponse>> listAll(@PathVariable UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return ResponseEntity.ok(
                summaryRepository.findByKnowledgeBaseId(kbId).stream()
                        .map(this::toResponse).toList());
    }

    @GetMapping("/documents/{docId}/summaries")
    @Operation(summary = "List compiled summaries for a specific document")
    public ResponseEntity<List<DocumentSummaryResponse>> listForDocument(
            @PathVariable UUID kbId,
            @PathVariable UUID docId) {
        knowledgeBaseService.findOrThrow(kbId);
        return ResponseEntity.ok(
                summaryRepository.findByDocumentRecordId(docId).stream()
                        .map(this::toResponse).toList());
    }

    private DocumentSummaryResponse toResponse(DocumentSummary s) {
        return new DocumentSummaryResponse(
                s.getId(),
                s.getDocumentRecord().getId(),
                s.getSummaryType().name(),
                s.getContent(),
                s.getSectionSource(),
                s.getWordCount(),
                s.getModelUsed(),
                s.getCompilationTimeMs(),
                s.getCreatedAt()
        );
    }
}
