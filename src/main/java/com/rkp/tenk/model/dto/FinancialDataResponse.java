package com.rkp.tenk.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinancialDataResponse(
        UUID id,
        UUID documentId,
        UUID companyId,
        String periodType,
        // Company metadata
        String companyName,
        String fiscalYear,
        LocalDate fiscalYearEndDate,
        String currencyCode,
        String accountingStandard,
        String amountsInUnit,
        // Balance sheet
        BigDecimal totalAssets,
        BigDecimal currentAssets,
        BigDecimal totalLiabilities,
        BigDecimal currentLiabilities,
        BigDecimal totalEquity,
        BigDecimal cashAndEquivalents,
        BigDecimal tradeReceivables,
        BigDecimal tradePayables,
        BigDecimal accumulatedProfit,
        // Income statement
        BigDecimal revenue,
        BigDecimal costOfSales,
        BigDecimal grossProfit,
        BigDecimal netIncome,
        // Extraction metadata
        String extractionStatus,
        String extractionModel,
        Double extractionConfidence,
        String extractionError,
        // Validation
        String validationStatus,
        List<ValidationCheckResult> validationChecks,
        // Timestamps
        Instant extractedAt,
        Instant validatedAt
) {}
