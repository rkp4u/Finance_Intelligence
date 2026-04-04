package com.rkp.tenk.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentSummaryResponse(
        UUID id,
        UUID documentId,
        String summaryType,
        String content,
        String sectionSource,
        Integer wordCount,
        String modelUsed,
        Long compilationTimeMs,
        Instant createdAt
) {}
