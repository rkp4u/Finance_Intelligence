package com.rkp.tenk.service;

import com.rkp.tenk.exception.QueryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GenerationService {

    private static final String SYSTEM_PROMPT = """
            You are a financial analyst assistant specializing in SEC 10-K filings and financial documents.
            Answer questions ONLY based on the provided context from the documents.

            Rules:
            - If the context does not contain sufficient information to answer the question, say so explicitly.
            - Always cite the specific section (e.g., "Item 7", "Item 1A") and page numbers when available.
            - For numerical data, quote exact figures from the context.
            - Do not speculate or use information outside the provided context.
            - Format financial figures consistently (e.g., $X.X million/billion).
            - If the user's question is in a language other than English, respond in that language.
            - Be concise but thorough in your analysis.
            """;

    private final ChatClient chatClient;

    public GenerationService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Generate an answer using retrieved context chunks and the user's question.
     */
    public String generateAnswer(String question, List<Document> contextDocuments) {
        if (contextDocuments.isEmpty()) {
            return "I could not find any relevant information in the knowledge base to answer your question. "
                    + "Please ensure the relevant documents have been uploaded and processed.";
        }

        String context = contextDocuments.stream()
                .map(this::formatChunkWithMetadata)
                .collect(Collectors.joining("\n\n---\n\n"));

        String userPrompt = """
                Context from documents:
                %s

                Question: %s

                Provide a detailed answer with citations to specific sections and pages.
                """.formatted(context, question);

        try {
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            return stripThinkingBlock(response);
        } catch (Exception e) {
            log.error("LLM generation failed for question: {}", question, e);
            throw new QueryException("Failed to generate answer: " + e.getMessage(), e);
        }
    }

    private String formatChunkWithMetadata(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        String sectionName = getMetadataString(metadata, "section_name", "Unknown Section");
        String pageNumber = getMetadataString(metadata, "page_number", "N/A");

        return "[Section: %s | Page: %s]\n%s".formatted(sectionName, pageNumber, doc.getText());
    }

    private String getMetadataString(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Strip reasoning/thinking blocks from models like Qwen 3 that include
     * chain-of-thought in {@code <think>...</think>} tags.
     */
    private String stripThinkingBlock(String response) {
        if (response == null) return "";
        return response.replaceAll("(?s)<think>.*?</think>\\s*", "").trim();
    }
}
