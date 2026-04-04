package com.rkp.tenk.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationshipResponse(
        UUID id,
        UUID sourceCompanyId,
        String sourceCompanyName,
        UUID targetCompanyId,
        String targetCompanyName,
        String relationshipType,
        UUID evidenceDocumentId,
        String evidenceText,
        Double confidence,
        Instant createdAt
) {}
