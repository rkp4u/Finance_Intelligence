package com.rkp.tenk.repository;

import com.rkp.tenk.model.entity.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, UUID> {

    List<DocumentRecord> findByKnowledgeBaseId(UUID knowledgeBaseId);
}
