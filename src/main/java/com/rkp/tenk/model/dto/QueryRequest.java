package com.rkp.tenk.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QueryRequest(
        @NotBlank(message = "Question is required")
        @Size(max = 2000, message = "Question must be at most 2000 characters")
        String question,

        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 20, message = "topK must be at most 20")
        Integer topK,

        Double similarityThreshold
) {
    public QueryRequest {
        if (topK == null) topK = 5;
        if (similarityThreshold == null) similarityThreshold = 0.3;
    }
}
