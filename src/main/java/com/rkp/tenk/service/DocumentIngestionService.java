package com.rkp.tenk.service;

import com.rkp.tenk.exception.DocumentProcessingException;
import com.rkp.tenk.model.entity.DocumentRecord;
import com.rkp.tenk.model.entity.KnowledgeBase;
import com.rkp.tenk.model.enums.DocumentStatus;
import com.rkp.tenk.repository.DocumentRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentIngestionService {

    private static final int VECTOR_STORE_BATCH_SIZE = 50;

    private final PdfProcessingService pdfProcessingService;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final DocumentRecordRepository documentRecordRepository;
    private final FinancialExtractionService financialExtractionService;
    private final KnowledgeCompilationService knowledgeCompilationService;

    /**
     * Create a document record and start async processing.
     */
    @Transactional
    public DocumentRecord initiateIngestion(MultipartFile file, KnowledgeBase knowledgeBase) {
        validatePdf(file);

        String storedFilename = UUID.randomUUID() + ".pdf";

        DocumentRecord record = new DocumentRecord();
        record.setKnowledgeBase(knowledgeBase);
        record.setFilename(storedFilename);
        record.setOriginalFilename(file.getOriginalFilename());
        record.setFileSize(file.getSize());
        record.setStatus(DocumentStatus.PROCESSING);
        try {
            record.setPdfContent(file.getBytes());
        } catch (IOException e) {
            log.warn("Could not store PDF bytes for re-extraction: {}", e.getMessage());
        }

        record = documentRecordRepository.save(record);
        log.info("Created document record: id={}, filename={}", record.getId(), file.getOriginalFilename());

        return record;
    }

    /**
     * Process the document asynchronously: extract text, chunk, embed, and store.
     * The knowledgeBaseId is passed explicitly to avoid lazy loading issues in the async thread.
     */
    @Async("documentProcessingExecutor")
    public void processDocumentAsync(UUID documentRecordId, UUID knowledgeBaseId,
                                     byte[] fileBytes, String originalFilename) {
        log.info("Starting async processing for document: id={}", documentRecordId);

        DocumentRecord record = documentRecordRepository.findById(documentRecordId)
                .orElseThrow(() -> new DocumentProcessingException("Document record not found: " + documentRecordId));

        try {
            Resource pdfResource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return originalFilename;
                }
            };

            // Extract text with progress tracking
            List<Document> pages = pdfProcessingService.extractTextBatched(pdfResource, new PdfProcessingService.ProgressCallback() {
                @Override
                public void onTotalPages(int totalPages) {
                    record.setTotalPages(totalPages);
                    documentRecordRepository.save(record);
                }

                @Override
                public void onBatchProcessed(int pagesProcessed) {
                    record.setPagesProcessed(pagesProcessed);
                    documentRecordRepository.save(record);
                }
            });

            // Detect language
            String language = pdfProcessingService.detectLanguage(pages);
            record.setLanguage(language);
            documentRecordRepository.save(record);

            // Chunk the documents (using the explicitly passed knowledgeBaseId)
            List<Document> chunks = chunkingService.chunkDocuments(
                    pages, knowledgeBaseId, record.getId(), language);

            // Store in vector store in batches
            storeChunksInBatches(chunks);

            // Mark as ready
            record.setStatus(DocumentStatus.READY);
            record.setChunkCount(chunks.size());
            record.setProcessedAt(Instant.now());
            documentRecordRepository.save(record);

            log.info("Document processing complete: id={}, chunks={}, pages={}, language={}",
                    documentRecordId, chunks.size(), record.getTotalPages(), language);

            // Trigger structured financial extraction (non-blocking — failure doesn't affect RAG pipeline)
            try {
                financialExtractionService.extractAndStore(pages, documentRecordId, knowledgeBaseId, fileBytes);
            } catch (Exception extractionEx) {
                log.warn("Financial extraction failed for document {}, RAG pipeline unaffected",
                        documentRecordId, extractionEx);
            }

            // Trigger knowledge compilation (non-blocking — failure doesn't affect RAG pipeline)
            try {
                knowledgeCompilationService.compile(chunks, documentRecordId, knowledgeBaseId);
            } catch (Exception compilationEx) {
                log.warn("Knowledge compilation failed for document {}, RAG pipeline unaffected",
                        documentRecordId, compilationEx);
            }

        } catch (Exception e) {
            log.error("Document processing failed: id={}", documentRecordId, e);
            try {
                record.setStatus(DocumentStatus.FAILED);
                record.setErrorMessage(truncateMessage(e.getMessage(), 1000));
                record.setProcessedAt(Instant.now());
                documentRecordRepository.save(record);
            } catch (Exception saveEx) {
                log.error("Failed to save FAILED status for document: id={}", documentRecordId, saveEx);
            }
        }
    }

    /**
     * Store chunks in the vector store in batches to manage memory.
     * Sanitizes text content to remove null bytes that PostgreSQL rejects.
     */
    private void storeChunksInBatches(List<Document> chunks) {
        for (int i = 0; i < chunks.size(); i += VECTOR_STORE_BATCH_SIZE) {
            int end = Math.min(i + VECTOR_STORE_BATCH_SIZE, chunks.size());
            List<Document> batch = chunks.subList(i, end).stream()
                    .map(this::sanitizeDocument)
                    .toList();
            vectorStore.add(batch);
            log.debug("Stored chunk batch {}-{} of {}", i + 1, end, chunks.size());
        }
    }

    /**
     * Remove null bytes (0x00) from document text — PostgreSQL UTF8 columns reject them.
     */
    private Document sanitizeDocument(Document doc) {
        String text = doc.getText();
        if (text != null && text.indexOf('\0') >= 0) {
            return doc.mutate().text(text.replace("\0", "")).build();
        }
        return doc;
    }

    /**
     * Delete all vector store entries for a specific document.
     */
    public void deleteDocumentVectors(UUID documentId) {
        try {
            vectorStore.delete("document_id == '" + documentId + "'");
            log.info("Deleted vector store entries for document: id={}", documentId);
        } catch (Exception e) {
            log.warn("Failed to delete vector store entries for document: id={}, error={}",
                    documentId, e.getMessage());
        }
    }

    private String truncateMessage(String message, int maxLength) {
        if (message == null) return null;
        return message.length() > maxLength ? message.substring(0, maxLength) : message;
    }

    private void validatePdf(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are accepted");
        }
        String contentType = file.getContentType();
        if (contentType != null
                && !contentType.equals("application/pdf")
                && !contentType.equals("application/x-pdf")
                && !contentType.equals("application/octet-stream")) {
            throw new IllegalArgumentException("Invalid content type. Expected a PDF file, got: " + contentType);
        }
    }
}
