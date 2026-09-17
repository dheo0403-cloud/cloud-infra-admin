package com.example.infra.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GCP Vertex AI 커스텀 모델 엔드포인트(Endpoint) 호출량, 지연시간 및 인프라 관제 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VertexEndpointMetricsDto {

    // 0. 프로젝트 및 고객사 정보
    private String projectId;
    private String customerName;

    // 1. 핵심 요약 지표
    private Long totalPredictRequests7d;       // 7일간 총 예측 요청 수 (Calls)
    private Integer avgLatencyMs;              // 전체 엔드포인트 평균 지연시간 (ms)
    private Integer p95LatencyMs;              // P95 지연시간 (ms)
    private Double successRatePercent;         // 예측 성공률 (%)
    private Integer totalEndpoints;            // 총 엔드포인트 수
    private Integer activeEndpoints;           // 활성 엔드포인트 수
    private Double totalEstimatedHourlyCost;   // 시간당 총 인프라 비용 ($)
    private Double totalEstimatedMonthlyCost;  // 월간 환산 예상 비용 ($)

    // 2. 일별 트렌드 (복합 차트용)
    private List<String> dates;                // 일자 목록 (예: ["09.11", "09.12", ...])
    private List<Long> dailyRequestsTrend;     // 일별 예측 요청수 트렌드
    private List<Integer> dailyLatencyTrend;   // 일별 평균 지연시간 트렌드

    // 3. 개별 엔드포인트 인프라 및 서빙 상세 목록
    private List<EndpointItemDto> endpoints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointItemDto {
        private String endpointId;
        private String endpointName;
        private String deployedModelName;
        private String machineType;
        private String acceleratorType;
        private Integer acceleratorCount;
        private Integer minReplicaCount;
        private Integer maxReplicaCount;
        private Integer activeReplicaCount;
        private Long totalPredictRequests;
        private Integer avgLatencyMs;
        private Integer p95LatencyMs;
        private Double gpuUtilizationPercent;
        private Double cpuUtilizationPercent;
        private Double estimatedHourlyCost;
        private String status;
    }
}
