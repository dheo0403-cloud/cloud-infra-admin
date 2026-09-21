package com.example.infra.service;

import com.example.infra.dto.DirectAiMetricsDto;
import com.example.infra.dto.EndpointServingMetricsDto;
import com.example.infra.dto.VertexAiMetricsDto;
import com.example.infra.dto.VertexEndpointMetricsDto;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GCP AI 서비스 직접 사용 (Direct AI Usage) & 엔드포인트 서빙 (Endpoint Serving) 듀얼 관제 메트릭 집계 서비스 (4개월 통합 추이)
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

    private static final String DIRECT_AI_TABLE = "daily_direct_ai_metrics";
    private static final String ENDPOINT_SERVING_TABLE = "daily_endpoint_serving_metrics";
    private static final String MONTHLY_DIRECT_AI_SUMMARY_TABLE = "monthly_direct_ai_summary";
    private static final String MONTHLY_ENDPOINT_SERVING_SUMMARY_TABLE = "monthly_endpoint_serving_summary";

    /**
     * 타겟 고객사 프로젝트 및 지정 연월 기준의 Direct AI Usage (직접 사용) 관제 메트릭 조회 (기본 4개월 추이)
     */
    public DirectAiMetricsDto getDirectAiOperationsMetrics(String targetProjectId, String targetYearMonth) {
        return getDirectAiOperationsMetrics(targetProjectId, targetYearMonth, "monthly");
    }

    /**
     * 타겟 고객사 프로젝트 및 지정 연월 기준의 Direct AI Usage 관제 메트릭 조회 (4개월 월별 집계)
     */
    public DirectAiMetricsDto getDirectAiOperationsMetrics(String targetProjectId, String targetYearMonth, String period) {
        if (targetProjectId == null || targetProjectId.trim().isEmpty()) {
            return createEmptyDirectAiMetrics("");
        }

        String effectiveProjectId = targetProjectId.trim();
        String effectiveYearMonth = (targetYearMonth != null && targetYearMonth.matches("^\\d{4}-\\d{2}$"))
                ? targetYearMonth.trim()
                : YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        log.info("[DIRECT-AI-METRICS] Fetching Direct AI 4-month metrics for project `{}` and target month `{}`",
                effectiveProjectId, effectiveYearMonth);

        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // 4개월(YY.MM) 및 YYYY-MM 배열 생성 (예: 2026-09 -> 2026-06, 2026-07, 2026-08, 2026-09)
        YearMonth targetYm = YearMonth.parse(effectiveYearMonth);
        List<String> yyyyMmList = new ArrayList<>();
        List<String> labelList = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            YearMonth ym = targetYm.minusMonths(i);
            yyyyMmList.add(ym.format(DateTimeFormatter.ofPattern("yyyy-MM")));
            labelList.add(ym.format(DateTimeFormatter.ofPattern("yy.MM")));
        }

        String startYm = yyyyMmList.get(0);
        String endYm = yyyyMmList.get(3);

        try {
            // 1. 4개월 월별 요약 테이블(Summary) 우선 조회 쿼리 (없을 경우 일일 Raw 테이블 Fallback)
            Map<String, FieldValueList> monthDataMap = new HashMap<>();
            String customerName = "고객사 GCP 프로젝트";

            try {
                String summarySql = String.format(
                    "SELECT " +
                    "  report_year_month AS ym, " +
                    "  customer_name, " +
                    "  monthly_input_tokens AS monthly_in_tokens, " +
                    "  monthly_output_tokens AS monthly_out_tokens, " +
                    "  monthly_total_tokens AS monthly_tot_tokens, " +
                    "  monthly_pretrained_calls AS monthly_pre_calls, " +
                    "  monthly_vision_calls AS monthly_vision, " +
                    "  monthly_speech_calls AS monthly_speech, " +
                    "  monthly_translation_calls AS monthly_trans, " +
                    "  monthly_nlp_calls AS monthly_nlp " +
                    "FROM `%s.%s.%s` " +
                    "WHERE project_id = '%s' AND report_year_month BETWEEN '%s' AND '%s' " +
                    "ORDER BY ym ASC",
                    hostProjectId, datasetName, MONTHLY_DIRECT_AI_SUMMARY_TABLE, effectiveProjectId, startYm, endYm
                );
                TableResult summaryResult = bigQuery.query(QueryJobConfiguration.newBuilder(summarySql).build());
                for (FieldValueList row : summaryResult.iterateAll()) {
                    String ym = row.get("ym").getStringValue();
                    monthDataMap.put(ym, row);
                    if (!row.get("customer_name").isNull()) {
                        customerName = row.get("customer_name").getStringValue();
                    }
                }
            } catch (Exception ex) {
                log.debug("Summary table query skipped, falling back to raw table: {}", ex.getMessage());
            }

            // 요약 테이블에 데이터가 없는 월은 일일 Raw 테이블에서 실시간 집계 Fallback
            if (monthDataMap.size() < 4) {
                String monthlySql = String.format(
                    "SELECT " +
                    "  SUBSTR(CAST(snapshot_date AS STRING), 1, 7) AS ym, " +
                    "  MAX(customer_name) AS customer_name, " +
                    "  SUM(input_tokens) AS monthly_in_tokens, " +
                    "  SUM(output_tokens) AS monthly_out_tokens, " +
                    "  SUM(total_tokens) AS monthly_tot_tokens, " +
                    "  SUM(pretrained_api_calls) AS monthly_pre_calls, " +
                    "  SUM(vision_api_calls) AS monthly_vision, " +
                    "  SUM(speech_api_calls) AS monthly_speech, " +
                    "  SUM(translation_api_calls) AS monthly_trans, " +
                    "  SUM(nlp_api_calls) AS monthly_nlp " +
                    "FROM `%s.%s.%s` " +
                    "WHERE project_id = '%s' AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) BETWEEN '%s' AND '%s' " +
                    "GROUP BY ym ORDER BY ym ASC",
                    hostProjectId, datasetName, DIRECT_AI_TABLE, effectiveProjectId, startYm, endYm
                );

                TableResult monthlyResult = bigQuery.query(QueryJobConfiguration.newBuilder(monthlySql).build());
                for (FieldValueList row : monthlyResult.iterateAll()) {
                    String ym = row.get("ym").getStringValue();
                    monthDataMap.putIfAbsent(ym, row);
                    if (!row.get("customer_name").isNull()) {
                        customerName = row.get("customer_name").getStringValue();
                    }
                }
            }

            List<Long> inputTokensTrend = new ArrayList<>();
            List<Long> outputTokensTrend = new ArrayList<>();
            List<Long> pretrainedCallsTrend = new ArrayList<>();

            long targetMonthInTok = 0;
            long targetMonthOutTok = 0;
            long targetMonthPre = 0;
            long targetMonthVision = 0;
            long targetMonthSpeech = 0;
            long targetMonthTrans = 0;
            long targetMonthNlp = 0;

            for (int i = 0; i < 4; i++) {
                String ym = yyyyMmList.get(i);
                if (monthDataMap.containsKey(ym)) {
                    FieldValueList r = monthDataMap.get(ym);
                    long inTok = r.get("monthly_in_tokens").isNull() ? 0L : r.get("monthly_in_tokens").getLongValue();
                    long outTok = r.get("monthly_out_tokens").isNull() ? 0L : r.get("monthly_out_tokens").getLongValue();
                    long preCalls = r.get("monthly_pre_calls").isNull() ? 0L : r.get("monthly_pre_calls").getLongValue();

                    inputTokensTrend.add(inTok);
                    outputTokensTrend.add(outTok);
                    pretrainedCallsTrend.add(preCalls);

                    if (i == 3) { // 기준월(당월)
                        targetMonthInTok = inTok;
                        targetMonthOutTok = outTok;
                        targetMonthPre = preCalls;
                        targetMonthVision = r.get("monthly_vision").isNull() ? 0L : r.get("monthly_vision").getLongValue();
                        targetMonthSpeech = r.get("monthly_speech").isNull() ? 0L : r.get("monthly_speech").getLongValue();
                        targetMonthTrans = r.get("monthly_trans").isNull() ? 0L : r.get("monthly_trans").getLongValue();
                        targetMonthNlp = r.get("monthly_nlp").isNull() ? 0L : r.get("monthly_nlp").getLongValue();
                    }
                } else {
                    inputTokensTrend.add(0L);
                    outputTokensTrend.add(0L);
                    pretrainedCallsTrend.add(0L);
                }
            }

            // 2. 기준월 최신 스냅샷 상세 쿼리 (모델 비중, Quota, 리소스, 비용)
            String latestSql = String.format(
                "SELECT * FROM (" +
                "  SELECT *, ROW_NUMBER() OVER(PARTITION BY project_id ORDER BY snapshot_date DESC, created_at DESC) AS rn " +
                "  FROM `%s.%s.%s` " +
                "  WHERE project_id = '%s' AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) = '%s' " +
                ") WHERE rn = 1",
                hostProjectId, datasetName, DIRECT_AI_TABLE, effectiveProjectId, effectiveYearMonth
            );

            TableResult latestRes = bigQuery.query(QueryJobConfiguration.newBuilder(latestSql).build());
            int currentRpm = 45;
            int maxRpmQuota = 1000;
            double rpmUsagePercent = 4.5;
            long currentTpd = targetMonthInTok + targetMonthOutTok;
            long maxTpdQuota = 4500000L;
            double tpdUsagePercent = Math.round(((double) currentTpd / maxTpdQuota * 100.0) * 10.0) / 10.0;
            boolean quotaAlert = false;
            double trainingHours = 4.0;
            int pipelineRuns = 2;
            double workbenchUptime = 12.0;
            int activeWorkbench = 1;
            double flashRatio = 65.0;
            double proRatio = 25.0;
            double claudeRatio = 10.0;
            double customRatio = 0.0;
            double estimatedApiCost = Math.round(((targetMonthInTok * 0.0000005) + (targetMonthOutTok * 0.0000015) + (targetMonthPre * 0.0015)) * 100.0) / 10.0;
            double estimatedTrainingCost = Math.round((trainingHours * 0.45 + pipelineRuns * 0.15 + workbenchUptime * 0.08) * 100.0) / 100.0;
            double totalDailyCost = Math.round((estimatedApiCost + estimatedTrainingCost) * 100.0) / 100.0;
            double totalMonthlyCost = Math.round(totalDailyCost * 30.0 * 100.0) / 100.0;

            for (FieldValueList latest : latestRes.iterateAll()) {
                currentRpm = latest.get("current_rpm").isNull() ? currentRpm : (int) latest.get("current_rpm").getLongValue();
                maxRpmQuota = latest.get("max_rpm_quota").isNull() ? maxRpmQuota : (int) latest.get("max_rpm_quota").getLongValue();
                rpmUsagePercent = maxRpmQuota > 0 ? Math.round(((double) currentRpm / maxRpmQuota * 100.0) * 10.0) / 10.0 : 0.0;
                trainingHours = latest.get("training_node_hours").isNull() ? trainingHours : latest.get("training_node_hours").getDoubleValue();
                pipelineRuns = latest.get("pipeline_runs_count").isNull() ? pipelineRuns : (int) latest.get("pipeline_runs_count").getLongValue();
                workbenchUptime = latest.get("workbench_uptime_hours").isNull() ? workbenchUptime : latest.get("workbench_uptime_hours").getDoubleValue();
                activeWorkbench = latest.get("active_workbench_count").isNull() ? activeWorkbench : (int) latest.get("active_workbench_count").getLongValue();
                flashRatio = latest.get("gemini_flash_ratio").isNull() ? flashRatio : latest.get("gemini_flash_ratio").getDoubleValue();
                proRatio = latest.get("gemini_pro_ratio").isNull() ? proRatio : latest.get("gemini_pro_ratio").getDoubleValue();
                claudeRatio = latest.get("claude_ratio").isNull() ? claudeRatio : latest.get("claude_ratio").getDoubleValue();
                customRatio = latest.get("custom_model_ratio").isNull() ? customRatio : latest.get("custom_model_ratio").getDoubleValue();
                estimatedApiCost = latest.get("estimated_api_cost").isNull() ? estimatedApiCost : latest.get("estimated_api_cost").getDoubleValue();
                estimatedTrainingCost = latest.get("estimated_training_cost").isNull() ? estimatedTrainingCost : latest.get("estimated_training_cost").getDoubleValue();
                totalDailyCost = latest.get("total_estimated_daily_cost").isNull() ? totalDailyCost : latest.get("total_estimated_daily_cost").getDoubleValue();
                totalMonthlyCost = Math.round(totalDailyCost * 30.0 * 100.0) / 100.0;
                quotaAlert = rpmUsagePercent >= 80.0 || tpdUsagePercent >= 80.0;
            }

            return DirectAiMetricsDto.builder()
                    .projectId(effectiveProjectId)
                    .customerName(customerName)
                    .dates(labelList)
                    .inputTokensTrend(inputTokensTrend)
                    .outputTokensTrend(outputTokensTrend)
                    .pretrainedApiCallsTrend(pretrainedCallsTrend)
                    .totalInputTokens(targetMonthInTok)
                    .totalOutputTokens(targetMonthOutTok)
                    .totalTokens(targetMonthInTok + targetMonthOutTok)
                    .totalPretrainedApiCalls(targetMonthPre)
                    .visionApiCalls(targetMonthVision)
                    .speechApiCalls(targetMonthSpeech)
                    .translationApiCalls(targetMonthTrans)
                    .nlpApiCalls(targetMonthNlp)
                    .trainingNodeHours(trainingHours)
                    .pipelineRunsCount(pipelineRuns)
                    .workbenchUptimeHours(workbenchUptime)
                    .activeWorkbenchCount(activeWorkbench)
                    .currentRpm(currentRpm)
                    .maxRpmQuota(maxRpmQuota)
                    .rpmQuotaUsagePercent(rpmUsagePercent)
                    .currentTpd(currentTpd)
                    .maxTpdQuota(maxTpdQuota)
                    .tpdQuotaUsagePercent(tpdUsagePercent)
                    .quotaAlert(quotaAlert)
                    .geminiFlashRatio(flashRatio)
                    .geminiProRatio(proRatio)
                    .claudeRatio(claudeRatio)
                    .customModelRatio(customRatio)
                    .estimatedApiCost(estimatedApiCost)
                    .estimatedTrainingCost(estimatedTrainingCost)
                    .totalEstimatedDailyCost(totalDailyCost)
                    .totalEstimatedMonthlyCost(totalMonthlyCost)
                    .lastUpdated(LocalDateTime.now().format(timeFormatter))
                    .build();

        } catch (Exception e) {
            log.warn("[DIRECT-AI-METRICS] BigQuery query notice: {}. Returning empty data for {}.", e.getMessage(), effectiveYearMonth);
            return createEmptyDirectAiMetrics(effectiveProjectId);
        }
    }

    private DirectAiMetricsDto createEmptyDirectAiMetrics(String targetProjectId) {
        return DirectAiMetricsDto.builder()
                .projectId(targetProjectId)
                .customerName("고객사 GCP 프로젝트")
                .dates(new ArrayList<>())
                .inputTokensTrend(new ArrayList<>())
                .outputTokensTrend(new ArrayList<>())
                .pretrainedApiCallsTrend(new ArrayList<>())
                .totalInputTokens(0L)
                .totalOutputTokens(0L)
                .totalTokens(0L)
                .totalPretrainedApiCalls(0L)
                .visionApiCalls(0L)
                .speechApiCalls(0L)
                .translationApiCalls(0L)
                .nlpApiCalls(0L)
                .trainingNodeHours(0.0)
                .pipelineRunsCount(0)
                .workbenchUptimeHours(0.0)
                .activeWorkbenchCount(0)
                .currentRpm(0)
                .maxRpmQuota(1000)
                .rpmQuotaUsagePercent(0.0)
                .currentTpd(0L)
                .maxTpdQuota(4500000L)
                .tpdQuotaUsagePercent(0.0)
                .quotaAlert(false)
                .geminiFlashRatio(0.0)
                .geminiProRatio(0.0)
                .claudeRatio(0.0)
                .customModelRatio(0.0)
                .estimatedApiCost(0.0)
                .estimatedTrainingCost(0.0)
                .totalEstimatedDailyCost(0.0)
                .totalEstimatedMonthlyCost(0.0)
                .lastUpdated(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .build();
    }

    /**
     * 타겟 고객사 프로젝트 및 지정 연월 기준의 Endpoint Serving (엔드포인트 서빙) 관제 메트릭 조회 (기본 4개월 추이)
     */
    public EndpointServingMetricsDto getEndpointServingOperationsMetrics(String targetProjectId, String targetYearMonth) {
        return getEndpointServingOperationsMetrics(targetProjectId, targetYearMonth, "monthly");
    }

    /**
     * 타겟 고객사 프로젝트 및 지정 연월 기준의 Endpoint Serving 관제 메트릭 조회 (4개월 월별 집계)
     */
    public EndpointServingMetricsDto getEndpointServingOperationsMetrics(String targetProjectId, String targetYearMonth, String period) {
        if (targetProjectId == null || targetProjectId.trim().isEmpty()) {
            return createEmptyEndpointServingMetrics("");
        }

        String effectiveProjectId = targetProjectId.trim();
        String effectiveYearMonth = (targetYearMonth != null && targetYearMonth.matches("^\\d{4}-\\d{2}$"))
                ? targetYearMonth.trim()
                : YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        log.info("[ENDPOINT-SERVING-METRICS] Fetching endpoint serving 4-month metrics for project `{}` and target month `{}`",
                effectiveProjectId, effectiveYearMonth);

        // 4개월(YY.MM) 및 YYYY-MM 배열 생성 (예: 2026-09 -> 2026-06, 2026-07, 2026-08, 2026-09)
        YearMonth targetYm = YearMonth.parse(effectiveYearMonth);
        List<String> yyyyMmList = new ArrayList<>();
        List<String> labelList = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            YearMonth ym = targetYm.minusMonths(i);
            yyyyMmList.add(ym.format(DateTimeFormatter.ofPattern("yyyy-MM")));
            labelList.add(ym.format(DateTimeFormatter.ofPattern("yy.MM")));
        }

        String startYm = yyyyMmList.get(0);
        String endYm = yyyyMmList.get(3);

        try {
            // 1. 4개월 월별 집계 요약 테이블 우선 조회 (없을 경우 일일 Raw 테이블 Fallback)
            Map<String, FieldValueList> monthDataMap = new HashMap<>();

            try {
                String summarySql = String.format(
                    "SELECT " +
                    "  report_year_month AS ym, " +
                    "  SUM(monthly_total_requests) AS monthly_requests, " +
                    "  AVG(avg_latency_ms) AS monthly_avg_latency, " +
                    "  AVG(avg_qps) AS monthly_qps " +
                    "FROM `%s.%s.%s` " +
                    "WHERE project_id = '%s' AND report_year_month BETWEEN '%s' AND '%s' " +
                    "GROUP BY ym ORDER BY ym ASC",
                    hostProjectId, datasetName, MONTHLY_ENDPOINT_SERVING_SUMMARY_TABLE, effectiveProjectId, startYm, endYm
                );
                TableResult summaryRes = bigQuery.query(QueryJobConfiguration.newBuilder(summarySql).build());
                for (FieldValueList row : summaryRes.iterateAll()) {
                    String ym = row.get("ym").getStringValue();
                    monthDataMap.put(ym, row);
                }
            } catch (Exception ex) {
                log.debug("Endpoint summary table query notice: {}", ex.getMessage());
            }

            if (monthDataMap.size() < 4) {
                String monthlySql = String.format(
                    "SELECT " +
                    "  SUBSTR(CAST(snapshot_date AS STRING), 1, 7) AS ym, " +
                    "  SUM(total_requests) AS monthly_requests, " +
                    "  AVG(avg_latency_ms) AS monthly_avg_latency, " +
                    "  AVG(qps) AS monthly_qps " +
                    "FROM `%s.%s.%s` " +
                    "WHERE project_id = '%s' AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) BETWEEN '%s' AND '%s' " +
                    "GROUP BY ym ORDER BY ym ASC",
                    hostProjectId, datasetName, ENDPOINT_SERVING_TABLE, effectiveProjectId, startYm, endYm
                );

                TableResult monthlyRes = bigQuery.query(QueryJobConfiguration.newBuilder(monthlySql).build());
                for (FieldValueList row : monthlyRes.iterateAll()) {
                    String ym = row.get("ym").getStringValue();
                    monthDataMap.putIfAbsent(ym, row);
                }
            }

            List<Long> monthlyRequestsTrend = new ArrayList<>();
            List<Integer> monthlyLatenciesTrend = new ArrayList<>();
            List<Double> monthlyQpsTrend = new ArrayList<>();

            for (int i = 0; i < 4; i++) {
                String ym = yyyyMmList.get(i);
                if (monthDataMap.containsKey(ym)) {
                    FieldValueList row = monthDataMap.get(ym);
                    monthlyRequestsTrend.add(row.get("monthly_requests").getLongValue());
                    monthlyLatenciesTrend.add((int) row.get("monthly_avg_latency").getDoubleValue());
                    monthlyQpsTrend.add(Math.round(row.get("monthly_qps").getDoubleValue() * 100.0) / 100.0);
                } else {
                    monthlyRequestsTrend.add(0L);
                    monthlyLatenciesTrend.add(0);
                    monthlyQpsTrend.add(0.0);
                }
            }

            // 2. 최신 스냅샷 기준 개별 엔드포인트 목록 쿼리
            String endpointSql = String.format(
                "SELECT * FROM (" +
                "  SELECT *, ROW_NUMBER() OVER(PARTITION BY endpoint_id ORDER BY snapshot_date DESC, created_at DESC) as rn " +
                "  FROM `%s.%s.%s` " +
                "  WHERE project_id = '%s' AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) = '%s' " +
                ") WHERE rn = 1 ORDER BY endpoint_name ASC",
                hostProjectId, datasetName, ENDPOINT_SERVING_TABLE, effectiveProjectId, effectiveYearMonth
            );

            TableResult epRes = bigQuery.query(QueryJobConfiguration.newBuilder(endpointSql).build());
            List<EndpointServingMetricsDto.EndpointDetailDto> endpointItems = new ArrayList<>();
            String customerName = "고객사 GCP 프로젝트";
            long totalRequestsTargetMonth = monthlyRequestsTrend.get(3);
            long weightedLatencySum = 0;
            int maxP95 = 0;
            int maxP99 = 0;
            double totalHourlyCost = 0.0;
            int activeCount = 0;
            int totalGpuCount = 0;
            long error4xxSum = 0;
            long error5xxSum = 0;
            long totalVectorQueries = 0;
            long totalVectorUpdates = 0;

            for (FieldValueList row : epRes.iterateAll()) {
                if (!row.get("customer_name").isNull()) {
                    customerName = row.get("customer_name").getStringValue();
                }
                long reqs = row.get("total_requests").getLongValue();
                int avgLat = (int) row.get("avg_latency_ms").getLongValue();
                int p95Lat = (int) row.get("p95_latency_ms").getLongValue();
                int p99Lat = (int) row.get("p99_latency_ms").getLongValue();
                double hourlyCost = row.get("hourly_serving_cost").getDoubleValue();
                double monthlyCost = row.get("monthly_serving_cost").getDoubleValue();
                String status = row.get("status").getStringValue();
                int gpuCount = (int) row.get("accelerator_count").getLongValue();

                if ("ACTIVE".equalsIgnoreCase(status)) activeCount++;
                totalGpuCount += gpuCount;
                weightedLatencySum += (reqs * avgLat);
                if (p95Lat > maxP95) maxP95 = p95Lat;
                if (p99Lat > maxP99) maxP99 = p99Lat;
                totalHourlyCost += hourlyCost;
                error4xxSum += row.get("error_count_4xx").getLongValue();
                error5xxSum += row.get("error_count_5xx").getLongValue();
                if (!row.get("vector_search_queries").isNull()) totalVectorQueries += row.get("vector_search_queries").getLongValue();
                if (!row.get("vector_search_updates").isNull()) totalVectorUpdates += row.get("vector_search_updates").getLongValue();

                endpointItems.add(EndpointServingMetricsDto.EndpointDetailDto.builder()
                        .endpointId(row.get("endpoint_id").getStringValue())
                        .endpointName(row.get("endpoint_name").getStringValue())
                        .deployedModelId(row.get("deployed_model_id").getStringValue())
                        .deployedModelName(row.get("deployed_model_name").getStringValue())
                        .machineType(row.get("machine_type").getStringValue())
                        .acceleratorType(row.get("accelerator_type").getStringValue())
                        .acceleratorCount(gpuCount)
                        .minReplicas((int) row.get("min_replicas").getLongValue())
                        .maxReplicas((int) row.get("max_replicas").getLongValue())
                        .currentReplicas((int) row.get("current_replicas").getLongValue())
                        .totalRequests(reqs)
                        .qps(row.get("qps").getDoubleValue())
                        .avgLatencyMs(avgLat)
                        .p95LatencyMs(p95Lat)
                        .p99LatencyMs(p99Lat)
                        .errorCount4xx(row.get("error_count_4xx").getLongValue())
                        .errorCount5xx(row.get("error_count_5xx").getLongValue())
                        .errorRate4xx(row.get("error_rate_4xx_percent").getDoubleValue())
                        .errorRate5xx(row.get("error_rate_5xx_percent").getDoubleValue())
                        .gpuUtilizationPercent(row.get("gpu_utilization_percent").getDoubleValue())
                        .cpuUtilizationPercent(row.get("cpu_utilization_percent").getDoubleValue())
                        .nodeUptimeHours(row.get("node_uptime_hours").getDoubleValue())
                        .endpointNodeHours(row.get("endpoint_node_hours").getDoubleValue())
                        .hourlyCost(hourlyCost)
                        .monthlyCost(monthlyCost)
                        .status(status)
                        .build());
            }

            if (endpointItems.isEmpty()) {
                return createEmptyEndpointServingMetrics(effectiveProjectId);
            }

            int overallAvgLatency = totalRequestsTargetMonth > 0 ? (int) (weightedLatencySum / Math.max(1, endpointItems.size())) : 0;
            double err4xxRate = totalRequestsTargetMonth > 0 ? Math.round(((double) error4xxSum / totalRequestsTargetMonth * 100.0) * 10.0) / 10.0 : 0.0;
            double err5xxRate = totalRequestsTargetMonth > 0 ? Math.round(((double) error5xxSum / totalRequestsTargetMonth * 100.0) * 10.0) / 10.0 : 0.0;
            double successRate = Math.max(0.0, Math.round((100.0 - err4xxRate - err5xxRate) * 10.0) / 10.0);
            double monthlyCost = Math.round(totalHourlyCost * 24 * 30 * 10.0) / 10.0;
            double currentQps = Math.round((totalRequestsTargetMonth / (30.0 * 86400.0)) * 100.0) / 100.0;

            return EndpointServingMetricsDto.builder()
                    .projectId(effectiveProjectId)
                    .customerName(customerName)
                    .totalRequests7d(totalRequestsTargetMonth)
                    .currentQps(currentQps)
                    .avgLatencyMs(overallAvgLatency)
                    .p95LatencyMs(maxP95)
                    .p99LatencyMs(maxP99)
                    .errorRate4xxPercent(err4xxRate)
                    .errorRate5xxPercent(err5xxRate)
                    .successRatePercent(successRate)
                    .vectorSearchQueries(totalVectorQueries)
                    .vectorSearchUpdates(totalVectorUpdates)
                    .totalEndpoints(endpointItems.size())
                    .activeEndpoints(activeCount)
                    .totalAllocatedGpus(totalGpuCount)
                    .totalEstimatedHourlyCost(Math.round(totalHourlyCost * 100.0) / 100.0)
                    .totalEstimatedMonthlyCost(monthlyCost)
                    .dates(labelList)
                    .dailyRequestsTrend(monthlyRequestsTrend)
                    .dailyLatencyTrend(monthlyLatenciesTrend)
                    .dailyQpsTrend(monthlyQpsTrend)
                    .endpoints(endpointItems)
                    .build();

        } catch (Exception e) {
            log.warn("[ENDPOINT-SERVING-METRICS] Notice querying endpoint serving metrics: {}", e.getMessage());
            return createEmptyEndpointServingMetrics(effectiveProjectId);
        }
    }

    private EndpointServingMetricsDto createEmptyEndpointServingMetrics(String targetProjectId) {
        return EndpointServingMetricsDto.builder()
                .projectId(targetProjectId)
                .customerName("고객사 GCP 프로젝트")
                .totalRequests7d(0L)
                .currentQps(0.0)
                .avgLatencyMs(0)
                .p95LatencyMs(0)
                .p99LatencyMs(0)
                .errorRate4xxPercent(0.0)
                .errorRate5xxPercent(0.0)
                .successRatePercent(100.0)
                .vectorSearchQueries(0L)
                .vectorSearchUpdates(0L)
                .totalEndpoints(0)
                .activeEndpoints(0)
                .totalAllocatedGpus(0)
                .totalEstimatedHourlyCost(0.0)
                .totalEstimatedMonthlyCost(0.0)
                .dates(new ArrayList<>())
                .dailyRequestsTrend(new ArrayList<>())
                .dailyLatencyTrend(new ArrayList<>())
                .dailyQpsTrend(new ArrayList<>())
                .endpoints(new ArrayList<>())
                .build();
    }

    // Legacy Helpers
    public VertexAiMetricsDto getVertexAiOperationsMetrics(String targetProjectId, String targetYearMonth) {
        DirectAiMetricsDto d = getDirectAiOperationsMetrics(targetProjectId, targetYearMonth);
        return VertexAiMetricsDto.builder()
                .projectId(d.getProjectId())
                .customerName(d.getCustomerName())
                .dates(d.getDates())
                .inputTokensTrend(d.getInputTokensTrend())
                .outputTokensTrend(d.getOutputTokensTrend())
                .geminiFlashRatio(d.getGeminiFlashRatio())
                .geminiProRatio(d.getGeminiProRatio())
                .estimatedHourlyCost(d.getEstimatedTrainingCost())
                .estimatedMonthlyCost(d.getTotalEstimatedMonthlyCost())
                .lastUpdated(d.getLastUpdated())
                .build();
    }

    public VertexEndpointMetricsDto getVertexEndpointOperationsMetrics(String targetProjectId, String targetYearMonth) {
        EndpointServingMetricsDto s = getEndpointServingOperationsMetrics(targetProjectId, targetYearMonth);
        List<VertexEndpointMetricsDto.EndpointItemDto> items = new ArrayList<>();
        if (s.getEndpoints() != null) {
            for (EndpointServingMetricsDto.EndpointDetailDto ep : s.getEndpoints()) {
                items.add(VertexEndpointMetricsDto.EndpointItemDto.builder()
                        .endpointId(ep.getEndpointId())
                        .endpointName(ep.getEndpointName())
                        .deployedModelName(ep.getDeployedModelName())
                        .machineType(ep.getMachineType())
                        .acceleratorType(ep.getAcceleratorType())
                        .acceleratorCount(ep.getAcceleratorCount())
                        .minReplicaCount(ep.getMinReplicas())
                        .maxReplicaCount(ep.getMaxReplicas())
                        .activeReplicaCount(ep.getCurrentReplicas())
                        .totalPredictRequests(ep.getTotalRequests())
                        .avgLatencyMs(ep.getAvgLatencyMs())
                        .p95LatencyMs(ep.getP95LatencyMs())
                        .gpuUtilizationPercent(ep.getGpuUtilizationPercent())
                        .cpuUtilizationPercent(ep.getCpuUtilizationPercent())
                        .estimatedHourlyCost(ep.getHourlyCost())
                        .status(ep.getStatus())
                        .build());
            }
        }
        return VertexEndpointMetricsDto.builder()
                .projectId(s.getProjectId())
                .customerName(s.getCustomerName())
                .totalPredictRequests7d(s.getTotalRequests7d())
                .avgLatencyMs(s.getAvgLatencyMs())
                .p95LatencyMs(s.getP95LatencyMs())
                .successRatePercent(s.getSuccessRatePercent())
                .totalEndpoints(s.getTotalEndpoints())
                .activeEndpoints(s.getActiveEndpoints())
                .totalEstimatedHourlyCost(s.getTotalEstimatedHourlyCost())
                .totalEstimatedMonthlyCost(s.getTotalEstimatedMonthlyCost())
                .dates(s.getDates())
                .dailyRequestsTrend(s.getDailyRequestsTrend())
                .dailyLatencyTrend(s.getDailyLatencyTrend())
                .endpoints(items)
                .build();
    }
}
