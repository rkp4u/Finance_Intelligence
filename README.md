# 10-K RAG

A production-quality Retrieval-Augmented Generation (RAG) system for querying SEC 10-K financial filings using natural language. Built with Spring Boot 3.4, Spring AI 1.0, and PostgreSQL + pgvector.

## Features

- **PDF Ingestion** - Upload 10-K filings (up to 200MB), automatically extracted, chunked by SEC sections, and embedded
- **Vector Search** - Cosine similarity search with HNSW indexing via pgvector
- **RAG Query Pipeline** - Ask questions in natural language, get cited answers grounded in your documents
- **Multi-Document** - Upload multiple PDFs per knowledge base; queries search across all documents
- **Large PDF Support** - Handles 500+ page filings via batched processing (50 pages at a time)
- **Multi-Language** - Auto-detects document language; multilingual embedding support
- **Pluggable LLM** - Switch between local (LM Studio, Ollama) and cloud (OpenAI) models via Spring profiles
- **Web UI** - Dark-themed chat interface with markdown rendering, source cards, and document management

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 3.4.4, Spring AI 1.0.0 |
| LLM (default) | Qwen 3 14B via LM Studio |
| Embeddings | nomic-embed-text v1.5 (768 dimensions) |
| Vector Store | PostgreSQL 16 + pgvector (HNSW index) |
| PDF Parsing | Apache PDFBox (via Spring AI PDF Reader) |
| Migrations | Liquibase |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Java | 17 |

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

Open LM Studio, then load the models via CLI:

```bash
lms server start
lms load qwen/qwen3-14b --context-length 16384
lms load text-embedding-nomic-embed-text-v1.5
```

### 3. Run the Application

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=lmstudio
```

The app starts at **http://localhost:8080**

### 4. Use It

1. Create a knowledge base in the sidebar
2. Upload a 10-K PDF (drag-and-drop or browse)
3. Wait for processing (status shows progress)
4. Ask questions in the chat

## LLM Profiles

| Profile | Provider | Chat Model | Embedding Model | Dimensions |
|---------|----------|-----------|----------------|------------|
| `lmstudio` | LM Studio (local GPU) | qwen/qwen3-14b | nomic-embed-text-v1.5 | 768 |
| `dev` | Ollama (local/Docker) | qwen3:14b | nomic-embed-text | 768 |
| `openai` | OpenAI Cloud | gpt-4o-mini | text-embedding-3-small | 1536 |

Switch profiles:

```bash
# LM Studio (default for development)
./mvnw spring-boot:run -Dspring-boot.run.profiles=lmstudio

# Ollama
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# OpenAI (requires OPENAI_API_KEY env var)
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

> **Note:** Switching embedding providers (768d vs 1536d) requires re-ingesting all documents.

## API Endpoints

### Knowledge Bases

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/knowledge-bases` | Create knowledge base |
| `GET` | `/api/v1/knowledge-bases` | List all |
| `GET` | `/api/v1/knowledge-bases/{id}` | Get by ID |
| `PUT` | `/api/v1/knowledge-bases/{id}` | Update |
| `DELETE` | `/api/v1/knowledge-bases/{id}` | Delete (cascades to docs + embeddings) |

### Documents

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/knowledge-bases/{kbId}/documents` | Upload PDF (multipart) |
| `GET` | `/api/v1/knowledge-bases/{kbId}/documents` | List documents |
| `GET` | `/api/v1/knowledge-bases/{kbId}/documents/{docId}` | Get status |
| `DELETE` | `/api/v1/knowledge-bases/{kbId}/documents/{docId}` | Delete document + embeddings |

### Query

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/knowledge-bases/{kbId}/query` | Ask a question |

**Request body:**

```json
{
  "question": "What were the total revenues?",
  "topK": 5,
  "similarityThreshold": 0.3
}
```

**Response:**

```json
{
  "answer": "The total revenues were $391 billion...",
  "sources": [
    {
      "sectionName": "ITEM 8. FINANCIAL STATEMENTS",
      "pageNumber": 31,
      "similarityScore": 0.66,
      "content": "Consolidated Statements of Operations..."
    }
  ],
  "metadata": {
    "chunksRetrieved": 5,
    "retrievalTimeMs": 85,
    "generationTimeMs": 62500,
    "modelUsed": "qwen/qwen3-14b"
  }
}
```

Full API documentation available at `/swagger-ui.html` when running.

## Docker (Full Stack)

Run everything in Docker (uses Ollama, not LM Studio):

```bash
docker-compose --profile docker-ollama up
```

This starts PostgreSQL, Ollama (with model downloads), and the Spring Boot app.

## Project Structure

```
src/main/java/com/rkp/tenk/
  config/         # AiConfig, AsyncConfig, LmStudioConfig, WebConfig
  controller/     # KnowledgeBaseController, DocumentController, QueryController
  service/        # PdfProcessingService, ChunkingService, RetrievalService,
                  # GenerationService, DocumentIngestionService, KnowledgeBaseService
  model/entity/   # KnowledgeBase, DocumentRecord
  model/dto/      # Request/Response DTOs
  exception/      # GlobalExceptionHandler, custom exceptions
  repository/     # JPA repositories

src/main/resources/
  static/         # Web UI (index.html, style.css, app.js)
  db/changelog/   # Liquibase migrations
  application.yml # Multi-profile configuration
```

## Testing

```bash
./mvnw test
```

13 tests covering chunking, PDF processing, generation, and controller layers. Uses Testcontainers for PostgreSQL integration tests.
