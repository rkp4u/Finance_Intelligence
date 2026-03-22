package com.rkp.tenk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkp.tenk.model.dto.EvaluationResult;
import com.rkp.tenk.model.dto.EvaluationResult.Verdict;
import com.rkp.tenk.util.LlmOutputUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RetrievalEvaluationService {

    private static final int MAX_CHUNKS_TO_EVALUATE = 10;
    private static final int CHUNK_SUMMARY_MAX_CHARS = 200;

    private static final String SYSTEM_PROMPT = """
            You are a retrieval quality evaluator for a financial document question-answering system.
            Given a user question and retrieved document chunks, determine whether the chunks contain
            sufficient information to answer the question.

            Rules:
            - If the chunks contain the key facts, figures, or topics needed to answer the question,
              respond with SUFFICIENT.
            - If the chunks are missing critical information needed to answer the question,
              respond with INSUFFICIENT and suggest 1-2 refined search queries that might find
              the missing information.
            - Be pragmatic: partial coverage is acceptable if the main aspects of the question
              can be addressed.
            - Do NOT be overly strict. If chunks contain roughly relevant information, mark SUFFICIENT.
            - Return ONLY valid JSON in the exact format below. No other text.

            When sufficient:
            {"verdict": "SUFFICIENT"}

            When insufficient:
            {"verdict": "INSUFFICIENT", "refinedQueries": ["specific query 1", "specific query 2"]}
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            Question: %s

            Retrieved chunks (%d total):
            %s

            Evaluate whether these chunks contain sufficient information to answer the question.
            Return JSON only.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final LlmOutputUtil llmOutputUtil;

    public RetrievalEvaluationService(ChatClient chatClient, ObjectMapper objectMapper,
                                       LlmOutputUtil llmOutputUtil) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.llmOutputUtil = llmOutputUtil;
    }

    /**
     * Evaluate whether retrieved chunks contain sufficient information to answer the question.
     * Fail-open: defaults to SUFFICIENT if evaluation fails.
     */
    public EvaluationResult evaluate(String question, List<Document> retrievedChunks) {
        if (retrievedChunks.isEmpty()) {
            log.debug("No chunks to evaluate, returning INSUFFICIENT");
            return EvaluationResult.insufficient(List.of(question));
        }

        try {
            String chunkSummaries = buildChunkSummaries(retrievedChunks);
            String userPrompt = USER_PROMPT_TEMPLATE.formatted(
                    question, retrievedChunks.size(), chunkSummaries);

            String rawResponse = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            String stripped = llmOutputUtil.stripThinkingBlock(rawResponse);
            return parseEvaluation(stripped);

        } catch (Exception e) {
            log.warn("Retrieval evaluation failed, defaulting to SUFFICIENT: {}", e.getMessage());
            return EvaluationResult.sufficient();
        }
    }

    private String buildChunkSummaries(List<Document> chunks) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(chunks.size(), MAX_CHUNKS_TO_EVALUATE);

        for (int i = 0; i < limit; i++) {
            Document doc = chunks.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String section = meta.getOrDefault("section_name", "Unknown").toString();
            String page = meta.getOrDefault("page_number", "N/A").toString();
            String text = doc.getText();
            String truncated = text.length() > CHUNK_SUMMARY_MAX_CHARS
                    ? text.substring(0, CHUNK_SUMMARY_MAX_CHARS) + "..."
                    : text;

            sb.append("[Chunk %d | Section: %s | Page: %s]\n".formatted(i + 1, section, page));
            sb.append(truncated).append("\n\n");
        }

        if (chunks.size() > limit) {
            sb.append("... and %d more chunks\n".formatted(chunks.size() - limit));
        }

        return sb.toString();
    }

    /**
     * Parse evaluation result with fallback to SUFFICIENT.
     */
    private EvaluationResult parseEvaluation(String content) {
        // Tier 1: Direct parse
        JsonNode node = tryParseJson(content);

        // Tier 2: Regex extraction
        if (node == null) {
            String jsonStr = llmOutputUtil.extractJsonObject(content);
            if (jsonStr != null) {
                node = tryParseJson(jsonStr);
            }
        }

        if (node == null) {
            log.warn("Could not parse evaluation response, defaulting to SUFFICIENT. Raw: {}",
                    content.length() > 200 ? content.substring(0, 200) + "..." : content);
            return EvaluationResult.sufficient();
        }

        String verdict = node.has("verdict") ? node.get("verdict").asText() : "SUFFICIENT";

        if ("INSUFFICIENT".equalsIgnoreCase(verdict)) {
            List<String> refinedQueries = new ArrayList<>();
            if (node.has("refinedQueries") && node.get("refinedQueries").isArray()) {
                for (JsonNode q : node.get("refinedQueries")) {
                    String query = q.asText();
                    if (query != null && !query.isBlank()) {
                        refinedQueries.add(query);
                    }
                }
            }
            log.info("Evaluation verdict: INSUFFICIENT, refined queries: {}", refinedQueries);
            return EvaluationResult.insufficient(refinedQueries);
        }

        log.info("Evaluation verdict: SUFFICIENT");
        return EvaluationResult.sufficient();
    }

    private JsonNode tryParseJson(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            return null;
        }
    }
}
