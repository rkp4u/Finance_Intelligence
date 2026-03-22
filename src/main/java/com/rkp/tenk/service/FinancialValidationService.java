package com.rkp.tenk.service;

import com.rkp.tenk.model.dto.ValidationCheckResult;
import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.enums.ValidationStatus;
import com.rkp.tenk.service.validation.ValidationSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates extracted financial data using accounting identity checks
 * and pluggable external validation sources (EDGAR XBRL, etc.).
 */
@Service
@Slf4j
public class FinancialValidationService {

    @Value("${financial-extraction.validation-tolerance-percent:1.0}")
    private double tolerancePercent;

    private final List<ValidationSource> externalSources;

    public FinancialValidationService(List<ValidationSource> externalSources) {
        this.externalSources = externalSources;
        log.info("Registered {} external validation sources: {}",
                externalSources.size(),
                externalSources.stream().map(ValidationSource::sourceId).toList());
    }

    /**
     * Run all validation checks on the extracted financial data.
     * Returns the list of check results and the aggregate status.
     */
    public ValidationResult validate(FinancialData data) {
        return validate(data, tolerancePercent);
    }

    public ValidationResult validate(FinancialData data, double tolerancePct) {
        List<ValidationCheckResult> checks = new ArrayList<>();
        double tolerance = tolerancePct / 100.0;

        // Check 1: Accounting equation — Assets = Liabilities + Equity
        if (allNonNull(data.getTotalAssets(), data.getTotalLiabilities(), data.getTotalEquity())) {
            BigDecimal expected = data.getTotalLiabilities().add(data.getTotalEquity());
            boolean passed = isWithinTolerance(data.getTotalAssets(), expected, tolerance);
            checks.add(new ValidationCheckResult(
                    "Accounting Equation",
                    passed,
                    format(expected),
                    format(data.getTotalAssets()),
                    passed ? "Total Assets = Total Liabilities + Total Equity"
                            : "Total Assets (" + format(data.getTotalAssets()) + ") != Liabilities + Equity (" + format(expected) + ")",
                    "CRITICAL"
            ));
        }

        // Check 2: Current Assets <= Total Assets
        if (allNonNull(data.getCurrentAssets(), data.getTotalAssets())) {
            boolean passed = data.getCurrentAssets().compareTo(data.getTotalAssets()) <= 0;
            checks.add(new ValidationCheckResult(
                    "Current Assets <= Total Assets",
                    passed,
                    "<= " + format(data.getTotalAssets()),
                    format(data.getCurrentAssets()),
                    passed ? "Current Assets within Total Assets"
                            : "Current Assets (" + format(data.getCurrentAssets()) + ") exceeds Total Assets (" + format(data.getTotalAssets()) + ")",
                    "ERROR"
            ));
        }

        // Check 3: Current Liabilities <= Total Liabilities
        if (allNonNull(data.getCurrentLiabilities(), data.getTotalLiabilities())) {
            boolean passed = data.getCurrentLiabilities().compareTo(data.getTotalLiabilities()) <= 0;
            checks.add(new ValidationCheckResult(
                    "Current Liabilities <= Total Liabilities",
                    passed,
                    "<= " + format(data.getTotalLiabilities()),
                    format(data.getCurrentLiabilities()),
                    passed ? "Current Liabilities within Total Liabilities"
                            : "Current Liabilities exceeds Total Liabilities",
                    "ERROR"
            ));
        }

        // Check 4: Gross Profit = Revenue - Cost of Sales
        if (allNonNull(data.getGrossProfit(), data.getRevenue(), data.getCostOfSales())) {
            BigDecimal expected = data.getRevenue().subtract(data.getCostOfSales());
            boolean passed = isWithinTolerance(data.getGrossProfit(), expected, tolerance);
            checks.add(new ValidationCheckResult(
                    "Gross Profit Identity",
                    passed,
                    format(expected),
                    format(data.getGrossProfit()),
                    passed ? "Gross Profit = Revenue - Cost of Sales"
                            : "Gross Profit mismatch",
                    "ERROR"
            ));
        }

        // Check 5: Cash <= Current Assets
        if (allNonNull(data.getCashAndEquivalents(), data.getCurrentAssets())) {
            boolean passed = data.getCashAndEquivalents().compareTo(data.getCurrentAssets()) <= 0;
            checks.add(new ValidationCheckResult(
                    "Cash <= Current Assets",
                    passed,
                    "<= " + format(data.getCurrentAssets()),
                    format(data.getCashAndEquivalents()),
                    passed ? "Cash within Current Assets"
                            : "Cash exceeds Current Assets",
                    "WARNING"
            ));
        }

        // Check 6: Trade Receivables <= Current Assets
        if (allNonNull(data.getTradeReceivables(), data.getCurrentAssets())) {
            boolean passed = data.getTradeReceivables().compareTo(data.getCurrentAssets()) <= 0;
            checks.add(new ValidationCheckResult(
                    "Receivables <= Current Assets",
                    passed,
                    "<= " + format(data.getCurrentAssets()),
                    format(data.getTradeReceivables()),
                    passed ? "Trade Receivables within Current Assets"
                            : "Trade Receivables exceeds Current Assets",
                    "WARNING"
            ));
        }

        // Check 7: Trade Payables <= Current Liabilities
        if (allNonNull(data.getTradePayables(), data.getCurrentLiabilities())) {
            boolean passed = data.getTradePayables().compareTo(data.getCurrentLiabilities()) <= 0;
            checks.add(new ValidationCheckResult(
                    "Payables <= Current Liabilities",
                    passed,
                    "<= " + format(data.getCurrentLiabilities()),
                    format(data.getTradePayables()),
                    passed ? "Trade Payables within Current Liabilities"
                            : "Trade Payables exceeds Current Liabilities",
                    "WARNING"
            ));
        }

        // Check 8: Non-negative Total Assets
        if (data.getTotalAssets() != null) {
            boolean passed = data.getTotalAssets().signum() >= 0;
            checks.add(new ValidationCheckResult(
                    "Non-negative Total Assets",
                    passed, ">= 0", format(data.getTotalAssets()),
                    passed ? "Total Assets is non-negative" : "Total Assets is negative",
                    "WARNING"
            ));
        }

        // Check 9: Non-negative Revenue
        if (data.getRevenue() != null) {
            boolean passed = data.getRevenue().signum() >= 0;
            checks.add(new ValidationCheckResult(
                    "Non-negative Revenue",
                    passed, ">= 0", format(data.getRevenue()),
                    passed ? "Revenue is non-negative" : "Revenue is negative",
                    "WARNING"
            ));
        }

        // Run external validation sources (EDGAR XBRL, etc.)
        for (ValidationSource source : externalSources) {
            if (source.supports(data)) {
                try {
                    log.info("Running external validation: {}", source.sourceId());
                    List<ValidationCheckResult> externalChecks = source.validate(data);
                    checks.addAll(externalChecks);
                    log.info("External validation {} returned {} checks", source.sourceId(), externalChecks.size());
                } catch (Exception e) {
                    log.warn("External validation source {} failed: {}", source.sourceId(), e.getMessage());
                }
            }
        }

        // Determine aggregate status
        ValidationStatus status = determineStatus(checks);

        log.info("Validation complete: {} checks, status={}", checks.size(), status);
        return new ValidationResult(status, checks);
    }

    private ValidationStatus determineStatus(List<ValidationCheckResult> checks) {
        if (checks.isEmpty()) return ValidationStatus.NOT_RUN;

        boolean hasCriticalOrErrorFailure = checks.stream()
                .anyMatch(c -> !c.passed() && ("CRITICAL".equals(c.severity()) || "ERROR".equals(c.severity())));
        boolean hasWarningFailure = checks.stream()
                .anyMatch(c -> !c.passed() && "WARNING".equals(c.severity()));

        if (hasCriticalOrErrorFailure) return ValidationStatus.FAILED;
        if (hasWarningFailure) return ValidationStatus.WARNINGS;
        return ValidationStatus.PASSED;
    }

    private boolean isWithinTolerance(BigDecimal actual, BigDecimal expected, double tolerance) {
        if (expected.signum() == 0) {
            return actual.abs().doubleValue() < 1.0; // allow rounding for zero
        }
        BigDecimal diff = actual.subtract(expected).abs();
        BigDecimal ratio = diff.divide(expected.abs(), MathContext.DECIMAL64);
        return ratio.doubleValue() <= tolerance;
    }

    private boolean allNonNull(BigDecimal... values) {
        for (BigDecimal v : values) {
            if (v == null) return false;
        }
        return true;
    }

    private String format(BigDecimal value) {
        return value != null ? value.toPlainString() : "null";
    }

    public record ValidationResult(ValidationStatus status, List<ValidationCheckResult> checks) {}
}
