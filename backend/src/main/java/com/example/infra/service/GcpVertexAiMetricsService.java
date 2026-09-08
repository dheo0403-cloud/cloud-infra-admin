package com.example.infra.service;

import com.example.infra.dto.VertexAiMetricsDto;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * GCP Vertex AI & 생성형 AI 관제 메트릭 수집 및 집계 서비스
 * BigQuery daily_vertex_ai_metrics 테이블의 타겟 고객사 프로젝트 실데이터를 연월(Month)별로 동적 필터링 조회합니다.
 * 해당 월에 데이터가 없을 경우 임의의 더미 데이터 대신 명시적인 빈 데이터(Empty List)를 반환합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GcpVertexAiMetricsService {

    private final BigQuery bigQuery;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String hostProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    private static final String TABLE_NAME = "daily_vertex_ai_metrics";

    /**
     * 타겟 고객사 프로젝트 및 지정 연월 기준의 Vertex AI 운영 관제 메트릭 조회
     * @param targetProjectId 조회 대상 고객사 GCP 프로젝트 ID
     * @param targetYearMonth 조회 대상 연월 (예: 2026-08, 2026-09)
     */
    public VertexAiMetricsDto getVertexAiOperationsMetrics(String targetProjectId, String targetYearMonth) {
        String effectiveProjectId = (targetProjectId != null && !targetProjectId.trim().isEmpty())
                ? targetProjectId.trim()
                : "hcompany-485701"; // 기본 고객사 프로젝트

        String effectiveYearMonth = (targetYearMonth != null && targetYearMonth.matches("^\\d{4}-\\d{2}$"))
                ? targetYearMonth.trim()
                : YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        log.info("[VERTEX-AI-METRICS] Fetching GenAI metrics for project `{}` and month `{}` from BigQuery {}.{}.{}",
                effectiveProjectId, effectiveYearMonth, hostProjectId, datasetName, TABLE_NAME);

        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try {
            // 1. 타겟 프로젝트 ID 및 지정 연월 기준 스냅샷 쿼리 (WHERE 절 월별 필터링)
            String query = String.format(
                "SELECT " +
                "  project_id, " +
                "  CAST(snapshot_date AS STRING) AS sdate, " +
                "  input_tokens, output_tokens, current_rpm, max_rpm_quota, " +
                "  current_tpd, max_tpd_quota, total_endpoints, active_endpoints, " +
                "  idle_endpoints, allocated_gpus, allocated_tpus, gpu_model, " +
                "  estimated_hourly_cost, gemini_flash_ratio, gemini_pro_ratio, " +
                "  fine_tuned_ratio, prompt_cache_hit_ratio, rate_limit_429_errors, " +
                "  safety_filter_blocks, avg_latency_ms " +
                "FROM `%s.%s.%s` " +
                "WHERE project_id = '%s' " +
                "  AND CAST(snapshot_date AS STRING) LIKE '%s%%' " +
                "ORDER BY snapshot_date ASC " +
                "LIMIT 7",
                hostProjectId, datasetName, TABLE_NAME, effectiveProjectId, effectiveYearMonth
            );

            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            List<FieldValueList> rows = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                rows.add(row);
            }

            // 2. 지정된 프로젝트/월 데이터가 존재할 경우 정상 DTO 반환
            if (!rows.isEmpty()) {
                String actualProjId = rows.get(0).get("project_id").isNull() ? effectiveProjectId : rows.get(0).get("project_id").getStringValue();
                log.info("[VERTEX-AI-METRICS] Successfully loaded {} rows for project `{}` ({}) from BigQuery `{}`",
                        rows.size(), actualProjId, effectiveYearMonth, TABLE_NAME);

                List<String> dates = new ArrayList<>();
                List<Long> inputTokens = new ArrayList<>();
                List<Long> outputTokens = new ArrayList<>();

                for (FieldValueList r : rows) {
                    String fullDate = r.get("sdate").getStringValue();
                    String shortDate = fullDate.length() >= 5 ? fullDate.substring(5, Math.min(10, fullDate.length())) : fullDate;
                    dates.add(shortDate);
                    inputTokens.add(r.get("input_tokens").isNull() ? 0L : r.get("input_tokens").getLongValue());
                    outputTokens.add(r.get("output_tokens").isNull() ? 0L : r.get("output_tokens").getLongValue());
                }

                FieldValueList latest = rows.get(rows.size() - 1);
                int currentRpm = latest.get("current_rpm").isNull() ? 0 : (int) latest.get("current_rpm").getLongValue();
                int maxRpmQuota = latest.get("max_rpm_quota").isNull() ? 1000 : (int) latest.get("max_rpm_quota").getLongValue();
                double rpmUsagePercent = maxRpmQuota > 0 ? Math.round(((double) currentRpm / maxRpmQuota * 100.0) * 10.0) / 10.0 : 0.0;

                long currentTpd = latest.get("current_tpd").isNull() ? 0L : latest.get("current_tpd").getLongValue();
                long maxTpdQuota = latest.get("max_tpd_quota").isNull() ? 4500000L : latest.get("max_tpd_quota").getLongValue();
                double tpdUsagePercent = maxTpdQuota > 0 ? Math.round(((double) currentTpd / maxTpdQuota * 100.0) * 10.0) / 10.0 : 0.0;
                boolean quotaAlert = tpdUsagePercent >= 80.0;

                int totalEndpoints = latest.get("total_endpoints").isNull() ? 0 : (int) latest.get("total_endpoints").getLongValue();
                int activeEndpoints = latest.get("active_endpoints").isNull() ? 0 : (int) latest.get("active_endpoints").getLongValue();
                int idleEndpoints = latest.get("idle_endpoints").isNull() ? 0 : (int) latest.get("idle_endpoints").getLongValue();
                int allocatedGpus = latest.get("allocated_gpus").isNull() ? 0 : (int) latest.get("allocated_gpus").getLongValue();
                int allocatedTpus = latest.get("allocated_tpus").isNull() ? 0 : (int) latest.get("allocated_tpus").getLongValue();
                String gpuModel = latest.get("gpu_model").isNull() ? "N/A" : latest.get("gpu_model").getStringValue();
                double hourlyCost = latest.get("estimated_hourly_cost").isNull() ? 0.0 : latest.get("estimated_hourly_cost").getDoubleValue();
                double monthlyCost = Math.round(hourlyCost * 24 * 30 * 10.0) / 10.0;

                double flashRatio = latest.get("gemini_flash_ratio").isNull() ? 0.0 : latest.get("gemini_flash_ratio").getDoubleValue();
                double proRatio = latest.get("gemini_pro_ratio").isNull() ? 0.0 : latest.get("gemini_pro_ratio").getDoubleValue();
                double fineTunedRatio = latest.get("fine_tuned_ratio").isNull() ? 0.0 : latest.get("fine_tuned_ratio").getDoubleValue();
                double cacheHitRatio = latest.get("prompt_cache_hit_ratio").isNull() ? 0.0 : latest.get("prompt_cache_hit_ratio").getDoubleValue();

                int rateLimit429Errors = latest.get("rate_limit_429_errors").isNull() ? 0 : (int) latest.get("rate_limit_429_errors").getLongValue();
                int safetyFilterBlocks = latest.get("safety_filter_blocks").isNull() ? 0 : (int) latest.get("safety_filter_blocks").getLongValue();
                int avgLatencyMs = latest.get("avg_latency_ms").isNull() ? 0 : (int) latest.get("avg_latency_ms").getLongValue();

                return VertexAiMetricsDto.builder()
                        .projectId(actualProjId)
                        .customerName("고객사 GCP 프로젝트")
                        .dates(dates)
                        .inputTokensTrend(inputTokens)
                        .outputTokensTrend(outputTokens)
                        .rpmQuotaUsagePercent(rpmUsagePercent)
                        .tpdQuotaUsagePercent(tpdUsagePercent)
                        .currentRpm(currentRpm)
                        .maxRpmQuota(maxRpmQuota)
                        .currentTpd(currentTpd)
                        .maxTpdQuota(maxTpdQuota)
                        .quotaAlert(quotaAlert)
                        .totalEndpoints(totalEndpoints)
                        .activeEndpoints(activeEndpoints)
                        .idleEndpoints(idleEndpoints)
                        .allocatedGpus(allocatedGpus)
                        .allocatedTpus(allocatedTpus)
                        .gpuModel(gpuModel)
                        .estimatedHourlyCost(hourlyCost)
                        .estimatedMonthlyCost(monthlyCost)
                        .geminiFlashRatio(flashRatio)
                        .geminiProRatio(proRatio)
                        .fineTunedRatio(fineTunedRatio)
                        .promptCacheHitRatio(cacheHitRatio)
                        .rateLimit429Errors(rateLimit429Errors)
                        .safetyFilterBlocks(safetyFilterBlocks)
                        .avgLatencyMs(avgLatencyMs)
                        .lastUpdated(LocalDateTime.now().format(timeFormatter))
                        .build();
            } else {
                log.info("[VERTEX-AI-METRICS] No BigQuery records for project `{}` in month `{}`. Returning empty data.",
                        effectiveProjectId, effectiveYearMonth);
                return createEmptyMetrics(effectiveProjectId, timeFormatter);
            }
        } catch (Exception e) {
            log.warn("[VERTEX-AI-METRICS] BigQuery query notice: {}. Returning empty data for {}.",
                    e.getMessage(), effectiveYearMonth);
            return createEmptyMetrics(effectiveProjectId, timeFormatter);
        }
    }

    /**
     * 데이터가 없는 월에 대한 명시적 빈 데이터 DTO 반환
     */
    private VertexAiMetricsDto createEmptyMetrics(String targetProjectId, DateTimeFormatter timeFormatter) {
        return VertexAiMetricsDto.builder()
                .projectId(targetProjectId)
                .customerName("고객사 GCP 프로젝트")
                .dates(new ArrayList<>())
                .inputTokensTrend(new ArrayList<>())
                .outputTokensTrend(new ArrayList<>())
                .rpmQuotaUsagePercent(0.0)
                .tpdQuotaUsagePercent(0.0)
                .currentRpm(0)
                .maxRpmQuota(1000)
                .currentTpd(0L)
                .maxTpdQuota(4500000L)
                .quotaAlert(false)
                .totalEndpoints(0)
                .activeEndpoints(0)
                .idleEndpoints(0)
                .allocatedGpus(0)
                .allocatedTpus(0)
                .gpuModel("N/A")
                .estimatedHourlyCost(0.0)
                .estimatedMonthlyCost(0.0)
                .geminiFlashRatio(0.0)
                .geminiProRatio(0.0)
                .fineTunedRatio(0.0)
                .promptCacheHitRatio(0.0)
                .rateLimit429Errors(0)
                .safetyFilterBlocks(0)
                .avgLatencyMs(0)
                .lastUpdated(LocalDateTime.now().format(timeFormatter))
                .build();
    }
}
