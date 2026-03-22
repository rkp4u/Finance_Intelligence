package com.rkp.tenk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class PdfProcessingServiceTest {

    private PdfProcessingService pdfProcessingService;

    @BeforeEach
    void setUp() {
        pdfProcessingService = new PdfProcessingService();
    }

    @Test
    void detectLanguageShouldReturnEnForEnglishText() {
        Document page = new Document("This is an English document about financial performance.");
        String lang = pdfProcessingService.detectLanguage(List.of(page));
        assertThat(lang).isEqualTo("en");
    }

    @Test
    void detectLanguageShouldReturnEnForEmptyList() {
        String lang = pdfProcessingService.detectLanguage(Collections.emptyList());
        assertThat(lang).isEqualTo("en");
    }

    @Test
    void detectLanguageShouldReturnEnForBlankContent() {
        Document page = new Document("   ");
        String lang = pdfProcessingService.detectLanguage(List.of(page));
        assertThat(lang).isEqualTo("en");
    }
}
