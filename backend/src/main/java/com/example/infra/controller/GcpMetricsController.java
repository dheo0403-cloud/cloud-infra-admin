package com.example.infra.controller;

import com.example.infra.dto.VertexAiMetricsDto;
import com.example.infra.dto.VertexEndpointMetricsDto;
import com.example.infra.service.GcpVertexAiMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.RequestParam;

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
     * @param projectId 타겟 고객사 GCP 프로젝트 ID (선택 사항)
     * @param targetYearMonth 조회 대상 연월 (예: 2026-08, 2026-09)
     * @return VertexAiMetricsDto
     */
    @GetMapping("/vertex-ai")
    public ResponseEntity<VertexAiMetricsDto> getVertexAiMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth) {
        log.info("[API] Requesting GCP Vertex AI operations metrics for target projectId: {}, targetYearMonth: {}", projectId, targetYearMonth);
        VertexAiMetricsDto metrics = gcpVertexAiMetricsService.getVertexAiOperationsMetrics(projectId, targetYearMonth);
        return ResponseEntity.ok(metrics);
    }

    /**
     * GCP Vertex AI Endpoint 온라인 예측 호출 및 인프라 관제 메트릭 조회
     * @param projectId 타겟 고객사 GCP 프로젝트 ID (선택 사항)
     * @param targetYearMonth 조회 대상 연월 (예: 2026-08, 2026-09)
     * @return VertexEndpointMetricsDto
     */
    @GetMapping("/vertex-endpoints")
    public ResponseEntity<VertexEndpointMetricsDto> getVertexEndpointMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth) {
        log.info("[API] Requesting GCP Vertex AI Endpoint operations metrics for target projectId: {}, targetYearMonth: {}", projectId, targetYearMonth);
        VertexEndpointMetricsDto metrics = gcpVertexAiMetricsService.getVertexEndpointOperationsMetrics(projectId, targetYearMonth);
        return ResponseEntity.ok(metrics);
    }
}
