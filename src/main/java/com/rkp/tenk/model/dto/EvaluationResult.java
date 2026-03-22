package com.rkp.tenk.model.dto;

import java.util.List;

public record EvaluationResult(
        Verdict verdict,
        List<String> refinedQueries
) {
    public enum Verdict { SUFFICIENT, INSUFFICIENT }

    public static EvaluationResult sufficient() {
        return new EvaluationResult(Verdict.SUFFICIENT, List.of());
    }

    public static EvaluationResult insufficient(List<String> refinedQueries) {
        return new EvaluationResult(Verdict.INSUFFICIENT, refinedQueries);
    }
}
