package com.rkp.tenk.controller;

import com.rkp.tenk.model.dto.FieldCoverage;
import com.rkp.tenk.model.dto.FinancialAnomaly;
import com.rkp.tenk.model.dto.KnowledgeHealthReport;
import com.rkp.tenk.service.KnowledgeBaseService;
import com.rkp.tenk.service.KnowledgeHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/health")
@RequiredArgsConstructor
@Tag(name = "Health", description = "Knowledge base quality and health metrics")
public class HealthController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeHealthService healthService;

    @GetMapping
    @Operation(summary = "Overall health report for a knowledge base")
    public ResponseEntity<KnowledgeHealthReport> report(@PathVariable UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return ResponseEntity.ok(healthService.generateReport(kbId));
    }

    @GetMapping("/anomalies")
    @Operation(summary = "Detected data anomalies in financial data")
    public ResponseEntity<List<FinancialAnomaly>> anomalies(@PathVariable UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return ResponseEntity.ok(healthService.detectAnomalies(kbId));
    }

    @GetMapping("/field-coverage")
    @Operation(summary = "Per-field extraction coverage across all documents")
    public ResponseEntity<List<FieldCoverage>> fieldCoverage(@PathVariable UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return ResponseEntity.ok(healthService.getFieldCoverage(kbId));
    }
}
