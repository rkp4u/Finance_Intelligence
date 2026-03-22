package com.rkp.tenk.model.dto;

import com.rkp.tenk.model.enums.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentUploadResponse(
        UUID id,
        String filename,
        Long fileSize,
        DocumentStatus status,
        Integer totalPages,
        Integer pagesProcessed,
        Integer chunkCount,
        String language,
        String errorMessage,
        Instant createdAt,
        Instant processedAt
) {}
