package com.example.infra.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GCP AI 서비스 직접 사용 (Direct AI Usage) 관제 DTO
 * Pre-trained API, Generative AI API, 파이프라인/학습/노트북 단발성 작업 지표
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectAiMetricsDto {

    // 0. 프로젝트 및 고객사 정보
    private String projectId;
    private String customerName;

    // 1. 요청 및 호출량 (일별 트렌드)
    private List<String> dates;
    private List<Long> inputTokensTrend;
    private List<Long> outputTokensTrend;
    private List<Long> pretrainedApiCallsTrend;

    // 1-2. 누적 합산 지표
    private Long totalInputTokens;
    private Long totalOutputTokens;
    private Long totalTokens;
    private Long totalPretrainedApiCalls;
    private Long visionApiCalls;
    private Long speechApiCalls;
    private Long translationApiCalls;
    private Long nlpApiCalls;

    // 2. 학습 및 단발성 워크로드 리소스
    private Double trainingNodeHours;          // Training Jobs 가동 시간 (hrs)
    private Integer pipelineRunsCount;         // Vertex Pipelines 실행 건수
    private Double workbenchUptimeHours;       // Workbench 인스턴스 가동 시간 (hrs)
    private Integer activeWorkbenchCount;      // 활성 Workbench 노트북 수

    // 3. Quota 및 모델 비율
    private Integer currentRpm;
    private Integer maxRpmQuota;
    private Double rpmQuotaUsagePercent;
    private Long currentTpd;
    private Long maxTpdQuota;
    private Double tpdQuotaUsagePercent;
    private Boolean quotaAlert;
    private Double geminiFlashRatio;           // Gemini Flash 비율 (%)
    private Double geminiProRatio;             // Gemini Pro 비율 (%)
    private Double claudeRatio;                // Claude 모델 비율 (%)
    private Double customModelRatio;           // 파인튜닝/커스텀 모델 비율 (%)

    // 4. 비용
    private Double estimatedApiCost;           // API 토큰 및 호출 비용 ($)
    private Double estimatedTrainingCost;      // 학습 및 파이프라인 비용 ($)
    private Double totalEstimatedDailyCost;    // 일일 총 비용 ($)
    private Double totalEstimatedMonthlyCost;  // 월간 환산 예상 비용 ($)

    private String lastUpdated;
}
