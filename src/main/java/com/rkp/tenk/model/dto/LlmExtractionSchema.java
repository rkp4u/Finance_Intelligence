package com.rkp.tenk.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * JSON schema that the LLM is asked to produce during financial data extraction.
 * Uses relaxed deserialization to handle missing/extra fields gracefully.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmExtractionSchema(
        @JsonProperty("companyName") String companyName,
        @JsonProperty("fiscalYear") String fiscalYear,
        @JsonProperty("fiscalYearEndDate") String fiscalYearEndDate,
        @JsonProperty("currencyCode") String currencyCode,
        @JsonProperty("accountingStandard") String accountingStandard,
        @JsonProperty("amountsInUnit") String amountsInUnit,
        @JsonProperty("totalAssets") BigDecimal totalAssets,
        @JsonProperty("currentAssets") BigDecimal currentAssets,
        @JsonProperty("totalLiabilities") BigDecimal totalLiabilities,
        @JsonProperty("currentLiabilities") BigDecimal currentLiabilities,
        @JsonProperty("totalEquity") BigDecimal totalEquity,
        @JsonProperty("cashAndEquivalents") BigDecimal cashAndEquivalents,
        @JsonProperty("tradeReceivables") BigDecimal tradeReceivables,
        @JsonProperty("tradePayables") BigDecimal tradePayables,
        @JsonProperty("accumulatedProfit") BigDecimal accumulatedProfit,
        @JsonProperty("revenue") BigDecimal revenue,
        @JsonProperty("costOfSales") BigDecimal costOfSales,
        @JsonProperty("grossProfit") BigDecimal grossProfit,
        @JsonProperty("netIncome") BigDecimal netIncome,
        @JsonProperty("confidenceScore") Double confidenceScore
) {}
