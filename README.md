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
```

### Ingestion Pipeline

```
PDF Upload
    │
    ├─► PDF Extraction (PDFBox) → Section-aware chunking → Embeddings → pgvector
    │                                                          (Vector RAG path)
    │
    └─► Financial Statement Detection → LLM Structured Extraction → Validation
                                              │                          │
                                              ▼                          ▼
                                        financial_data table    Accounting checks
                                        (14 fields, typed)     + XBRL cross-check
                                                               (Structured path)
```

## Features

### Structured Financial Extraction
- **14 standard fields** extracted from balance sheet + income statement
- **Multi-jurisdiction** — US GAAP, IFRS, Ind-AS with automatic standard detection
- **Multi-currency/unit** — USD/SGD/INR, Millions/Crores/Lakhs/Billions
- **Smart page detection** — keyword scoring identifies financial statement pages
- **Notes-aware** — prefers narrow sub-line items (trade receivables) over aggregated totals

### Multi-Layer Validation
- **Accounting identity checks** — A=L+E, gross profit identity, bounds checks
- **SEC EDGAR XBRL cross-validation** — automated for US GAAP companies (13 fields, free API)
- **Pluggable architecture** — `ValidationSource` interface for adding India (NSE), Singapore (ACRA) sources

### Hybrid Query Routing
- **Financial data queries** → instant DB lookup, zero LLM cost, 100% accuracy
- **Narrative queries** → vector RAG with semantic search + LLM generation
- **Automatic classification** — keyword-based routing, no LLM overhead

### RAG Pipeline
- **Agentic mode** — query decomposition, parallel retrieval, evaluation + retry loop
- **Section-aware chunking** — respects SEC filing structure (Item 1, Item 7, Item 8, etc.)
- **Source attribution** — every answer cites section names and page numbers

### Product UI (FinLens)
- **Dashboard** — financial data cards, validation checks, key ratios, confidence scores
- **Compare** — side-by-side comparison table across companies
- **Chat** — RAG query interface with markdown rendering and source cards

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 3.4, Spring AI 1.0 |
| LLM (local) | Qwen 3 14B via LM Studio |
| LLM (cloud) | GPT-4o-mini via OpenAI API |
| Embeddings | nomic-embed-text v1.5 (768 dims) |
| Vector Store | PostgreSQL 16 + pgvector (HNSW) |
| PDF Parsing | Apache PDFBox |
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
3. Wait for processing — extraction + validation runs automatically
4. Click a document to see the **Dashboard** with extracted financial data
5. Use **Compare** tab for cross-company analysis
6. Use **Chat** tab for narrative questions

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
| `POST` | `/api/v1/knowledge-bases/{kbId}/query` | Ask a question (auto-routes to structured or RAG) |

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
| `POST` | `/{kbId}/documents/{docId}/financial-data/re-extract` | Force re-extraction |
| `POST` | `/{kbId}/documents/{docId}/financial-data/validate` | Re-run validation |

Full API docs at `/swagger-ui.html`.

## Accuracy

Tested across 3 companies, 3 accounting standards:

| Company | Standard | Currency | Fields | XBRL Checks | Validation |
|---------|----------|----------|--------|-------------|------------|
| Micron Technology | US GAAP | USD / Millions | 14/14 (100%) | 13/13 PASSED | PASSED |
| Singtel | IFRS | SGD / Millions | All extracted | N/A (no XBRL source yet) | PASSED |
| TCS | Ind-AS | INR / Crores | All extracted | N/A (no XBRL source yet) | PASSED |

## Project Structure

```
src/main/java/com/rkp/tenk/
  config/              # AI, Async, LM Studio, OpenAPI, Web configs
  controller/          # KnowledgeBase, Document, Query, FinancialData controllers
  service/             # Core services:
    QueryClassifier        # Hybrid routing: FINANCIAL_DATA vs NARRATIVE
    StructuredQueryService # DB lookup for financial queries (zero LLM)
    AgenticRagOrchestrator # RAG with query decomposition + evaluation
    FinancialExtractionService  # LLM-based structured extraction
    FinancialStatementDetector  # Page detection via keyword scoring
    FinancialValidationService  # Accounting identity checks
    validation/
      ValidationSource     # Pluggable validation interface
      EdgarXbrlSource      # SEC EDGAR XBRL cross-validation
  model/entity/        # KnowledgeBase, DocumentRecord, FinancialData
  model/dto/           # Request/Response DTOs
  model/enums/         # ExtractionStatus, ValidationStatus, AccountingStandard
  repository/          # JPA repositories

src/main/resources/
  static/              # FinLens Web UI (Dashboard, Compare, Chat)
  db/changelog/        # Liquibase migrations (4 changesets)
  application.yml      # Multi-profile config (lmstudio, dev, openai)
```

## Docker

```bash
docker-compose --profile docker-ollama up
```

## Testing

```bash
./mvnw test
```
