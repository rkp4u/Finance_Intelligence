package com.rkp.tenk.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyResponse(
        UUID id,
        String name,
        String canonicalName,
        String ticker,
        String cik,
        String jurisdiction,
        String sector
) {}
