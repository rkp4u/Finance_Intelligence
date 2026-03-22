package com.rkp.tenk.model.entity;

import com.rkp.tenk.model.enums.DocumentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_record")
@Getter
@Setter
@NoArgsConstructor
public class DocumentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @Column(nullable = false)
    private String filename;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "file_size")
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "total_pages")
    private Integer totalPages;

    @Column(name = "pages_processed")
    private Integer pagesProcessed;

    private String language;

    @Column(name = "error_message")
    private String errorMessage;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "pdf_content")
    private byte[] pdfContent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
