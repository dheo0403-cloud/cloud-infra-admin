package com.example.infra.controller;

import com.example.infra.dto.BigQueryOptimizationDto;
import com.example.infra.dto.DirectAiMetricsDto;
import com.example.infra.dto.EndpointServingMetricsDto;
import com.example.infra.dto.VertexAiMetricsDto;
import com.example.infra.dto.VertexEndpointMetricsDto;
import com.example.infra.service.BigQueryOptimizationService;
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
 * GCP 클라우드 지표 및 Direct AI Usage & Endpoint Serving & BigQuery 성능 최적화 관제 컨트롤러 API
 */
@Slf4j
@RestController
@RequestMapping("/api/metrics/gcp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GcpMetricsController {

    private final GcpVertexAiMetricsService gcpVertexAiMetricsService;
    private final BigQueryOptimizationService bigQueryOptimizationService;

    /**
     * GCP BigQuery 성능 및 비용 최적화 관제 메트릭 조회 (트렌드, 고비용 TOP 10, 슬롯/장기실행 TOP 10)
     */
    @GetMapping("/bigquery-optimization")
    public ResponseEntity<BigQueryOptimizationDto> getBigQueryOptimizationMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth) {
        log.info("[API] Requesting GCP BigQuery Optimization metrics for target projectId: {}, targetYearMonth: {}", projectId, targetYearMonth);
        BigQueryOptimizationDto metrics = bigQueryOptimizationService.getBigQueryOptimizationMetrics(projectId, targetYearMonth);
        return ResponseEntity.ok(metrics);
    }

    /**
     * 전체 20개 GCP 프로젝트 대상 과거 4개월(6~9월) BigQuery 성능 최적화 데이터 일괄 백필 트리거
     */
    @RequestMapping({"/bigquery-optimization/backfill"})
    public ResponseEntity<java.util.Map<String, Object>> backfillBigQueryOptimizationMetrics() {
        log.info("[API] Triggering bulk backfill for all 20 GCP projects across 4 months (2026-06 ~ 2026-09)");
        bigQueryOptimizationService.backfillAllProjects4MonthsBulk();
        java.util.Map<String, Object> res = new java.util.HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "20개 프로젝트 대상 과거 4개월(6~9월) BigQuery 최적화 데이터 일괄 롤업 완료");
        return ResponseEntity.ok(res);
    }

    /**
     * GCP AI 서비스 직접 사용 (Direct AI Usage) 관제 메트릭 조회
     * @param projectId 타겟 고객사 GCP 프로젝트 ID (선택 사항)
     * @param targetYearMonth 조회 대상 연월 (예: 2026-08, 2026-09)
     * @param period 조회 주기 (monthly: 월간 30일, quarterly: 분기 90일)
     * @return DirectAiMetricsDto
     */
    @GetMapping("/direct-ai")
    public ResponseEntity<DirectAiMetricsDto> getDirectAiMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth,
            @RequestParam(required = false, defaultValue = "monthly") String period) {
        log.info("[API] Requesting GCP Direct AI usage metrics for target projectId: {}, targetYearMonth: {}, period: {}", projectId, targetYearMonth, period);
        DirectAiMetricsDto metrics = gcpVertexAiMetricsService.getDirectAiOperationsMetrics(projectId, targetYearMonth, period);
        return ResponseEntity.ok(metrics);
    }

    /**
     * GCP AI 엔드포인트 서빙 (Endpoint Serving) 온라인 예측 및 인프라 관제 메트릭 조회
     * @param projectId 타겟 고객사 GCP 프로젝트 ID (선택 사항)
     * @param targetYearMonth 조회 대상 연월 (예: 2026-08, 2026-09)
     * @param period 조회 주기 (monthly: 월간 30일, quarterly: 분기 90일)
     * @return EndpointServingMetricsDto
     */
    @GetMapping("/endpoint-serving")
    public ResponseEntity<EndpointServingMetricsDto> getEndpointServingMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth,
            @RequestParam(required = false, defaultValue = "monthly") String period) {
        log.info("[API] Requesting GCP AI Endpoint Serving metrics for target projectId: {}, targetYearMonth: {}, period: {}", projectId, targetYearMonth, period);
        EndpointServingMetricsDto metrics = gcpVertexAiMetricsService.getEndpointServingOperationsMetrics(projectId, targetYearMonth, period);
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
