package com.rkp.tenk.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

public record QueryResponse(
        String answer,
        List<SourceChunk> sources,
        QueryMetadata metadata
) {

    public record SourceChunk(
            String content,
            String sectionName,
            Integer pageNumber,
            Double similarityScore,
            UUID documentId
    ) {}

    public record QueryMetadata(
            int chunksRetrieved,
            long retrievalTimeMs,
            long generationTimeMs,
            String modelUsed,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            AgenticMetadata agentic
    ) {}

    public record AgenticMetadata(
            long decompositionTimeMs,
            long evaluationTimeMs,
            int evaluationRounds,
            List<String> subQueriesUsed
    ) {}
}
