package com.rkp.tenk.controller;

import com.rkp.tenk.model.dto.KnowledgeBaseRequest;
import com.rkp.tenk.model.dto.KnowledgeBaseResponse;
import com.rkp.tenk.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
@Tag(name = "Knowledge Bases", description = "CRUD operations for knowledge bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    @Operation(summary = "Create a new knowledge base")
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeBaseService.create(request));
    }

    @GetMapping
    @Operation(summary = "List all knowledge bases")
    public ResponseEntity<List<KnowledgeBaseResponse>> listAll() {
        return ResponseEntity.ok(knowledgeBaseService.listAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a knowledge base by ID")
    public ResponseEntity<KnowledgeBaseResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(knowledgeBaseService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a knowledge base")
    public ResponseEntity<KnowledgeBaseResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeBaseRequest request) {
        return ResponseEntity.ok(knowledgeBaseService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a knowledge base and all its documents")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        knowledgeBaseService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
