package com.rkp.tenk.model.dto;

public record FieldCoverage(
        String fieldName,
        int nonNullCount,
        int totalCount,
        double coveragePercent
) {}
