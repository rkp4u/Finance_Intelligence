package com.rkp.tenk.model.entity;

import com.rkp.tenk.model.enums.SummaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_summary",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_document_summary_doc_type",
                columnNames = {"document_id", "summary_type"}))
@Getter
@Setter
@NoArgsConstructor
public class DocumentSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentRecord documentRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_type", nullable = false)
    private SummaryType summaryType;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_entities", columnDefinition = "jsonb")
    private String keyEntities;

    @Column(name = "section_source")
    private String sectionSource;

    @Column(name = "word_count")
    private Integer wordCount;

    @Column(name = "model_used")
    private String modelUsed;

    @Column(name = "compilation_time_ms")
    private Long compilationTimeMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
