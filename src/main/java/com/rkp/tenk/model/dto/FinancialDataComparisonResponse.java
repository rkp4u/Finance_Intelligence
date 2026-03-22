package com.rkp.tenk.model.dto;

import java.util.List;

public record FinancialDataComparisonResponse(
        List<FinancialDataResponse> documents,
        int documentCount
) {}
