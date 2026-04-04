package com.rkp.tenk.model.entity;

import com.rkp.tenk.model.enums.AccountingStandard;
import com.rkp.tenk.model.enums.ExtractionStatus;
import com.rkp.tenk.model.enums.PeriodType;
import com.rkp.tenk.model.enums.ValidationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "financial_data")
@Getter
@Setter
@NoArgsConstructor
public class FinancialData {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentRecord documentRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type")
    private PeriodType periodType;

    // --- Company metadata ---

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "fiscal_year")
    private String fiscalYear;

    @Column(name = "fiscal_year_end_date")
    private LocalDate fiscalYearEndDate;

    @Column(name = "currency_code")
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_standard")
    private AccountingStandard accountingStandard;

    @Column(name = "amounts_in_unit")
    private String amountsInUnit;

    // --- Balance sheet fields ---

    @Column(name = "total_assets", precision = 20, scale = 2)
    private BigDecimal totalAssets;

    @Column(name = "current_assets", precision = 20, scale = 2)
    private BigDecimal currentAssets;

    @Column(name = "total_liabilities", precision = 20, scale = 2)
    private BigDecimal totalLiabilities;

    @Column(name = "current_liabilities", precision = 20, scale = 2)
    private BigDecimal currentLiabilities;

    @Column(name = "total_equity", precision = 20, scale = 2)
    private BigDecimal totalEquity;

    @Column(name = "cash_and_equivalents", precision = 20, scale = 2)
    private BigDecimal cashAndEquivalents;

    @Column(name = "trade_receivables", precision = 20, scale = 2)
    private BigDecimal tradeReceivables;

    @Column(name = "trade_payables", precision = 20, scale = 2)
    private BigDecimal tradePayables;

    @Column(name = "accumulated_profit", precision = 20, scale = 2)
    private BigDecimal accumulatedProfit;

    // --- Income statement fields ---

    @Column(precision = 20, scale = 2)
    private BigDecimal revenue;

    @Column(name = "cost_of_sales", precision = 20, scale = 2)
    private BigDecimal costOfSales;

    @Column(name = "gross_profit", precision = 20, scale = 2)
    private BigDecimal grossProfit;

    @Column(name = "net_income", precision = 20, scale = 2)
    private BigDecimal netIncome;

    // --- Extraction metadata ---

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false)
    private ExtractionStatus extractionStatus = ExtractionStatus.PENDING;

    @Column(name = "extraction_model")
    private String extractionModel;

    @Column(name = "extraction_confidence")
    private Double extractionConfidence;

    @Column(name = "extraction_error", columnDefinition = "text")
    private String extractionError;

    @Column(name = "raw_llm_response", columnDefinition = "text")
    private String rawLlmResponse;

    // --- Validation ---

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status")
    private ValidationStatus validationStatus = ValidationStatus.NOT_RUN;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_details", columnDefinition = "jsonb")
    private String validationDetails;

    // --- Timestamps ---

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
