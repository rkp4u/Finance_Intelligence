package com.rkp.tenk.model.dto;

import java.util.UUID;

public record RelationshipRequest(
        UUID targetCompanyId,
        String relationshipType,
        UUID evidenceDocumentId,
        String evidenceText,
        Double confidence
) {}
