# FinLens — Project Instructions

## Language & Framework
- Java 17+ with Spring Boot 3.4, Spring AI 1.0
- Maven for dependency management (use `./mvnw`)
- PostgreSQL 16 + pgvector for vector storage
- Liquibase for database migrations (never use `ddl-auto: create`)

## Code Conventions
- Use Lombok (`@Slf4j`, `@RequiredArgsConstructor`, `@Data` for entities, records for DTOs)
- Financial values MUST use `BigDecimal`, never `double` or `float`
- All new services must have `@Slf4j` logging
- Use Java records for DTOs and immutable data (`record QueryRequest(...)`)
- Constructor injection via `@RequiredArgsConstructor` (no `@Autowired` on fields)
- Use `@ConfigurationProperties` for grouped config, NOT `@Value` annotations. `@Value` is brittle, not type-safe, and scatters config across classes. Bind config to a record or POJO instead.
- Never hardcode API keys — use `${ENV_VAR}` in application.yml
- Embedding dimensions must be 768 across all profiles (lmstudio, dev, openai)

## Architecture Patterns
- **Hybrid query routing**: financial data queries → DB lookup (StructuredQueryService), narrative queries → vector RAG (AgenticRagOrchestrator)
- **Pluggable validation**: implement `ValidationSource` interface for new validation providers
- **Async processing**: document ingestion runs on `documentProcessingExecutor` thread pool — never put `@Transactional` on `@Async` methods
- **Financial extraction**: runs after vector embedding during ingestion, failure does NOT affect RAG pipeline

## Database
- Migrations in `src/main/resources/db/changelog/changes/` as YAML
- Naming: `NNN-description.yaml` (e.g., `004-add-pdf-content-to-document-record.yaml`)
- Always add to `db.changelog-master.yaml`
- Use `numeric(20,2)` for financial amounts
- Store JSONB for flexible structures (validation_details)

## Testing
- Integration tests use Testcontainers for PostgreSQL
- Test financial extraction against 3 companies: Micron (US GAAP), TCS (Ind-AS), Singtel (IFRS)
- All 14 financial fields must be verified for accuracy
- XBRL cross-validation must pass for US companies (13 checks)
- Run tests: `./mvnw test`

## Profiles
| Profile | LLM | Embeddings | Use |
|---------|-----|-----------|-----|
| `lmstudio` | Qwen 3 14B (local) | nomic-embed-text | Development |
| `dev` | Ollama qwen3:14b | nomic-embed-text | Docker dev |
| `openai` | gpt-4o-mini | text-embedding-3-small (768d) | Production/accuracy testing |

## API Design
- All endpoints under `/api/v1/` with plural nouns
- Knowledge base scoped: `/api/v1/knowledge-bases/{kbId}/...`
- Use `ResponseEntity<T>` return types
- Validate with `@Valid` on request body
- Return 404 via `ResourceNotFoundException`, 400 for bad input
- Swagger docs at `/swagger-ui.html`

## Git
- Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`
- Branch naming: `feature/`, `fix/`, `chore/`
- Never commit API keys or `.env` files

## Key Files
- `FinancialExtractionService.java` — LLM extraction orchestrator
- `FinancialStatementDetector.java` — page detection via keyword scoring
- `FinancialValidationService.java` — accounting identity checks + pluggable sources
- `EdgarXbrlSource.java` — SEC EDGAR XBRL cross-validation
- `QueryClassifier.java` — hybrid routing (FINANCIAL_DATA vs NARRATIVE)
- `StructuredQueryService.java` — DB lookup for financial queries (zero LLM cost)
- `AgenticRagOrchestrator.java` — RAG with query decomposition + evaluation
