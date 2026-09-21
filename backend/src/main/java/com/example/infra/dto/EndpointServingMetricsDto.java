package com.example.infra.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GCP AI 엔드포인트 서빙 (Endpoint Serving) 관제 DTO
 * 24/7 실시간 온라인 예측 모델 서버 및 GPU/TPU 가속기 인프라 지표
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointServingMetricsDto {

    // 0. 프로젝트 및 고객사 정보
    private String projectId;
    private String customerName;

    // 1. 핵심 요약 KPI
    private Long totalRequests7d;              // 7일 누적 총 예측 요청수
    private Double currentQps;                 // 현재 QPS (초당 요청수)
    private Integer avgLatencyMs;              // 평균 지연시간 (ms)
    private Integer p95LatencyMs;              // 95th Percentile 지연시간 (ms)
    private Integer p99LatencyMs;              // 99th Percentile 지연시간 (ms)
    private Double errorRate4xxPercent;        // 4xx 에러율 (%)
    private Double errorRate5xxPercent;        // 5xx 에러율 (%)
    private Double successRatePercent;         // 예측 성공률 (%)
    private Long vectorSearchQueries;          // Vector Search (Matching Engine) 쿼리수
    private Long vectorSearchUpdates;          // Vector Search 스트림 업데이트수
    private Integer totalEndpoints;            // 총 엔드포인트 수
    private Integer activeEndpoints;           // 활성 엔드포인트 수
    private Integer totalAllocatedGpus;        // 총 할당된 GPU 수량
    private Double totalEstimatedHourlyCost;   // 시간당 총 인프라 서빙 비용 ($)
    private Double totalEstimatedMonthlyCost;  // 월간 환산 예상 비용 ($)

    // 2. 일별 트렌드 (복합 차트용)
    private List<String> dates;
    private List<Long> dailyRequestsTrend;
    private List<Integer> dailyLatencyTrend;
    private List<Double> dailyQpsTrend;

    // 3. 개별 엔드포인트 상세 목록
    private List<EndpointDetailDto> endpoints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointDetailDto {
        private String endpointId;
        private String endpointName;
        private String deployedModelId;
        private String deployedModelName;
        private String machineType;
        private String acceleratorType;        // NVIDIA_L4, NVIDIA_TESLA_T4, NVIDIA_A100 등
        private Integer acceleratorCount;
        private Integer minReplicas;
        private Integer maxReplicas;
        private Integer currentReplicas;
        private Long totalRequests;
        private Double qps;
        private Integer avgLatencyMs;
        private Integer p95LatencyMs;
        private Integer p99LatencyMs;
        private Long errorCount4xx;
        private Long errorCount5xx;
        private Double errorRate4xx;
        private Double errorRate5xx;
        private Double gpuUtilizationPercent;
        private Double cpuUtilizationPercent;
        private Double nodeUptimeHours;
        private Double endpointNodeHours;
        private Double hourlyCost;
        private Double monthlyCost;
        private String status;                 // ACTIVE, SCALED_TO_ZERO, IDLE
    }
}
