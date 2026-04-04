package com.rkp.tenk.repository;

import com.rkp.tenk.model.entity.DocumentSummary;
import com.rkp.tenk.model.enums.SummaryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentSummaryRepository extends JpaRepository<DocumentSummary, UUID> {

    List<DocumentSummary> findByDocumentRecordId(UUID documentId);

    List<DocumentSummary> findByKnowledgeBaseId(UUID knowledgeBaseId);

    Optional<DocumentSummary> findByDocumentRecordIdAndSummaryType(UUID documentId, SummaryType summaryType);

    int countByKnowledgeBaseId(UUID knowledgeBaseId);

    void deleteByDocumentRecordId(UUID documentId);
}
