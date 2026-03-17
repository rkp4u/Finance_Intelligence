package com.rkp.tenk.service;

import com.rkp.tenk.exception.ResourceNotFoundException;
import com.rkp.tenk.model.dto.KnowledgeBaseRequest;
import com.rkp.tenk.model.dto.KnowledgeBaseResponse;
import com.rkp.tenk.model.entity.KnowledgeBase;
import com.rkp.tenk.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final VectorStore vectorStore;

    @Transactional
    public KnowledgeBaseResponse create(KnowledgeBaseRequest request) {
        if (repository.existsByName(request.name())) {
            throw new IllegalArgumentException("Knowledge base with name '" + request.name() + "' already exists");
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());

        kb = repository.save(kb);
        log.info("Created knowledge base: id={}, name={}", kb.getId(), kb.getName());
        return toResponse(kb);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listAll() {
        return repository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public KnowledgeBaseResponse update(UUID id, KnowledgeBaseRequest request) {
        KnowledgeBase kb = findOrThrow(id);

        if (!kb.getName().equals(request.name()) && repository.existsByName(request.name())) {
            throw new IllegalArgumentException("Knowledge base with name '" + request.name() + "' already exists");
        }

        kb.setName(request.name());
        kb.setDescription(request.description());
        kb = repository.save(kb);

        log.info("Updated knowledge base: id={}", kb.getId());
        return toResponse(kb);
    }

    @Transactional
    public void delete(UUID id) {
        KnowledgeBase kb = findOrThrow(id);

        // Delete all vector store entries for this knowledge base
        try {
            vectorStore.delete("knowledge_base_id == '" + id + "'");
            log.info("Deleted vector store entries for knowledge base: id={}", id);
        } catch (Exception e) {
            log.warn("Failed to delete vector store entries for knowledge base: id={}, error={}", id, e.getMessage());
        }

        repository.delete(kb);
        log.info("Deleted knowledge base: id={}, name={}", id, kb.getName());
    }

    public KnowledgeBase findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeBase", id));
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        return new KnowledgeBaseResponse(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getCreatedAt(),
                kb.getUpdatedAt(),
                kb.getDocuments().size()
        );
    }
}
