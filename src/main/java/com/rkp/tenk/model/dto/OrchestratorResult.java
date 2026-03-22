package com.rkp.tenk.model.dto;

import org.springframework.ai.document.Document;

import java.util.List;

public record OrchestratorResult(
        String answer,
        List<Document> sourceDocuments,
        long decompositionTimeMs,
        long retrievalTimeMs,
        long evaluationTimeMs,
        long generationTimeMs,
        int evaluationRounds,
        List<String> subQueriesUsed,
        boolean agenticMode
) {}
