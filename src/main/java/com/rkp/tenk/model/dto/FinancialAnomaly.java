package com.rkp.tenk.model.dto;

public record FinancialAnomaly(
        String anomalyType,
        String description,
        String severity,
        String companyName,
        String fiscalYear
) {}
