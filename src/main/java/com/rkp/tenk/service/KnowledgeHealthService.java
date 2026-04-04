package com.rkp.tenk.service;

import com.rkp.tenk.model.dto.FieldCoverage;
import com.rkp.tenk.model.dto.FinancialAnomaly;
import com.rkp.tenk.model.dto.KnowledgeHealthReport;
import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.enums.ExtractionStatus;
import com.rkp.tenk.model.enums.ValidationStatus;
import com.rkp.tenk.repository.CompanyRepository;
import com.rkp.tenk.repository.DocumentRecordRepository;
import com.rkp.tenk.repository.DocumentSummaryRepository;
import com.rkp.tenk.repository.FinancialDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure SQL health and quality metrics for a knowledge base.
 * Zero LLM cost — all computations done from the database.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeHealthService {

    private final DocumentRecordRepository documentRecordRepository;
    private final FinancialDataRepository financialDataRepository;
    private final DocumentSummaryRepository summaryRepository;
    private final CompanyRepository companyRepository;

    public KnowledgeHealthReport generateReport(UUID kbId) {
        int totalDocs = documentRecordRepository.findByKnowledgeBaseId(kbId).size();
        List<FinancialData> allFd = financialDataRepository.findByKnowledgeBaseId(kbId);
        List<FinancialData> completed = allFd.stream()
                .filter(fd -> fd.getExtractionStatus() == ExtractionStatus.COMPLETED)
                .toList();

        double extractionSuccessRate = totalDocs == 0 ? 0 :
                (double) completed.size() / totalDocs * 100;

        long passed = completed.stream()
                .filter(fd -> fd.getValidationStatus() == ValidationStatus.PASSED
                        || fd.getValidationStatus() == ValidationStatus.WARNINGS)
                .count();
        double validationPassRate = completed.isEmpty() ? 0 :
                (double) passed / completed.size() * 100;

        double avgConfidence = completed.stream()
                .filter(fd -> fd.getExtractionConfidence() != null)
                .mapToDouble(FinancialData::getExtractionConfidence)
                .average().orElse(0) * 100;

        double avgFieldCompleteness = completed.stream()
                .mapToDouble(this::fieldCompleteness)
                .average().orElse(0) * 100;

        int companyCount = (int) completed.stream()
                .filter(fd -> fd.getCompany() != null)
                .map(fd -> fd.getCompany().getId())
                .distinct().count();

        int summaryCount = summaryRepository.countByKnowledgeBaseId(kbId);

        List<FieldCoverage> fieldCoverage = buildFieldCoverage(completed);

        return new KnowledgeHealthReport(
                totalDocs, completed.size(),
                round(extractionSuccessRate), round(validationPassRate),
                round(avgConfidence), round(avgFieldCompleteness),
                companyCount, summaryCount, fieldCoverage);
    }

    public List<FinancialAnomaly> detectAnomalies(UUID kbId) {
        List<FinancialData> completed = financialDataRepository
                .findByKnowledgeBaseIdAndExtractionStatus(kbId, ExtractionStatus.COMPLETED);

        List<FinancialAnomaly> anomalies = new ArrayList<>();

        for (FinancialData fd : completed) {
            String company = fd.getCompanyName();
            String fy = fd.getFiscalYear();

            if (fd.getTotalAssets() != null && fd.getTotalAssets().signum() < 0) {
                anomalies.add(new FinancialAnomaly("NEGATIVE_ASSETS",
                        "Total assets is negative: " + fd.getTotalAssets(),
                        "ERROR", company, fy));
            }

            if (fd.getTotalEquity() != null && fd.getTotalEquity().signum() < 0) {
                anomalies.add(new FinancialAnomaly("NEGATIVE_EQUITY",
                        "Total equity is negative: " + fd.getTotalEquity(),
                        "WARNING", company, fy));
            }

            if (fd.getCurrentAssets() != null && fd.getCurrentLiabilities() != null
                    && fd.getCurrentLiabilities().signum() > 0) {
                double currentRatio = fd.getCurrentAssets().divide(fd.getCurrentLiabilities(),
                        4, java.math.RoundingMode.HALF_UP).doubleValue();
                if (currentRatio < 0.1 || currentRatio > 50) {
                    anomalies.add(new FinancialAnomaly("UNUSUAL_CURRENT_RATIO",
                            "Current ratio out of range: " + String.format("%.2f", currentRatio),
                            "WARNING", company, fy));
                }
            }

            if (fd.getRevenue() != null && fd.getNetIncome() != null
                    && fd.getRevenue().signum() != 0) {
                double margin = fd.getNetIncome().divide(fd.getRevenue().abs(),
                        4, java.math.RoundingMode.HALF_UP).abs().doubleValue();
                if (margin > 1.0) {
                    anomalies.add(new FinancialAnomaly("EXTREME_MARGIN",
                            "Net income/revenue ratio exceeds 100%: " + String.format("%.1f%%", margin * 100),
                            "WARNING", company, fy));
                }
            }

            if (fd.getRevenue() != null && fd.getCostOfSales() == null) {
                anomalies.add(new FinancialAnomaly("REVENUE_WITHOUT_COGS",
                        "Revenue extracted but cost of sales is null",
                        "WARNING", company, fy));
            }
        }

        // Cross-document contradiction: same company + FY, values differ > 5%
        detectContradictions(completed, anomalies);

        return anomalies;
    }

    public List<FieldCoverage> getFieldCoverage(UUID kbId) {
        List<FinancialData> completed = financialDataRepository
                .findByKnowledgeBaseIdAndExtractionStatus(kbId, ExtractionStatus.COMPLETED);
        return buildFieldCoverage(completed);
    }

    private void detectContradictions(List<FinancialData> data, List<FinancialAnomaly> anomalies) {
        // Group by (canonicalized company name, fiscal year)
        Map<String, List<FinancialData>> byKey = new HashMap<>();
        for (FinancialData fd : data) {
            if (fd.getCompanyName() == null || fd.getFiscalYear() == null) continue;
            String key = fd.getCompanyName().toLowerCase().trim() + "|" + fd.getFiscalYear();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(fd);
        }
        for (var entry : byKey.entrySet()) {
            List<FinancialData> group = entry.getValue();
            if (group.size() < 2) continue;
            FinancialData a = group.get(0), b = group.get(1);
            if (contradicts(a.getRevenue(), b.getRevenue(), 0.05)) {
                anomalies.add(new FinancialAnomaly("CROSS_DOC_CONTRADICTION",
                        "Revenue differs >5% across two documents for same company+FY",
                        "ERROR", a.getCompanyName(), a.getFiscalYear()));
            }
            if (contradicts(a.getTotalAssets(), b.getTotalAssets(), 0.05)) {
                anomalies.add(new FinancialAnomaly("CROSS_DOC_CONTRADICTION",
                        "Total assets differs >5% across two documents for same company+FY",
                        "ERROR", a.getCompanyName(), a.getFiscalYear()));
            }
        }
    }

    private boolean contradicts(BigDecimal a, BigDecimal b, double tolerance) {
        if (a == null || b == null || a.signum() == 0) return false;
        double diff = a.subtract(b).abs().divide(a.abs(), 4, java.math.RoundingMode.HALF_UP).doubleValue();
        return diff > tolerance;
    }

    private List<FieldCoverage> buildFieldCoverage(List<FinancialData> data) {
        int total = data.size();
        return List.of(
                coverage("totalAssets", total, data, FinancialData::getTotalAssets),
                coverage("currentAssets", total, data, FinancialData::getCurrentAssets),
                coverage("totalLiabilities", total, data, FinancialData::getTotalLiabilities),
                coverage("currentLiabilities", total, data, FinancialData::getCurrentLiabilities),
                coverage("totalEquity", total, data, FinancialData::getTotalEquity),
                coverage("cashAndEquivalents", total, data, FinancialData::getCashAndEquivalents),
                coverage("tradeReceivables", total, data, FinancialData::getTradeReceivables),
                coverage("tradePayables", total, data, FinancialData::getTradePayables),
                coverage("accumulatedProfit", total, data, FinancialData::getAccumulatedProfit),
                coverage("revenue", total, data, FinancialData::getRevenue),
                coverage("costOfSales", total, data, FinancialData::getCostOfSales),
                coverage("grossProfit", total, data, FinancialData::getGrossProfit),
                coverage("netIncome", total, data, FinancialData::getNetIncome)
        );
    }

    private <T> FieldCoverage coverage(String field, int total, List<FinancialData> data,
                                        Function<FinancialData, T> getter) {
        int nonNull = (int) data.stream().filter(fd -> getter.apply(fd) != null).count();
        double pct = total == 0 ? 0 : round((double) nonNull / total * 100);
        return new FieldCoverage(field, nonNull, total, pct);
    }

    private double fieldCompleteness(FinancialData fd) {
        int filled = 0;
        if (fd.getTotalAssets() != null) filled++;
        if (fd.getCurrentAssets() != null) filled++;
        if (fd.getTotalLiabilities() != null) filled++;
        if (fd.getCurrentLiabilities() != null) filled++;
        if (fd.getTotalEquity() != null) filled++;
        if (fd.getCashAndEquivalents() != null) filled++;
        if (fd.getTradeReceivables() != null) filled++;
        if (fd.getTradePayables() != null) filled++;
        if (fd.getAccumulatedProfit() != null) filled++;
        if (fd.getRevenue() != null) filled++;
        if (fd.getCostOfSales() != null) filled++;
        if (fd.getGrossProfit() != null) filled++;
        if (fd.getNetIncome() != null) filled++;
        return (double) filled / 13;
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
