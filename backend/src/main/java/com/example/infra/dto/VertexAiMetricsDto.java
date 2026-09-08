package com.example.infra.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GCP Vertex AI & 생성형 AI 리소스, 토큰 사용량, Quota 및 비용 관제 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VertexAiMetricsDto {

    // 1. 일별 토큰 사용량 트렌드 (최근 7일)
    private List<String> dates;
    private List<Long> inputTokensTrend;
    private List<Long> outputTokensTrend;

    // 2. API Quota (할당량) 및 소진율
    private Double rpmQuotaUsagePercent;   // 분당 요청수 소진율 (%)
    private Double tpdQuotaUsagePercent;   // 일일 토큰수 소진율 (%)
    private Integer currentRpm;            // 현재 분당 요청수
    private Integer maxRpmQuota;           // 최대 허용 RPM Quota
    private Long currentTpd;               // 현재 일일 토큰 소진량
    private Long maxTpdQuota;              // 최대 허용 TPD Quota
    private Boolean quotaAlert;            // 80% 이상 임계치 초과 경보 여부

    // 3. 엔드포인트 및 가속기(GPU/TPU) 리소스 상태
    private Integer totalEndpoints;        // 총 엔드포인트 수
    private Integer activeEndpoints;       // 활성 엔드포인트 수
    private Integer idleEndpoints;         // 유휴(Idle) 상태 엔드포인트 수 (비용 누수 위험)
    private Integer allocatedGpus;         // 할당된 GPU 수량
    private Integer allocatedTpus;         // 할당된 TPU 수량
    private String gpuModel;               // 주력 GPU 모델 (예: NVIDIA L4 × 2)
    private Double estimatedHourlyCost;    // 시간당 예상 인프라 비용 ($)
    private Double estimatedMonthlyCost;   // 월간 환산 예상 비용 ($)

    // 4. 주력 AI 모델 호출 비중 (%)
    private Double geminiFlashRatio;       // Gemini 1.5/2.5 Flash 저비용 모델 비율 (%)
    private Double geminiProRatio;         // Gemini 1.5 Pro 고비용 모델 비율 (%)
    private Double fineTunedRatio;         // 커스텀/파인튜닝 모델 비율 (%)
    private Double promptCacheHitRatio;    // 프롬프트 캐싱(Context Caching) 적용률 (%)

    // 5. 안정성 및 보안 관제
    private Integer rateLimit429Errors;    // 429 Too Many Requests 발생 건수
    private Integer safetyFilterBlocks;    // Safety Settings 정책 위반 차단 프롬프트 건수
    private Integer avgLatencyMs;          // 평균 API 응답 지연 시간 (ms)
    private String lastUpdated;            // 지표 갱신 일시
}
