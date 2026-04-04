package com.rkp.tenk.controller;

import com.rkp.tenk.exception.ResourceNotFoundException;
import com.rkp.tenk.model.dto.RelationshipRequest;
import com.rkp.tenk.model.dto.RelationshipResponse;
import com.rkp.tenk.model.entity.Company;
import com.rkp.tenk.model.entity.DocumentRecord;
import com.rkp.tenk.model.entity.EntityRelationship;
import com.rkp.tenk.model.enums.RelationshipType;
import com.rkp.tenk.repository.CompanyRepository;
import com.rkp.tenk.repository.DocumentRecordRepository;
import com.rkp.tenk.repository.EntityRelationshipRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/relationships")
@RequiredArgsConstructor
@Tag(name = "Relationships", description = "Cross-company entity relationship management")
public class RelationshipController {

    private final EntityRelationshipRepository relationshipRepository;
    private final CompanyRepository companyRepository;
    private final DocumentRecordRepository documentRecordRepository;

    @GetMapping
    @Operation(summary = "List all relationships for a company (source or target)")
    public ResponseEntity<List<RelationshipResponse>> list(@PathVariable UUID companyId) {
        companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));
        return ResponseEntity.ok(
                relationshipRepository.findByCompanyId(companyId).stream()
                        .map(this::toResponse).toList());
    }

    @PostMapping
    @Operation(summary = "Create a relationship between two companies")
    public ResponseEntity<RelationshipResponse> create(
            @PathVariable UUID companyId,
            @RequestBody RelationshipRequest request) {

        Company source = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));
        Company target = companyRepository.findById(request.targetCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("Company", request.targetCompanyId()));

        RelationshipType type;
        try {
            type = RelationshipType.valueOf(request.relationshipType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        EntityRelationship rel = new EntityRelationship();
        rel.setSourceCompany(source);
        rel.setTargetCompany(target);
        rel.setRelationshipType(type);
        rel.setEvidenceText(request.evidenceText());
        rel.setConfidence(request.confidence());

        if (request.evidenceDocumentId() != null) {
            DocumentRecord doc = documentRecordRepository.findById(request.evidenceDocumentId()).orElse(null);
            rel.setEvidenceDocument(doc);
        }

        EntityRelationship saved = relationshipRepository.save(rel);
        return ResponseEntity.ok(toResponse(saved));
    }

    @DeleteMapping("/{relationshipId}")
    @Operation(summary = "Delete a relationship")
    public ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID relationshipId) {

        EntityRelationship rel = relationshipRepository.findById(relationshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship", relationshipId));
        relationshipRepository.delete(rel);
        return ResponseEntity.noContent().build();
    }

    private RelationshipResponse toResponse(EntityRelationship rel) {
        return new RelationshipResponse(
                rel.getId(),
                rel.getSourceCompany().getId(),
                rel.getSourceCompany().getName(),
                rel.getTargetCompany().getId(),
                rel.getTargetCompany().getName(),
                rel.getRelationshipType().name(),
                rel.getEvidenceDocument() != null ? rel.getEvidenceDocument().getId() : null,
                rel.getEvidenceText(),
                rel.getConfidence(),
                rel.getCreatedAt()
        );
    }
}
