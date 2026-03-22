package com.rkp.tenk.repository;

import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.enums.ExtractionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialDataRepository extends JpaRepository<FinancialData, UUID> {

    Optional<FinancialData> findByDocumentRecordId(UUID documentId);

    List<FinancialData> findByKnowledgeBaseId(UUID knowledgeBaseId);

    List<FinancialData> findByKnowledgeBaseIdAndExtractionStatus(
            UUID knowledgeBaseId, ExtractionStatus status);

    boolean existsByDocumentRecordId(UUID documentId);
}
