package com.rkp.tenk.controller;

import com.rkp.tenk.model.dto.DocumentUploadResponse;
import com.rkp.tenk.model.entity.DocumentRecord;
import com.rkp.tenk.model.entity.KnowledgeBase;
import com.rkp.tenk.exception.ResourceNotFoundException;
import com.rkp.tenk.repository.DocumentRecordRepository;
import com.rkp.tenk.service.DocumentIngestionService;
import com.rkp.tenk.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Documents", description = "Upload and manage PDF documents within knowledge bases")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRecordRepository documentRecordRepository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a PDF document for processing")
    public ResponseEntity<DocumentUploadResponse> upload(
            @PathVariable UUID kbId,
            @RequestParam("file") MultipartFile file) throws IOException {

        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        DocumentRecord record = ingestionService.initiateIngestion(file, kb);

        // Start async processing with the file bytes
        byte[] fileBytes = file.getBytes();
        ingestionService.processDocumentAsync(record.getId(), fileBytes, file.getOriginalFilename());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(record));
    }

    @GetMapping
    @Operation(summary = "List all documents in a knowledge base")
    public ResponseEntity<List<DocumentUploadResponse>> list(@PathVariable UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        List<DocumentUploadResponse> docs = documentRecordRepository.findByKnowledgeBaseId(kbId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(docs);
    }

    @GetMapping("/{docId}")
    @Operation(summary = "Get document status and details")
    public ResponseEntity<DocumentUploadResponse> getStatus(
            @PathVariable UUID kbId,
            @PathVariable UUID docId) {
        knowledgeBaseService.findOrThrow(kbId);
        DocumentRecord record = documentRecordRepository.findById(docId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", docId));
        return ResponseEntity.ok(toResponse(record));
    }

    @DeleteMapping("/{docId}")
    @Operation(summary = "Delete a document and its embeddings")
    public ResponseEntity<Void> delete(
            @PathVariable UUID kbId,
            @PathVariable UUID docId) {
        knowledgeBaseService.findOrThrow(kbId);
        DocumentRecord record = documentRecordRepository.findById(docId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", docId));

        ingestionService.deleteDocumentVectors(docId);
        documentRecordRepository.delete(record);

        log.info("Deleted document: id={}, knowledgeBaseId={}", docId, kbId);
        return ResponseEntity.noContent().build();
    }

    private DocumentUploadResponse toResponse(DocumentRecord record) {
        return new DocumentUploadResponse(
                record.getId(),
                record.getOriginalFilename(),
                record.getFileSize(),
                record.getStatus(),
                record.getTotalPages(),
                record.getPagesProcessed(),
                record.getChunkCount(),
                record.getLanguage(),
                record.getErrorMessage(),
                record.getCreatedAt(),
                record.getProcessedAt()
        );
    }
}
