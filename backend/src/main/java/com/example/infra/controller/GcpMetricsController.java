package com.example.infra.controller;

import com.example.infra.dto.DirectAiMetricsDto;
import com.example.infra.dto.EndpointServingMetricsDto;
import com.example.infra.dto.VertexAiMetricsDto;
import com.example.infra.dto.VertexEndpointMetricsDto;
import com.example.infra.service.GcpVertexAiMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GCP 클라우드 지표 및 Direct AI Usage & Endpoint Serving 듀얼 AI 관제 컨트롤러 API
 */
@Slf4j
@RestController
@RequestMapping("/api/metrics/gcp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GcpMetricsController {

    private final GcpVertexAiMetricsService gcpVertexAiMetricsService;

    /**
     * GCP AI 서비스 직접 사용 (Direct AI Usage) 관제 메트릭 조회
     * @param projectId 타겟 고객사 GCP 프로젝트 ID (선택 사항)
     * @param targetYearMonth 조회 대상 연월 (예: 2026-08, 2026-09)
     * @return DirectAiMetricsDto
     */
    @GetMapping("/direct-ai")
    public ResponseEntity<DirectAiMetricsDto> getDirectAiMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth) {
        log.info("[API] Requesting GCP Direct AI usage metrics for target projectId: {}, targetYearMonth: {}", projectId, targetYearMonth);
        DirectAiMetricsDto metrics = gcpVertexAiMetricsService.getDirectAiOperationsMetrics(projectId, targetYearMonth);
        return ResponseEntity.ok(metrics);
    }

    /**
     * GCP AI 엔드포인트 서빙 (Endpoint Serving) 온라인 예측 및 인프라 관제 메트릭 조회
     * @param projectId 타겟 고객사 GCP 프로젝트 ID (선택 사항)
     * @param targetYearMonth 조회 대상 연월 (예: 2026-08, 2026-09)
     * @return EndpointServingMetricsDto
     */
    @GetMapping("/endpoint-serving")
    public ResponseEntity<EndpointServingMetricsDto> getEndpointServingMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth) {
        log.info("[API] Requesting GCP AI Endpoint Serving metrics for target projectId: {}, targetYearMonth: {}", projectId, targetYearMonth);
        EndpointServingMetricsDto metrics = gcpVertexAiMetricsService.getEndpointServingOperationsMetrics(projectId, targetYearMonth);
        return ResponseEntity.ok(metrics);
    }

    // Legacy Fallback Endpoints
    @GetMapping("/vertex-ai")
    public ResponseEntity<VertexAiMetricsDto> getVertexAiMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth) {
        VertexAiMetricsDto metrics = gcpVertexAiMetricsService.getVertexAiOperationsMetrics(projectId, targetYearMonth);
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/vertex-endpoints")
    public ResponseEntity<VertexEndpointMetricsDto> getVertexEndpointMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth) {
        VertexEndpointMetricsDto metrics = gcpVertexAiMetricsService.getVertexEndpointOperationsMetrics(projectId, targetYearMonth);
        return ResponseEntity.ok(metrics);
    }
}
