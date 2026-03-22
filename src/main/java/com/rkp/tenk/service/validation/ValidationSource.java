package com.rkp.tenk.service.validation;

import com.rkp.tenk.model.dto.ValidationCheckResult;
import com.rkp.tenk.model.entity.FinancialData;

import java.util.List;

/**
 * Pluggable validation source interface.
 * Each implementation provides a different way to validate extracted financial data:
 * - AccountingIdentitySource: A=L+E, gross profit, bounds (V1 — done)
 * - EdgarXbrlSource: cross-check against SEC EDGAR XBRL data (V2 — US)
 * - NseXbrlSource: cross-check against NSE/BSE XBRL data (V3 — India)
 * - CommercialApiSource: cross-check via CRIF/LSEG (V3 — Singapore)
 * - ManualUploadSource: user-uploaded XBRL file (V3 — any country)
 */
public interface ValidationSource {

    /**
     * A short identifier for this validation source (e.g., "SEC_EDGAR", "NSE_XBRL").
     */
    String sourceId();

    /**
     * Whether this source can validate the given financial data.
     * E.g., EDGAR only works for US GAAP companies with a CIK.
     */
    boolean supports(FinancialData data);

    /**
     * Run validation checks against this source.
     * Returns a list of check results (passed/failed with details).
     */
    List<ValidationCheckResult> validate(FinancialData data);
}
