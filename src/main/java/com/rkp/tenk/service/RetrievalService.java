package com.rkp.tenk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final VectorStore vectorStore;

    /**
     * Retrieve relevant document chunks from the vector store,
     * filtered by knowledge base ID for multi-tenant isolation.
     */
    public List<Document> retrieveRelevantChunks(String query, UUID knowledgeBaseId,
                                                   int topK, double similarityThreshold) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(b.eq("knowledge_base_id", knowledgeBaseId.toString()).build())
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);
        log.info("Retrieved {} chunks for query in knowledge base {} (topK={}, threshold={})",
                results.size(), knowledgeBaseId, topK, similarityThreshold);
        return results;
    }
}
