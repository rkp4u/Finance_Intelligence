package com.rkp.tenk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkp.tenk.model.dto.DecompositionResult;
import com.rkp.tenk.util.LlmOutputUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class QueryDecompositionService {

    private static final String SYSTEM_PROMPT = """
            You are a query analysis assistant for financial document search.
            Analyze the user question and decide: return it as-is for simple questions,
            or decompose into 2-3 focused sub-queries for complex/multi-topic questions.

            Rules:
            - Simple, single-topic questions: return as a single-element JSON array.
            - Complex, multi-faceted questions: decompose into 2-3 focused sub-queries.
            - Each sub-query must be self-contained and specific enough for document retrieval.
            - Do NOT add topics beyond what the original question asks.
            - Return ONLY a JSON array of strings, no other text.

            Examples:
            Question: "What was the total revenue in 2023?"
            ["What was the total revenue in 2023?"]

            Question: "Compare the revenue growth with R&D spending and discuss risk factors."
            ["What is the revenue and revenue growth trend?", "What is the R&D spending trend?", "What are the key risk factors?"]

            Question: "What are the main risk factors and how has debt changed?"
            ["What are the main risk factors disclosed?", "How has the company's debt and borrowings changed?"]
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            Analyze this question and return a JSON array of queries (1 to 3 strings):
            Question: %s
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final LlmOutputUtil llmOutputUtil;

    public QueryDecompositionService(ChatClient chatClient, ObjectMapper objectMapper,
                                      LlmOutputUtil llmOutputUtil) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.llmOutputUtil = llmOutputUtil;
    }

    /**
     * Decompose a user question into 1-3 focused sub-queries using the LLM.
     * Falls back to the original question if parsing fails.
     */
    public DecompositionResult decompose(String question) {
        long start = System.currentTimeMillis();

        try {
            String rawResponse = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(USER_PROMPT_TEMPLATE.formatted(question))
                    .call()
                    .content();

            String stripped = llmOutputUtil.stripThinkingBlock(rawResponse);
            List<String> queries = parseQueries(stripped, question);

            long timeMs = System.currentTimeMillis() - start;
            boolean wasDecomposed = queries.size() > 1;

            log.info("Query decomposition: {} -> {} sub-queries ({}ms, decomposed={})",
                    truncate(question, 80), queries.size(), timeMs, wasDecomposed);
            log.debug("Sub-queries: {}", queries);

            return new DecompositionResult(queries, wasDecomposed, timeMs);

        } catch (Exception e) {
            long timeMs = System.currentTimeMillis() - start;
            log.warn("Query decomposition failed, using original question: {}", e.getMessage());
            return new DecompositionResult(List.of(question), false, timeMs);
        }
    }

    /**
     * Parse sub-queries from LLM output with 3-tier fallback:
     * 1. Direct JSON parse
     * 2. Regex extraction of JSON array
     * 3. Fall back to original question
     */
    private List<String> parseQueries(String content, String originalQuestion) {
        // Tier 1: Direct parse
        try {
            List<String> queries = objectMapper.readValue(content, new TypeReference<>() {});
            List<String> valid = validateQueries(queries);
            if (!valid.isEmpty()) return valid;
        } catch (Exception ignored) {
            // Fall through to tier 2
        }

        // Tier 2: Regex extraction
        String jsonArray = llmOutputUtil.extractJsonArray(content);
        if (jsonArray != null) {
            try {
                List<String> queries = objectMapper.readValue(jsonArray, new TypeReference<>() {});
                List<String> valid = validateQueries(queries);
                if (!valid.isEmpty()) return valid;
            } catch (Exception ignored) {
                // Fall through to tier 3
            }
        }

        // Tier 3: Fallback
        log.warn("Could not parse decomposition response, falling back to original question. Raw: {}",
                truncate(content, 200));
        return List.of(originalQuestion);
    }

    private List<String> validateQueries(List<String> queries) {
        if (queries == null) return List.of();
        List<String> valid = queries.stream()
                .filter(q -> q != null && !q.isBlank())
                .limit(3)
                .toList();
        return valid.isEmpty() ? List.of() : valid;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
