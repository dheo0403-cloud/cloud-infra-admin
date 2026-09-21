package com.example.infra.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BigQuery 성능 및 비용 최적화 분석 관제 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BigQueryOptimizationDto {

    private String projectId;
    private String customerName;
    private String targetYearMonth;

    // 1. 월별 리소스 및 스토리지 현황 (트렌드 모니터링)
    private List<String> dates;                          // 최근 4개월 (e.g. 26.06, 26.07, 26.08, 26.09)
    private List<Double> dataProcessedTbTrend;          // 4개월 데이터 처리량 (TB) 추이
    private List<Long> jobCountTrend;                   // 4개월 Job Count 추이
    private double currentMonthProcessedTb;             // 당월 데이터 처리량 (TB)
    private long currentMonthJobCount;                  // 당월 총 실행 Job 수

    // 스토리지 용량 (GB/TB)
    private double totalLogicalStorageGb;               // 활성 스토리지 (논리적 스토리지)
    private double totalPhysicalStorageGb;              // 장기 스토리지 (물리적 스토리지)
    private double totalPhysicalStorageTb;              // 물리적 스토리지 (TB)

    // 2. 고비용 쿼리 분석 (TOP 10 비용 최적화)
    private List<BigQueryJobItemDto> highCostQueries;

    // 3. 쿼리 성능 및 병목 현상 분석 (슬롯 & 장기 실행 TOP 10)
    private double maxSlotUsage;                        // 당월 최대 슬롯 사용량
    private double minSlotUsage;                        // 당월 최소 슬롯 사용량
    private double avgSlotUsage;                        // 당월 평균 슬롯 사용량
    private String slotHealthStatus;                    // 슬롯 상태 (정상 / 주의 / 병목감지)
    private List<BigQueryJobItemDto> longDurationQueries;

    private String lastUpdated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BigQueryJobItemDto {
        private int rank;
        private String createdDate;
        private String jobId;
        private String userEmail;
        private String statementType;
        private String query;
        private double bytesProcessedGb;
        private double estimatedCostUsd;
        private long totalSlotMs;
        private double executionTimeSeconds;
        private String executionDurationFormatted;
        private double jobAverageSlots;
    }
}
