package com.example.infra.controller;

import com.example.infra.dto.VertexAiMetricsDto;
import com.example.infra.service.GcpVertexAiMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GCP 클라우드 지표 및 Vertex AI / 생성형 AI 관제 컨트롤러 API
 */
@Slf4j
@RestController
@RequestMapping("/api/metrics/gcp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GcpMetricsController {

    private final GcpVertexAiMetricsService gcpVertexAiMetricsService;

    /**
     * GCP Vertex AI 및 GenAI 운영/비용 관제 메트릭 조회
     * @return VertexAiMetricsDto
     */
    @GetMapping("/vertex-ai")
    public ResponseEntity<VertexAiMetricsDto> getVertexAiMetrics() {
        log.info("[API] Requesting GCP Vertex AI operations metrics");
        VertexAiMetricsDto metrics = gcpVertexAiMetricsService.getVertexAiOperationsMetrics();
        return ResponseEntity.ok(metrics);
    }
}
