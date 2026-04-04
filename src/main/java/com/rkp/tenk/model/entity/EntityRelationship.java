package com.rkp.tenk.model.entity;

import com.rkp.tenk.model.enums.RelationshipType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entity_relationship")
@Getter
@Setter
@NoArgsConstructor
public class EntityRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_company_id", nullable = false)
    private Company sourceCompany;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_company_id", nullable = false)
    private Company targetCompany;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false)
    private RelationshipType relationshipType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_document_id")
    private DocumentRecord evidenceDocument;

    @Column(name = "evidence_text", columnDefinition = "text")
    private String evidenceText;

    @Column
    private Double confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
