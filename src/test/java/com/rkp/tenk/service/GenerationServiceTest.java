package com.rkp.tenk.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationServiceTest {

    @Test
    void shouldReturnNoInfoMessageWhenNoChunks() {
        // Use a null chat client to test the empty context path
        GenerationService service = new GenerationService(null);

        String answer = service.generateAnswer("What is the revenue?", Collections.emptyList());

        assertThat(answer).contains("could not find any relevant information");
    }
}
