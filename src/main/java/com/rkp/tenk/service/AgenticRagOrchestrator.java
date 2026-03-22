package com.rkp.tenk.service;

import com.rkp.tenk.model.dto.DecompositionResult;
import com.rkp.tenk.model.dto.EvaluationResult;
import com.rkp.tenk.model.dto.OrchestratorResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgenticRagOrchestrator {

    private final QueryDecompositionService decompositionService;
    private final RetrievalService retrievalService;
    private final RetrievalEvaluationService evaluationService;
    private final GenerationService generationService;
    private final Executor queryExecutor;

    @Value("${rag.agentic.enabled:false}")
    private boolean agenticEnabledDefault;

    @Value("${rag.agentic.max-evaluation-retries:1}")
    private int maxEvaluationRetries;

    public AgenticRagOrchestrator(QueryDecompositionService decompositionService,
                                   RetrievalService retrievalService,
                                   RetrievalEvaluationService evaluationService,
                                   GenerationService generationService,
                                   @Qualifier("queryExecutor") Executor queryExecutor) {
        this.decompositionService = decompositionService;
        this.retrievalService = retrievalService;
        this.evaluationService = evaluationService;
        this.generationService = generationService;
        this.queryExecutor = queryExecutor;
    }

    /**
     * Execute the RAG pipeline. Delegates to simple or agentic mode based on the toggle.
     *
     * @param agenticMode null to use global config default, or explicit true/false per-request
     */
    public OrchestratorResult execute(String question, UUID kbId, int topK,
                                       double similarityThreshold, Boolean agenticMode) {
        boolean useAgentic = agenticMode != null ? agenticMode : agenticEnabledDefault;

        if (useAgentic) {
            return executeAgentic(question, kbId, topK, similarityThreshold);
        }
        return executeSimple(question, kbId, topK, similarityThreshold);
    }

    /**
     * Simple mode: existing behavior — single retrieval then generation.
     */
    private OrchestratorResult executeSimple(String question, UUID kbId, int topK,
                                              double similarityThreshold) {
        long retrievalStart = System.currentTimeMillis();
        List<Document> chunks = retrievalService.retrieveRelevantChunks(
                question, kbId, topK, similarityThreshold);
        long retrievalTimeMs = System.currentTimeMillis() - retrievalStart;

        long generationStart = System.currentTimeMillis();
        String answer = generationService.generateAnswer(question, chunks);
        long generationTimeMs = System.currentTimeMillis() - generationStart;

        return new OrchestratorResult(
                answer, chunks,
                0, retrievalTimeMs, 0, generationTimeMs,
                0, List.of(question), false
        );
    }

    /**
     * Agentic mode: decompose → parallel retrieve → evaluate/retry → generate.
     */
    private OrchestratorResult executeAgentic(String question, UUID kbId, int topK,
                                               double similarityThreshold) {
        log.info("Agentic RAG pipeline starting for: {}", truncate(question, 80));

        // Step 1: Decompose
        DecompositionResult decomposition = decompositionService.decompose(question);
        List<String> subQueries = decomposition.queries();

        // Step 2: Parallel retrieval
        long retrievalStart = System.currentTimeMillis();
        List<Document> mergedChunks = retrieveParallel(subQueries, kbId, topK, similarityThreshold);
        long retrievalTimeMs = System.currentTimeMillis() - retrievalStart;

        // Step 3: Evaluate and retry if needed
        long evaluationStart = System.currentTimeMillis();
        int evaluationRounds = 0;

        for (int attempt = 0; attempt < maxEvaluationRetries; attempt++) {
            if (mergedChunks.isEmpty()) {
                log.debug("No chunks retrieved, skipping evaluation");
                break;
            }

            EvaluationResult evaluation = evaluationService.evaluate(question, mergedChunks);
            evaluationRounds++;

            if (evaluation.verdict() == EvaluationResult.Verdict.SUFFICIENT) {
                log.info("Evaluation: SUFFICIENT after {} round(s)", evaluationRounds);
                break;
            }

            // INSUFFICIENT — retry with refined queries
            List<String> refinedQueries = evaluation.refinedQueries();
            if (refinedQueries.isEmpty()) {
                log.info("Evaluation: INSUFFICIENT but no refined queries suggested, proceeding");
                break;
            }

            log.info("Evaluation: INSUFFICIENT (round {}), retrying with {} refined queries",
                    evaluationRounds, refinedQueries.size());

            List<Document> additionalChunks = retrieveParallel(
                    refinedQueries, kbId, topK, similarityThreshold);
            mergedChunks = mergeAndDeduplicate(mergedChunks, additionalChunks);

            // Track the refined queries for observability
            subQueries = new ArrayList<>(subQueries);
            subQueries.addAll(refinedQueries);
        }

        long evaluationTimeMs = System.currentTimeMillis() - evaluationStart;

        // Step 4: Generate
        long generationStart = System.currentTimeMillis();
        String answer = generationService.generateAnswer(question, mergedChunks);
        long generationTimeMs = System.currentTimeMillis() - generationStart;

        log.info("Agentic RAG complete: {} sub-queries, {} eval rounds, {} chunks, "
                        + "decompose={}ms, retrieve={}ms, evaluate={}ms, generate={}ms",
                subQueries.size(), evaluationRounds, mergedChunks.size(),
                decomposition.decompositionTimeMs(), retrievalTimeMs,
                evaluationTimeMs, generationTimeMs);

        return new OrchestratorResult(
                answer, mergedChunks,
                decomposition.decompositionTimeMs(), retrievalTimeMs,
                evaluationTimeMs, generationTimeMs,
                evaluationRounds, subQueries, true
        );
    }

    /**
     * Execute retrieval for multiple queries in parallel and merge results.
     */
    private List<Document> retrieveParallel(List<String> queries, UUID kbId, int topK,
                                             double similarityThreshold) {
        if (queries.size() == 1) {
            // No need for parallelism with a single query
            return retrievalService.retrieveRelevantChunks(
                    queries.get(0), kbId, topK, similarityThreshold);
        }

        List<CompletableFuture<List<Document>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(
                        () -> retrievalService.retrieveRelevantChunks(q, kbId, topK, similarityThreshold),
                        queryExecutor
                ).exceptionally(ex -> {
                    log.warn("Sub-query retrieval failed for '{}': {}", truncate(q, 60), ex.getMessage());
                    return List.of();
                }))
                .toList();

        // Wait for all and merge
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        List<Document> allChunks = new ArrayList<>();
        for (CompletableFuture<List<Document>> f : futures) {
            allChunks.addAll(f.join());
        }

        return deduplicateChunks(allChunks);
    }

    /**
     * Merge two chunk lists and deduplicate.
     */
    private List<Document> mergeAndDeduplicate(List<Document> existing, List<Document> additional) {
        List<Document> combined = new ArrayList<>(existing);
        combined.addAll(additional);
        return deduplicateChunks(combined);
    }

    /**
     * Deduplicate chunks by Document ID, keeping the instance with the highest score.
     * Results are sorted by score descending.
     */
    private List<Document> deduplicateChunks(List<Document> chunks) {
        Map<String, Document> best = new LinkedHashMap<>();

        for (Document doc : chunks) {
            String id = doc.getId();
            if (id == null) {
                // No ID — keep it (can't deduplicate)
                best.put(UUID.randomUUID().toString(), doc);
                continue;
            }

            Document existing = best.get(id);
            if (existing == null || getScore(doc) > getScore(existing)) {
                best.put(id, doc);
            }
        }

        return best.values().stream()
                .sorted(Comparator.comparingDouble(this::getScore).reversed())
                .collect(Collectors.toList());
    }

    private double getScore(Document doc) {
        Double score = doc.getScore();
        return score != null ? score : 0.0;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
