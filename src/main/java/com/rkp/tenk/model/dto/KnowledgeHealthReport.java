package com.rkp.tenk.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeHealthReport(
        int totalDocuments,
        int documentsWithFinancialData,
        double extractionSuccessRate,
        double validationPassRate,
        double avgConfidence,
        double avgFieldCompleteness,
        int companyCount,
        int summaryCount,
        List<FieldCoverage> fieldCoverage
) {}
