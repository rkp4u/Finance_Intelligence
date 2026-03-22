package com.rkp.tenk.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkp.tenk.model.dto.ValidationCheckResult;
import com.rkp.tenk.model.entity.FinancialData;
import com.rkp.tenk.model.enums.AccountingStandard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/**
 * Validates extracted financial data against SEC EDGAR XBRL company facts.
 * Uses the free SEC EDGAR API (no API key required, just User-Agent header).
 *
 * API: https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json
 * CIK lookup: https://efts.sec.gov/LATEST/search-index?q={company}&forms=10-K
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EdgarXbrlSource implements ValidationSource {

    private static final String USER_AGENT = "TenkRAG/1.0 admin@tenkrag.dev";
    private static final String COMPANY_FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK%s.json";
    private static final String COMPANY_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * XBRL tag → our field name mapping.
     * Multiple XBRL tags can map to the same field (fallback order).
     */
    private static final List<XbrlFieldMapping> FIELD_MAPPINGS = List.of(
            new XbrlFieldMapping("totalAssets", "Assets"),
            new XbrlFieldMapping("currentAssets", "AssetsCurrent"),
            new XbrlFieldMapping("totalLiabilities", "Liabilities"),
            new XbrlFieldMapping("currentLiabilities", "LiabilitiesCurrent"),
            new XbrlFieldMapping("totalEquity", "StockholdersEquity",
                    "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"),
            new XbrlFieldMapping("cashAndEquivalents", "CashAndCashEquivalentsAtCarryingValue",
                    "CashCashEquivalentsAndShortTermInvestments"),
            new XbrlFieldMapping("tradeReceivables", "AccountsReceivableNetCurrent",
                    "ReceivablesNetCurrent"),
            new XbrlFieldMapping("tradePayables", "AccountsPayableTradeCurrent",
                    "AccountsPayableCurrent"),
            new XbrlFieldMapping("accumulatedProfit", "RetainedEarningsAccumulatedDeficit"),
            new XbrlFieldMapping("revenue", "RevenueFromContractWithCustomerExcludingAssessedTax",
                    "Revenues", "SalesRevenueNet"),
            new XbrlFieldMapping("costOfSales", "CostOfGoodsAndServicesSold",
                    "CostOfRevenue", "CostOfGoodsSold"),
            new XbrlFieldMapping("grossProfit", "GrossProfit"),
            new XbrlFieldMapping("netIncome", "NetIncomeLoss")
    );

    @Override
    public String sourceId() {
        return "SEC_EDGAR";
    }

    @Override
    public boolean supports(FinancialData data) {
        return data.getAccountingStandard() == AccountingStandard.US_GAAP
                && "USD".equalsIgnoreCase(data.getCurrencyCode());
    }

    @Override
    public List<ValidationCheckResult> validate(FinancialData data) {
        List<ValidationCheckResult> results = new ArrayList<>();

        try {
            // Step 1: Resolve CIK from company name
            String cik = resolveCik(data.getCompanyName());
            if (cik == null) {
                results.add(new ValidationCheckResult(
                        "EDGAR CIK Resolution", false, "found", "not found",
                        "Could not resolve SEC CIK for: " + data.getCompanyName(),
                        "WARNING"));
                return results;
            }

            log.info("Resolved CIK {} for company: {}", cik, data.getCompanyName());

            // Step 2: Fetch company facts
            Map<String, BigDecimal> xbrlValues = fetchCompanyFacts(cik, data.getFiscalYearEndDate());
            if (xbrlValues.isEmpty()) {
                results.add(new ValidationCheckResult(
                        "EDGAR XBRL Fetch", false, "data", "empty",
                        "No XBRL data found for CIK " + cik + " at fiscal year end",
                        "WARNING"));
                return results;
            }

            // Step 3: Cross-validate each field
            BigDecimal unitMultiplier = getUnitMultiplier(data.getAmountsInUnit());

            for (XbrlFieldMapping mapping : FIELD_MAPPINGS) {
                BigDecimal xbrlValue = xbrlValues.get(mapping.ourField());
                BigDecimal extractedValue = getFieldValue(data, mapping.ourField());

                if (xbrlValue == null || extractedValue == null) {
                    continue; // Skip if either side has no value
                }

                // Convert extracted value to raw units for comparison
                BigDecimal extractedRaw = extractedValue.multiply(unitMultiplier);

                // Compare with 0.5% tolerance (for rounding)
                boolean match = isWithinTolerance(extractedRaw, xbrlValue, 0.005);

                String suggestedAction = match ? "PASS" : "REVIEW";
                String message = match
                        ? String.format("Extracted %s matches XBRL (%s)", formatValue(extractedRaw), mapping.primaryXbrlTag())
                        : String.format("Extracted %s but XBRL reports %s (tag: %s). Possible %s",
                                formatValue(extractedRaw), formatValue(xbrlValue), mapping.primaryXbrlTag(),
                                detectMismatchReason(extractedRaw, xbrlValue));

                results.add(new ValidationCheckResult(
                        "XBRL Cross-Check: " + mapping.ourField(),
                        match,
                        formatValue(xbrlValue),
                        formatValue(extractedRaw),
                        message,
                        match ? "INFO" : "WARNING"));
            }

        } catch (Exception e) {
            log.error("EDGAR XBRL validation failed for {}", data.getCompanyName(), e);
            results.add(new ValidationCheckResult(
                    "EDGAR XBRL Validation", false, "success", "error",
                    "EDGAR validation error: " + e.getMessage(),
                    "WARNING"));
        }

        return results;
    }

    /**
     * Resolve company name to SEC CIK number using the company tickers file.
     */
    String resolveCik(String companyName) {
        try {
            log.debug("Resolving CIK for: '{}'", companyName);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COMPANY_TICKERS_URL))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("SEC tickers API returned status {} ({} bytes)", response.statusCode(), response.body().length());
            if (response.statusCode() != 200) return null;

            JsonNode tickers = objectMapper.readTree(response.body());
            String normalizedName = normalize(companyName);

            // Try exact match first, then prefix match, then contains
            String bestMatch = null;
            int bestCik = 0;
            int bestScore = 0;

            for (Iterator<JsonNode> it = tickers.elements(); it.hasNext(); ) {
                JsonNode entry = it.next();
                String title = normalize(entry.get("title").asText());
                int score = 0;
                if (title.equals(normalizedName)) {
                    score = 100;
                } else if (normalizedName.startsWith(title) || title.startsWith(normalizedName)) {
                    score = 80;
                } else if (normalizedName.contains(title) || title.contains(normalizedName)) {
                    score = 60;
                }
                // Also check: strip common suffixes like "inc", "corp", "ltd", "limited"
                if (score == 0) {
                    String strippedName = stripSuffix(normalizedName);
                    String strippedTitle = stripSuffix(title);
                    if (strippedName.equals(strippedTitle)) score = 90;
                    else if (strippedName.startsWith(strippedTitle) || strippedTitle.startsWith(strippedName)) score = 70;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestCik = entry.get("cik_str").asInt();
                    bestMatch = entry.get("title").asText();
                }
            }

            if (bestScore >= 60) {
                log.debug("CIK match: '{}' → '{}' (score={})", companyName, bestMatch, bestScore);
                return String.format("%010d", bestCik);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to resolve CIK for '{}': {} - {}", companyName, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Fetch XBRL company facts from EDGAR and extract values for the given fiscal year end date.
     */
    Map<String, BigDecimal> fetchCompanyFacts(String cik, LocalDate fiscalYearEnd) {
        Map<String, BigDecimal> values = new HashMap<>();

        try {
            String url = String.format(COMPANY_FACTS_URL, cik);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("EDGAR API returned {} for CIK {}", response.statusCode(), cik);
                return values;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode usGaapFacts = root.path("facts").path("us-gaap");

            String targetEnd = fiscalYearEnd != null ? fiscalYearEnd.toString() : null;

            for (XbrlFieldMapping mapping : FIELD_MAPPINGS) {
                BigDecimal value = findXbrlValue(usGaapFacts, mapping.xbrlTags(), targetEnd);
                if (value != null) {
                    values.put(mapping.ourField(), value);
                }
            }

            log.info("Fetched {} XBRL values from EDGAR for CIK {}", values.size(), cik);

        } catch (Exception e) {
            log.error("Failed to fetch EDGAR company facts for CIK {}", cik, e);
        }

        return values;
    }

    /**
     * Find the XBRL value for a given tag at the target fiscal year end date.
     * Tries multiple tags in fallback order.
     */
    private BigDecimal findXbrlValue(JsonNode usGaapFacts, List<String> tags, String targetEnd) {
        for (String tag : tags) {
            JsonNode tagNode = usGaapFacts.path(tag);
            if (tagNode.isMissingNode()) continue;

            JsonNode usdEntries = tagNode.path("units").path("USD");
            if (!usdEntries.isArray()) continue;

            // Find 10-K FY entry matching target end date
            for (JsonNode entry : usdEntries) {
                String form = entry.path("form").asText();
                String fp = entry.path("fp").asText();
                String end = entry.path("end").asText();

                if ("10-K".equals(form) && "FY".equals(fp)) {
                    if (targetEnd == null || targetEnd.equals(end)) {
                        return BigDecimal.valueOf(entry.path("val").asLong());
                    }
                }
            }

            // If no exact date match, try most recent 10-K FY
            if (targetEnd != null) {
                BigDecimal latest = null;
                for (JsonNode entry : usdEntries) {
                    if ("10-K".equals(entry.path("form").asText()) && "FY".equals(entry.path("fp").asText())) {
                        latest = BigDecimal.valueOf(entry.path("val").asLong());
                    }
                }
                if (latest != null) return latest;
            }
        }
        return null;
    }

    private boolean isWithinTolerance(BigDecimal a, BigDecimal b, double tolerancePercent) {
        if (a.compareTo(BigDecimal.ZERO) == 0 && b.compareTo(BigDecimal.ZERO) == 0) return true;
        if (b.compareTo(BigDecimal.ZERO) == 0) return false;
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal tolerance = b.abs().multiply(BigDecimal.valueOf(tolerancePercent));
        return diff.compareTo(tolerance) <= 0;
    }

    private String detectMismatchReason(BigDecimal extracted, BigDecimal xbrl) {
        if (extracted.compareTo(BigDecimal.ZERO) == 0 || xbrl.compareTo(BigDecimal.ZERO) == 0) {
            return "missing value";
        }
        BigDecimal ratio = extracted.divide(xbrl, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.valueOf(1000)) == 0) return "scaling error (thousands vs units)";
        if (ratio.compareTo(BigDecimal.valueOf(0.001)) == 0) return "scaling error (units vs thousands)";
        if (ratio.compareTo(BigDecimal.valueOf(1.1)) > 0 && ratio.compareTo(BigDecimal.valueOf(2.0)) < 0) {
            return "parent vs sub-line mismatch";
        }
        return "value difference";
    }

    private BigDecimal getUnitMultiplier(String amountsInUnit) {
        if (amountsInUnit == null) return BigDecimal.ONE;
        return switch (amountsInUnit.toUpperCase()) {
            case "BILLIONS" -> BigDecimal.valueOf(1_000_000_000L);
            case "MILLIONS" -> BigDecimal.valueOf(1_000_000L);
            case "THOUSANDS" -> BigDecimal.valueOf(1_000L);
            case "CRORES" -> BigDecimal.valueOf(10_000_000L);
            case "LAKHS" -> BigDecimal.valueOf(100_000L);
            default -> BigDecimal.ONE;
        };
    }

    private BigDecimal getFieldValue(FinancialData data, String fieldName) {
        return switch (fieldName) {
            case "totalAssets" -> data.getTotalAssets();
            case "currentAssets" -> data.getCurrentAssets();
            case "totalLiabilities" -> data.getTotalLiabilities();
            case "currentLiabilities" -> data.getCurrentLiabilities();
            case "totalEquity" -> data.getTotalEquity();
            case "cashAndEquivalents" -> data.getCashAndEquivalents();
            case "tradeReceivables" -> data.getTradeReceivables();
            case "tradePayables" -> data.getTradePayables();
            case "accumulatedProfit" -> data.getAccumulatedProfit();
            case "revenue" -> data.getRevenue();
            case "costOfSales" -> data.getCostOfSales();
            case "grossProfit" -> data.getGrossProfit();
            case "netIncome" -> data.getNetIncome();
            default -> null;
        };
    }

    private static String normalize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    private static String stripSuffix(String normalized) {
        return normalized
                .replaceAll("\\b(inc|corp|corporation|ltd|limited|plc|co|company|group|holdings)\\b", "")
                .replaceAll("\\s+", " ").trim();
    }

    private String formatValue(BigDecimal value) {
        if (value == null) return "null";
        return String.format("%,.0f", value);
    }

    /**
     * Mapping between our field name and one or more XBRL tags (tried in order).
     */
    record XbrlFieldMapping(String ourField, List<String> xbrlTags) {
        XbrlFieldMapping(String ourField, String... tags) {
            this(ourField, List.of(tags));
        }

        String primaryXbrlTag() {
            return xbrlTags.isEmpty() ? "unknown" : xbrlTags.get(0);
        }
    }
}
