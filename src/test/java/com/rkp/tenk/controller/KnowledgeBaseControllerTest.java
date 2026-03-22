package com.rkp.tenk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkp.tenk.model.dto.KnowledgeBaseRequest;
import com.rkp.tenk.model.dto.KnowledgeBaseResponse;
import com.rkp.tenk.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KnowledgeBaseController.class)
class KnowledgeBaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    void createShouldReturn201() throws Exception {
        UUID id = UUID.randomUUID();
        KnowledgeBaseResponse response = new KnowledgeBaseResponse(
                id, "Test KB", "Test description", Instant.now(), Instant.now(), 0);

        when(knowledgeBaseService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new KnowledgeBaseRequest("Test KB", "Test description"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test KB"))
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void createWithBlankNameShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new KnowledgeBaseRequest("", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAllShouldReturnKnowledgeBases() throws Exception {
        KnowledgeBaseResponse kb = new KnowledgeBaseResponse(
                UUID.randomUUID(), "KB1", "Desc", Instant.now(), Instant.now(), 2);

        when(knowledgeBaseService.listAll()).thenReturn(List.of(kb));

        mockMvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("KB1"))
                .andExpect(jsonPath("$[0].documentCount").value(2));
    }
}
