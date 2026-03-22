package com.rkp.tenk.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class LlmOutputUtil {

    private static final Pattern THINKING_BLOCK = Pattern.compile("(?s)<think>.*?</think>\\s*");
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*]", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);

    /**
     * Strip {@code <think>...</think>} blocks from Qwen 3 model output.
     */
    public String stripThinkingBlock(String response) {
        if (response == null) return "";
        return THINKING_BLOCK.matcher(response).replaceAll("").trim();
    }

    /**
     * Extract a JSON array string from LLM output that may contain markdown
     * code fences or surrounding text.
     *
     * @return the extracted JSON array string, or null if not found
     */
    public String extractJsonArray(String text) {
        if (text == null || text.isBlank()) return null;

        // Try extracting from code fences first
        String fromFence = extractFromCodeFence(text);
        if (fromFence != null) {
            Matcher m = JSON_ARRAY.matcher(fromFence);
            if (m.find()) return m.group();
        }

        // Try direct extraction
        Matcher m = JSON_ARRAY.matcher(text);
        if (m.find()) return m.group();

        return null;
    }

    /**
     * Extract a JSON object string from LLM output that may contain markdown
     * code fences or surrounding text.
     *
     * @return the extracted JSON object string, or null if not found
     */
    public String extractJsonObject(String text) {
        if (text == null || text.isBlank()) return null;

        // Try extracting from code fences first
        String fromFence = extractFromCodeFence(text);
        if (fromFence != null) {
            Matcher m = JSON_OBJECT.matcher(fromFence);
            if (m.find()) return m.group();
        }

        // Try direct extraction
        Matcher m = JSON_OBJECT.matcher(text);
        if (m.find()) return m.group();

        return null;
    }

    private String extractFromCodeFence(String text) {
        Matcher m = CODE_FENCE.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}
