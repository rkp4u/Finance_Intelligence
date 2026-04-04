package com.rkp.tenk.service;

import com.rkp.tenk.model.entity.Company;
import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.repository.CompanyRepository;
import com.rkp.tenk.repository.FinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Resolves or creates Company records from extracted company names.
 * Uses canonical name matching to deduplicate companies across filings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyResolutionService {

    private final CompanyRepository companyRepository;
    private final FinancialDataRepository financialDataRepository;

    /**
     * Find or create a Company for the given extracted name.
     * Thread-safe: uses findOrCreate with canonical name as dedup key.
     *
     * @param name raw company name from LLM extraction
     * @return resolved or newly created Company
     */
    @Transactional
    public Company resolveOrCreate(String name) {
        if (name == null || name.isBlank()) return null;

        String canonical = Company.canonicalize(name);
        if (canonical == null || canonical.isBlank()) return null;

        return companyRepository.findByCanonicalName(canonical)
                .orElseGet(() -> {
                    Company c = new Company();
                    c.setName(name.trim());
                    c.setCanonicalName(canonical);
                    Company saved = companyRepository.save(c);
                    log.info("Created new Company: name='{}', canonical='{}'", saved.getName(), canonical);
                    return saved;
                });
    }

    /**
     * Backfill company_id for all existing FinancialData rows that have a company name
     * but no company FK. Safe to call repeatedly — skips already-linked rows.
     */
    @Transactional
    public int backfillExistingData() {
        List<FinancialData> unlinked = financialDataRepository.findByCompanyIsNull();
        int count = 0;
        for (FinancialData fd : unlinked) {
            if (fd.getCompanyName() == null || fd.getCompanyName().isBlank()) continue;
            Company company = resolveOrCreate(fd.getCompanyName());
            if (company != null) {
                fd.setCompany(company);
                financialDataRepository.save(fd);
                count++;
            }
        }
        log.info("Backfilled company_id for {} financial data rows", count);
        return count;
    }
}
