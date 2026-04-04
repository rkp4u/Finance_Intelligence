package com.rkp.tenk.controller;

import com.rkp.tenk.exception.ResourceNotFoundException;
import com.rkp.tenk.model.dto.CompanyResponse;
import com.rkp.tenk.model.entity.Company;
import com.rkp.tenk.repository.CompanyRepository;
import com.rkp.tenk.service.CompanyResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Companies", description = "Company master endpoints")
public class CompanyController {

    private final CompanyRepository companyRepository;
    private final CompanyResolutionService companyResolutionService;

    @GetMapping("/companies")
    @Operation(summary = "List all known companies")
    public ResponseEntity<List<CompanyResponse>> listAll() {
        List<CompanyResponse> companies = companyRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(companies);
    }

    @GetMapping("/companies/{id}")
    @Operation(summary = "Get a company by ID")
    public ResponseEntity<CompanyResponse> getById(@PathVariable UUID id) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        return ResponseEntity.ok(toResponse(company));
    }

    @PostMapping("/admin/backfill-companies")
    @Operation(summary = "Backfill company_id for existing financial data rows that have no company FK")
    public ResponseEntity<Map<String, Object>> backfill() {
        int count = companyResolutionService.backfillExistingData();
        log.info("Company backfill complete: {} rows linked", count);
        return ResponseEntity.ok(Map.of("linkedRows", count));
    }

    private CompanyResponse toResponse(Company c) {
        return new CompanyResponse(
                c.getId(), c.getName(), c.getCanonicalName(),
                c.getTicker(), c.getCik(), c.getJurisdiction(), c.getSector());
    }
}
