package com.rkp.tenk.model.dto;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeBaseResponse(
        UUID id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt,
        int documentCount
) {}
