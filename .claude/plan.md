# Financial Data Extraction Product — Implementation Plan

## Status Legend
- [x] Done
- [~] In Progress
- [ ] Pending

---

## Phase 1: Core Extraction Pipeline (DONE)

- [x] Structured extraction pipeline — PDF → page detection → LLM extraction → JSON → DB
- [x] Validation layer V1 — Accounting identity checks (A=L+E, gross profit, bounds)
- [x] Multi-jurisdiction support — US GAAP, IFRS, Ind-AS
- [x] Multi-currency/unit support — USD/SGD/INR, MILLIONS/CRORES/LAKHS
- [x] Revenue terminology expansion — telecoms, services, turnover
- [x] OpenAI integration — validated 93%+ accuracy with gpt-4o-mini
- [x] Bug fixes — @Async+@Transactional stuck processing, context overflow, null byte sanitization

## Phase 2: MVP — Accuracy, Re-extract & EDGAR Validation (CURRENT)

### 2A: Store PDF bytes + Re-extract endpoint
- [ ] Add `pdf_content` BYTEA column to `document_record` table (or file system storage)
- [ ] Save original PDF bytes during ingestion
- [ ] Implement working `/re-extract` endpoint that reads stored PDF, re-detects pages, re-runs LLM extraction

### 2B: EDGAR XBRL Cross-Validation (US)
- [ ] `ValidationSource` interface — pluggable architecture for external validation sources
- [ ] `EdgarXbrlSource` — fetch company facts from SEC EDGAR API (`data.sec.gov/api/xbrl/companyfacts/CIK.json`)
- [ ] CIK resolver — map company name or ticker to SEC CIK number
- [ ] XBRL tag → our schema field mapping (e.g., `us-gaap:AccountsReceivableNetCurrent` → `tradeReceivables`)
- [ ] Cross-validation engine — compare LLM-extracted values against XBRL, produce discrepancy report
- [ ] Auto-correct option — when XBRL value exists and differs from LLM extraction, flag and optionally override
- [ ] Composite confidence score — blend LLM confidence + XBRL match percentage

### 2C: Trade receivables disambiguation
- [ ] Refine prompt to better distinguish sub-line vs parent line items
- [ ] If EDGAR XBRL available, use XBRL value as ground truth (may auto-fix this)

### 2D: Non-MVP (deferred)
- Telecom costOfSales — null is correct behavior, document it
- Profile-aware vector dimensions — internal tooling, not product

## Phase 3: India & Singapore Validation Sources

### 3A: India — NSE/BSE XBRL
- [ ] `NseXbrlSource` — download XBRL filings from NSE NEAPS or BSE
- [ ] Ind-AS XBRL taxonomy → our schema field mapping
- [ ] Fallback: commercial API integration (FinEdge / RapidAPI) for structured data

### 3B: Singapore — ACRA / Commercial
- [ ] `CommercialApiSource` — integrate with CRIF BizInsights API or similar
- [ ] ACRA XBRL taxonomy → our schema field mapping
- [ ] Fallback: `ManualUploadSource` — user uploads XBRL file for validation

### 3C: Manual Upload Source (any country)
- [ ] API endpoint to upload XBRL/iXBRL file for a document
- [ ] Parse uploaded XBRL and cross-validate against extracted data

## Phase 4: Hybrid Architecture (Option C)

- [ ] Query routing — detect financial data queries and route to structured DB lookup instead of vector RAG
- [ ] Unified response format — merge structured data answers with RAG narrative answers
- [ ] Compare API — cross-document comparison endpoint with normalization

## Phase 5: Cross-Taxonomy Normalization

- [ ] Common financial schema — canonical field set spanning US GAAP, IFRS, Ind-AS
- [ ] US GAAP → common schema mapping rules
- [ ] IFRS → common schema mapping rules
- [ ] Ind-AS → common schema mapping rules
- [ ] Unit normalization — convert CRORES/LAKHS/MILLIONS to common base
- [ ] Currency-aware comparison — exchange rates, cross-currency comparison

## Phase 6: Temporal & Cross-Company Intelligence

- [ ] Multi-year extraction — extract from multiple annual reports for same company
- [ ] Year-over-year change detection — flag significant changes in key metrics
- [ ] Cross-company comparison dashboard — normalized view across jurisdictions
- [ ] Anomaly detection — flag outliers vs industry peers

## Phase 7: Product & API

- [ ] Public extraction API — upload a filing, get structured JSON back
- [ ] API authentication & rate limiting
- [ ] Webhook notifications — notify when extraction completes
- [ ] Multi-tenant support — isolated knowledge bases per customer
- [ ] Deployment packaging — Docker compose for self-hosted/VPC deployment
- [ ] API documentation & SDK

---

## Validation Architecture

```
ValidationSource (interface)
  │
  ├── AccountingIdentitySource   (V1 — DONE — A=L+E, gross profit, bounds)
  ├── EdgarXbrlSource            (V2 — Phase 2 — US, free SEC API)
  ├── NseXbrlSource              (V3 — Phase 3 — India, NSE/BSE filings)
  ├── CommercialApiSource        (V3 — Phase 3 — Singapore, CRIF/LSEG)
  ├── ManualUploadSource         (V3 — Phase 3 — any country, user uploads XBRL)
  └── PriorYearConsistencySource (V4 — Phase 6 — cross-year checks)
```

## Test Matrix

| Company | Country | Standard | Currency | Unit | Extraction | Validation V1 | Validation V2 (XBRL) |
|---------|---------|----------|----------|------|------------|---------------|----------------------|
| Micron Technology | US | US GAAP | USD | MILLIONS | 93% (OpenAI) | PASSED | Pending (EDGAR) |
| Singtel | Singapore | IFRS | SGD | MILLIONS | COMPLETED | PASSED | Pending (CRIF) |
| TCS | India | Ind-AS | INR | CRORES | COMPLETED | PASSED | Pending (NSE) |

## Architecture Decisions

- **Hybrid approach (Option C):** Structured extraction for financial data + vector RAG for narrative Q&A
- **Model strategy:** LM Studio (local) for development, OpenAI API for production accuracy
- **Product shape:** API-first (Shape A), expandable to enterprise VPC deployment (Shape C)
- **Validation layers:** (1) Accounting identity checks ✅, (2) XBRL cross-check (Phase 2-3), (3) Prior year consistency (Phase 6)
- **Validation architecture:** Pluggable `ValidationSource` interface — each country/source is a separate implementation

## External Data Sources

| Source | Country | Cost | API Type | Used For |
|--------|---------|------|----------|----------|
| SEC EDGAR | US | Free | REST (no key) | XBRL company facts |
| NSE NEAPS | India | Free | File download | XBRL filings |
| BSE | India | Free | File download | XBRL annual reports |
| FinEdge API | India | $50-200/mo | REST API | Structured financials |
| CRIF BizInsights | Singapore | Commercial | REST API | ACRA XBRL data |
| LSEG/Refinitiv | Global | Commercial | SOAP/REST | Fundamentals data |
