# FinLens — Financial Intelligence

An AI-powered financial data extraction and analysis platform that turns any financial filing (10-K, annual reports) into structured, validated, comparable data. Supports US GAAP, IFRS, and Ind-AS filings with automated XBRL cross-validation.

## Architecture

```
                          ┌─────────────────────┐
                          │    User Query        │
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                          │   Query Classifier   │  keyword-based, <1ms
                          └──────────┬──────────┘
                                     │
                    ┌────────────────┼────────────────┐
                    │                                 │
         ┌──────────▼──────────┐          ┌──────────▼──────────┐
         │  Structured Lookup  │          │    Vector RAG        │
         │  (financial data)   │          │    (narrative Q&A)   │
         │                     │          │                      │
         │  DB SELECT → format │          │  Embed → pgvector    │
         │  <100ms, 100% acc   │          │  → LLM generation    │
         │  Zero LLM cost      │          │  10-30s, ~95% acc    │
         └─────────────────────┘          └──────────────────────┘
              also handles TREND
              (multi-year table +
               YoY % change)
```

### Ingestion Pipeline

```
PDF Upload
    │
    ├─► PDF Extraction (PDFBox / Docling*) → Section-aware chunking → Embeddings → pgvector
    │                                                                    (Vector RAG path)
    │
    └─► Financial Statement Detection → LLM Structured Extraction → Validation
              │                                   │                       │
              ▼                                   ▼                       ▼
       Docling enrichment*              financial_data table       Accounting checks
       (BS/IS/Notes separately,         (14 fields, typed)        + XBRL cross-check
        falls back to PDFBox)           Company resolution
              │
              ▼
       Knowledge Compilation
       (LLM section summaries →
        document_summary table
        + back into vector store)

* Docling optional, disabled by default
```

## Features

### Structured Financial Extraction
- **14 standard fields** extracted from balance sheet + income statement
- **Multi-jurisdiction** — US GAAP, IFRS, Ind-AS with automatic standard detection
- **Multi-currency/unit** — USD/SGD/INR, Millions/Crores/Lakhs/Billions
- **Smart page detection** — keyword scoring identifies financial statement pages
- **Notes-aware** — prefers narrow sub-line items (trade receivables) over aggregated totals
- **Docling integration** — optional per-section IBM TableFormer enrichment for complex/scanned PDFs

### Multi-Layer Validation
- **Accounting identity checks** — A=L+E, gross profit identity, bounds checks
- **SEC EDGAR XBRL cross-validation** — automated for US GAAP companies (13 fields, free API)
- **Pluggable architecture** — `ValidationSource` interface for adding India (NSE), Singapore (ACRA) sources

### Hybrid Query Routing
- **Financial data queries** → instant DB lookup, zero LLM cost, 100% accuracy
- **Trend queries** → multi-year markdown table with YoY % change (e.g. "show me Micron revenue over 3 years")
- **Narrative queries** → vector RAG with semantic search + LLM generation
- **Automatic classification** — keyword-based routing with FINANCIAL_DATA, TREND, NARRATIVE types

### Knowledge Architecture
- **Company master** — canonical name deduplication (strips Inc./Ltd./Corp. suffixes), links all filings to a single company entity
- **Knowledge compilation** — LLM synthesizes section summaries (Business, Risks, MD&A, Financials) post-ingestion; summaries stored in DB and back into vector store for improved RAG retrieval
- **Health dashboard** — pure SQL quality metrics: extraction success rate, validation pass rate, avg confidence, field completeness per KB; no LLM cost
- **Anomaly detection** — flags negative assets/equity, extreme margins, unusual current ratios, cross-document contradictions (same company+FY, values differ >5%)
- **CSV export** — download all extracted financial data for a KB as a spreadsheet
- **Cross-company relationships** — typed links (SUBSIDIARY, CUSTOMER, SUPPLIER, COMPETITOR, PARTNER) with evidence attribution

### RAG Pipeline
- **Agentic mode** — query decomposition, parallel retrieval, evaluation + retry loop
- **Section-aware chunking** — respects SEC filing structure (Item 1, Item 7, Item 8, etc.)
- **Source attribution** — every answer cites section names and page numbers
- **Summary-boosted retrieval** — compiled summaries returned alongside raw chunks

### Product UI (FinLens)
- **Dashboard** — financial data cards, validation checks, key ratios, confidence scores, compiled summaries, CSV export button
- **Compare** — side-by-side comparison table across companies
- **Chat** — RAG query interface with markdown table rendering and source cards
- **Health** — KB-level metric cards, per-field coverage bars, anomaly list with severity badges

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 3.4, Spring AI 1.0 |
| LLM (local) | Qwen 3 14B via LM Studio |
| LLM (cloud) | GPT-4o-mini via OpenAI API |
| Embeddings | nomic-embed-text v1.5 (768 dims) |
| Vector Store | PostgreSQL 16 + pgvector (HNSW) |
| PDF Parsing | Apache PDFBox (primary) |
| Table Extraction | Docling Serve — IBM TableFormer ML (optional, disabled by default) |
| Migrations | Liquibase |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Java | 17+ |

## Quick Start

### Prerequisites

- Java 17+
- Docker Desktop (for PostgreSQL)
- [LM Studio](https://lmstudio.ai) (for local LLM)

### 1. Start PostgreSQL

```bash
docker-compose up -d postgres
```

### 2. Start LM Studio

```bash
lms server start
lms load qwen/qwen3-14b --context-length 32768
lms load text-embedding-nomic-embed-text-v1.5
```

### 3. Run the Application

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=lmstudio
```

Open **http://localhost:8080**

### 4. Use It

1. Create a knowledge base in the sidebar
2. Upload a financial filing PDF (drag-and-drop or browse)
3. Wait for processing — extraction + validation + knowledge compilation runs automatically
4. Click a document to see the **Dashboard** with extracted financial data and compiled summaries
5. Use **Compare** tab for cross-company analysis
6. Use **Chat** tab for narrative questions and trend queries ("show Micron revenue over 3 years")
7. Use **Health** tab to monitor data quality across the KB
8. Click **↓ Export CSV** to download all extracted data as a spreadsheet

## LLM Profiles

| Profile | Provider | Chat Model | Embedding | Dims |
|---------|----------|-----------|-----------|------|
| `lmstudio` | LM Studio | qwen3-14b | nomic-embed-text-v1.5 | 768 |
| `dev` | Ollama | qwen3:14b | nomic-embed-text | 768 |
| `openai` | OpenAI | gpt-4o-mini | text-embedding-3-small | 768 |

```bash
# LM Studio (default)
./mvnw spring-boot:run -Dspring-boot.run.profiles=lmstudio

# OpenAI
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

## API Endpoints

### Knowledge Bases

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/knowledge-bases` | Create |
| `GET` | `/api/v1/knowledge-bases` | List all |
| `DELETE` | `/api/v1/knowledge-bases/{id}` | Delete (cascades) |

### Documents

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/knowledge-bases/{kbId}/documents` | Upload PDF |
| `GET` | `/api/v1/knowledge-bases/{kbId}/documents` | List |
| `DELETE` | `/api/v1/knowledge-bases/{kbId}/documents/{docId}` | Delete |

### Query (Hybrid Routing)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/knowledge-bases/{kbId}/query` | Ask a question (auto-routes: FINANCIAL_DATA / TREND / NARRATIVE) |

**Request:**
```json
{
  "question": "What is Micron's revenue?",
  "topK": 5,
  "similarityThreshold": 0.3
}
```

**Response (structured path):**
```json
{
  "answer": "## Micron Technology, Inc. — FY2024 (USD, MILLIONS)\n\n**Income Statement:**\n- Revenue: $25,111\n\n*Source: US_GAAP filing. Confidence: 100%. XBRL-validated.*",
  "sources": [],
  "metadata": {
    "chunksRetrieved": 0,
    "retrievalTimeMs": 20,
    "generationTimeMs": 0,
    "queryType": "STRUCTURED"
  }
}
```

### Financial Data

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/{kbId}/documents/{docId}/financial-data` | Get extracted data |
| `GET` | `/{kbId}/financial-data` | List all in KB |
| `GET` | `/{kbId}/financial-data/compare?documentIds=id1,id2` | Compare across documents |
| `GET` | `/{kbId}/financial-data/export.csv` | Download all as CSV |
| `POST` | `/{kbId}/documents/{docId}/financial-data/re-extract` | Force re-extraction |
| `POST` | `/{kbId}/documents/{docId}/financial-data/validate` | Re-run validation |

### Knowledge Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/{kbId}/health` | Overall health report (extraction rate, validation rate, field completeness) |
| `GET` | `/{kbId}/health/anomalies` | Detected data anomalies (negative values, extreme ratios, cross-doc contradictions) |
| `GET` | `/{kbId}/health/field-coverage` | Per-field extraction coverage across all documents |

### Knowledge Compilation (Summaries)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/{kbId}/summaries` | List all compiled summaries in a KB |
| `GET` | `/{kbId}/documents/{docId}/summaries` | Compiled summaries for a specific document |

### Companies

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/companies` | List all resolved companies with canonical names |
| `GET` | `/api/v1/companies/{id}/financial-data` | All financial data for a company |
| `GET` | `/api/v1/companies/{id}/relationships` | Company relationships |
| `POST` | `/api/v1/companies/{id}/relationships` | Create a relationship |
| `DELETE` | `/api/v1/companies/{id}/relationships/{relId}` | Delete a relationship |

Full API docs at `/swagger-ui.html`.

## Accuracy

Tested across 3 companies, 3 accounting standards:

| Company | Standard | Currency | Fields (14 total) | XBRL Checks | Validation |
|---------|----------|----------|-------------------|-------------|------------|
| Micron Technology | US GAAP | USD / Millions | 14/14 (100%) | 12/13 (tradeReceivables off by 3.7%) | WARNINGS |
| Singtel | IFRS | SGD / Millions | 12/14 (costOfSales, grossProfit null — not in filing format) | N/A | PASSED |
| TCS | Ind-AS | INR / Crores | 13/14 (grossProfit null) | N/A | PASSED |

## Project Structure

```
src/main/java/com/rkp/tenk/
  config/
    AppProperties           # @ConfigurationProperties(prefix = "app") — model name
    CompilationConfig       # @EnableConfigurationProperties for compilation
    CompilationProperties   # @ConfigurationProperties(prefix = "knowledge-compilation")
    DoclingConfig           # @EnableConfigurationProperties for Docling
    DoclingProperties       # @ConfigurationProperties(prefix = "docling")
  controller/
    KnowledgeBaseController
    DocumentController
    FinancialDataController  # includes CSV export endpoint
    QueryController
    CompanyController        # company master + financial data by company
    HealthController         # /health, /health/anomalies, /health/field-coverage
    SummaryController        # /summaries, /documents/{id}/summaries
    RelationshipController   # cross-company relationship CRUD
  service/
    QueryClassifier          # Hybrid routing: FINANCIAL_DATA, TREND, NARRATIVE
    StructuredQueryService   # DB lookup + multi-year trend formatting (zero LLM)
    AgenticRagOrchestrator   # RAG with query decomposition + evaluation
    FinancialExtractionService    # LLM-based structured extraction
    FinancialStatementDetector    # Page detection via keyword scoring
    FinancialValidationService    # Accounting identity checks + pluggable sources
    DoclingTableService           # Optional per-section table enrichment (disabled by default)
    CompanyResolutionService      # Find-or-create company with race condition handling
    KnowledgeCompilationService   # LLM section summaries → DB + vector store
    KnowledgeHealthService        # Pure SQL health metrics + anomaly detection
    FinancialDataExportService    # CSV generation with formula injection protection
    validation/
      ValidationSource       # Pluggable validation interface
      EdgarXbrlSource        # SEC EDGAR XBRL cross-validation
  model/
    entity/
      KnowledgeBase, DocumentRecord, FinancialData
      Company                # Canonical name dedup entity
      DocumentSummary        # LLM-compiled section summaries
      EntityRelationship     # Typed cross-company links
    dto/
      FinancialStatementPages    # Detected pages with per-section effective*Text() fallback
      KnowledgeHealthReport, FieldCoverage, FinancialAnomaly
      DocumentSummaryResponse, CompanyResponse, RelationshipResponse
    enums/
      ExtractionStatus, ValidationStatus, AccountingStandard
      SummaryType            # BUSINESS_OVERVIEW, RISK_PROFILE, MANAGEMENT_DISCUSSION, etc.
      RelationshipType       # SUBSIDIARY, CUSTOMER, SUPPLIER, COMPETITOR, PARTNER
      PeriodType             # FY, Q1-Q4, H1, H2
  repository/              # JPA repositories

src/main/resources/
  static/                  # FinLens Web UI (Dashboard, Compare, Chat, Health)
  db/changelog/            # Liquibase migrations (8 changesets)
    001-initial-schema.yaml
    002-add-validation-fields.yaml
    003-add-pdf-content-to-document-record.yaml
    004-add-pdf-content-to-document-record.yaml
    005-create-company-table.yaml
    006-add-company-id-and-period-type.yaml
    007-create-document-summary.yaml
    008-create-entity-relationship.yaml
  application.yml          # Multi-profile config (lmstudio, dev, openai)
```

## Docker

```bash
# PostgreSQL only (default)
docker compose up -d postgres

# PostgreSQL + Ollama (for dev profile)
docker compose --profile docker-ollama up

# PostgreSQL + Docling Serve (optional table extraction enhancement)
docker compose --profile docling up -d docling-serve
```

## Docling Table Extraction (Optional)

Docling integrates IBM's [TableFormer ML model](https://github.com/DS4SD/docling) for structured table extraction. It is **disabled by default** — PDFBox handles all extraction unless you explicitly opt in.

**When to use Docling:**
- Poor-quality or scanned PDFs where PDFBox loses column alignment
- Complex multi-column table layouts
- Non-standard filing formats (not SEC 10-K machine PDFs)

**When NOT to use Docling:**
- Standard SEC 10-K filings (machine-generated PDFs — PDFBox reads them at ~95% fidelity)
- Evaluation on Micron, Singtel, TCS showed +1 field across 42 slots — not worth the overhead

**To enable:**
```bash
# 1. Start Docling Serve
docker compose --profile docling up -d docling-serve

# 2. Enable in config (or pass as JVM arg)
./mvnw spring-boot:run -Dspring-boot.run.profiles=lmstudio \
  -Dspring-boot.run.jvmArguments="-Ddocling.enabled=true"
```

The integration calls Docling **per section** (balance sheet, income statement, notes separately) to preserve the 3-section prompt structure. Each section falls back to PDFBox independently if Docling fails.

**Config:**
```yaml
docling:
  enabled: false              # opt-in
  base-url: http://localhost:5001
  timeout-seconds: 60
  max-pages-per-request: 10  # per section

knowledge-compilation:
  enabled: true               # auto-compile summaries on ingestion
  max-chunks-per-section: 20
  max-summary-chars: 18000
```

## Testing

```bash
./mvnw test
```
