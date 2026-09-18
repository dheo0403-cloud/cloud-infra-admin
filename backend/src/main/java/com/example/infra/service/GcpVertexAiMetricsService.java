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
import java.util.List;

/**
 * GCP AI 서비스 직접 사용 (Direct AI Usage) & 엔드포인트 서빙 (Endpoint Serving) 듀얼 관제 메트릭 집계 서비스
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

    /**
     * 타겟 고객사 프로젝트 및 지정 연월 기준의 Direct AI Usage (직접 사용) 관제 메트릭 조회
     */
    public DirectAiMetricsDto getDirectAiOperationsMetrics(String targetProjectId, String targetYearMonth) {
        if (targetProjectId == null || targetProjectId.trim().isEmpty()) {
            return createEmptyDirectAiMetrics("");
        }

        String effectiveProjectId = targetProjectId.trim();
        String effectiveYearMonth = (targetYearMonth != null && targetYearMonth.matches("^\\d{4}-\\d{2}$"))
                ? targetYearMonth.trim()
                : YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        log.info("[DIRECT-AI-METRICS] Fetching Direct AI metrics for project `{}` and month `{}` from BigQuery {}.{}.{}",
                effectiveProjectId, effectiveYearMonth, hostProjectId, datasetName, DIRECT_AI_TABLE);

        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try {
            String query = String.format(
                "SELECT " +
                "  project_id, customer_name, " +
                "  CAST(snapshot_date AS STRING) AS sdate, " +
                "  input_tokens, output_tokens, total_tokens, pretrained_api_calls, " +
                "  vision_api_calls, speech_api_calls, translation_api_calls, nlp_api_calls, " +
                "  training_node_hours, pipeline_runs_count, workbench_uptime_hours, active_workbench_count, " +
                "  current_rpm, max_rpm_quota, gemini_flash_ratio, gemini_pro_ratio, claude_ratio, custom_model_ratio, " +
                "  estimated_api_cost, estimated_training_cost, total_estimated_daily_cost " +
                "FROM (" +
                "  SELECT *, ROW_NUMBER() OVER(PARTITION BY snapshot_date ORDER BY created_at DESC) AS rn " +
                "  FROM `%s.%s.%s` " +
                "  WHERE project_id = '%s' AND CAST(snapshot_date AS STRING) LIKE '%s%%' " +
                ") WHERE rn = 1 ORDER BY snapshot_date ASC LIMIT 31",
                hostProjectId, datasetName, DIRECT_AI_TABLE, effectiveProjectId, effectiveYearMonth
            );

            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            List<FieldValueList> rows = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                rows.add(row);
            }

            if (!rows.isEmpty()) {
                String customerName = rows.get(0).get("customer_name").isNull() ? "고객사 GCP 프로젝트" : rows.get(0).get("customer_name").getStringValue();
                List<String> dates = new ArrayList<>();
                List<Long> inputTokens = new ArrayList<>();
                List<Long> outputTokens = new ArrayList<>();
                List<Long> pretrainedCalls = new ArrayList<>();

                long sumInTok = 0;
                long sumOutTok = 0;
                long sumPretrained = 0;
                long sumVision = 0;
                long sumSpeech = 0;
                long sumTrans = 0;
                long sumNlp = 0;

                for (FieldValueList r : rows) {
                    String fullDate = r.get("sdate").getStringValue();
                    String shortDate = fullDate.length() >= 5 ? fullDate.substring(5).replace("-", ".") : fullDate;
                    dates.add(shortDate);

                    long inTok = r.get("input_tokens").isNull() ? 0L : r.get("input_tokens").getLongValue();
                    long outTok = r.get("output_tokens").isNull() ? 0L : r.get("output_tokens").getLongValue();
                    long preCalls = r.get("pretrained_api_calls").isNull() ? 0L : r.get("pretrained_api_calls").getLongValue();

                    inputTokens.add(inTok);
                    outputTokens.add(outTok);
                    pretrainedCalls.add(preCalls);

                    sumInTok += inTok;
                    sumOutTok += outTok;
                    sumPretrained += preCalls;
                    sumVision += r.get("vision_api_calls").isNull() ? 0L : r.get("vision_api_calls").getLongValue();
                    sumSpeech += r.get("speech_api_calls").isNull() ? 0L : r.get("speech_api_calls").getLongValue();
                    sumTrans += r.get("translation_api_calls").isNull() ? 0L : r.get("translation_api_calls").getLongValue();
                    sumNlp += r.get("nlp_api_calls").isNull() ? 0L : r.get("nlp_api_calls").getLongValue();
                }

                FieldValueList latest = rows.get(rows.size() - 1);
                int currentRpm = latest.get("current_rpm").isNull() ? 0 : (int) latest.get("current_rpm").getLongValue();
                int maxRpmQuota = latest.get("max_rpm_quota").isNull() ? 1000 : (int) latest.get("max_rpm_quota").getLongValue();
                double rpmUsagePercent = maxRpmQuota > 0 ? Math.round(((double) currentRpm / maxRpmQuota * 100.0) * 10.0) / 10.0 : 0.0;

                long currentTpd = latest.get("total_tokens").isNull() ? 0L : latest.get("total_tokens").getLongValue();
                long maxTpdQuota = 4500000L;
                double tpdUsagePercent = Math.round(((double) currentTpd / maxTpdQuota * 100.0) * 10.0) / 10.0;
                boolean quotaAlert = rpmUsagePercent >= 80.0 || tpdUsagePercent >= 80.0;

                double trainingHours = latest.get("training_node_hours").isNull() ? 0.0 : latest.get("training_node_hours").getDoubleValue();
                int pipelineRuns = latest.get("pipeline_runs_count").isNull() ? 0 : (int) latest.get("pipeline_runs_count").getLongValue();
                double workbenchUptime = latest.get("workbench_uptime_hours").isNull() ? 0.0 : latest.get("workbench_uptime_hours").getDoubleValue();
                int activeWorkbench = latest.get("active_workbench_count").isNull() ? 0 : (int) latest.get("active_workbench_count").getLongValue();

                double flashRatio = latest.get("gemini_flash_ratio").isNull() ? 0.0 : latest.get("gemini_flash_ratio").getDoubleValue();
                double proRatio = latest.get("gemini_pro_ratio").isNull() ? 0.0 : latest.get("gemini_pro_ratio").getDoubleValue();
                double claudeRatio = latest.get("claude_ratio").isNull() ? 0.0 : latest.get("claude_ratio").getDoubleValue();
                double customRatio = latest.get("custom_model_ratio").isNull() ? 0.0 : latest.get("custom_model_ratio").getDoubleValue();

                double estimatedApiCost = latest.get("estimated_api_cost").isNull() ? 0.0 : latest.get("estimated_api_cost").getDoubleValue();
                double estimatedTrainingCost = latest.get("estimated_training_cost").isNull() ? 0.0 : latest.get("estimated_training_cost").getDoubleValue();
                double totalDailyCost = latest.get("total_estimated_daily_cost").isNull() ? 0.0 : latest.get("total_estimated_daily_cost").getDoubleValue();
                double totalMonthlyCost = Math.round(totalDailyCost * 30.0 * 100.0) / 100.0;

                return DirectAiMetricsDto.builder()
                        .projectId(effectiveProjectId)
                        .customerName(customerName)
                        .dates(dates)
                        .inputTokensTrend(inputTokens)
                        .outputTokensTrend(outputTokens)
                        .pretrainedApiCallsTrend(pretrainedCalls)
                        .totalInputTokens(sumInTok)
                        .totalOutputTokens(sumOutTok)
                        .totalTokens(sumInTok + sumOutTok)
                        .totalPretrainedApiCalls(sumPretrained)
                        .visionApiCalls(sumVision)
                        .speechApiCalls(sumSpeech)
                        .translationApiCalls(sumTrans)
                        .nlpApiCalls(sumNlp)
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
            } else {
                return createEmptyDirectAiMetrics(effectiveProjectId);
            }
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
     * 타겟 고객사 프로젝트 및 지정 연월 기준의 Endpoint Serving (엔드포인트 서빙) 관제 메트릭 조회
     */
    public EndpointServingMetricsDto getEndpointServingOperationsMetrics(String targetProjectId, String targetYearMonth) {
        if (targetProjectId == null || targetProjectId.trim().isEmpty()) {
            return createEmptyEndpointServingMetrics("");
        }

        String effectiveProjectId = targetProjectId.trim();
        String effectiveYearMonth = (targetYearMonth != null && targetYearMonth.matches("^\\d{4}-\\d{2}$"))
                ? targetYearMonth.trim()
                : YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        log.info("[ENDPOINT-SERVING-METRICS] Fetching endpoint serving metrics for project `{}` and month `{}`",
                effectiveProjectId, effectiveYearMonth);

        try {
            // 1. 일별 집계 쿼리 (날짜별 총 예측 요청수, 평균 지연시간, QPS)
            String dailySql = String.format(
                "SELECT " +
                "  CAST(snapshot_date AS STRING) AS sdate, " +
                "  SUM(total_requests) AS daily_requests, " +
                "  AVG(avg_latency_ms) AS daily_avg_latency, " +
                "  AVG(qps) AS daily_qps " +
                "FROM `%s.%s.%s` " +
                "WHERE project_id = '%s' AND CAST(snapshot_date AS STRING) LIKE '%s%%' " +
                "GROUP BY snapshot_date ORDER BY snapshot_date ASC",
                hostProjectId, datasetName, ENDPOINT_SERVING_TABLE, effectiveProjectId, effectiveYearMonth
            );

            TableResult dailyRes = bigQuery.query(QueryJobConfiguration.newBuilder(dailySql).build());
            List<String> dates = new ArrayList<>();
            List<Long> dailyRequests = new ArrayList<>();
            List<Integer> dailyLatencies = new ArrayList<>();
            List<Double> dailyQpsList = new ArrayList<>();

            for (FieldValueList row : dailyRes.iterateAll()) {
                String sdate = row.get("sdate").getStringValue();
                dates.add(sdate.length() >= 10 ? sdate.substring(5).replace("-", ".") : sdate);
                dailyRequests.add(row.get("daily_requests").getLongValue());
                dailyLatencies.add((int) row.get("daily_avg_latency").getDoubleValue());
                dailyQpsList.add(Math.round(row.get("daily_qps").getDoubleValue() * 100.0) / 100.0);
            }

            // 2. 최신 스냅샷 기준 개별 엔드포인트 목록 쿼리
            String endpointSql = String.format(
                "SELECT * FROM (" +
                "  SELECT *, ROW_NUMBER() OVER(PARTITION BY endpoint_id ORDER BY snapshot_date DESC, created_at DESC) as rn " +
                "  FROM `%s.%s.%s` " +
                "  WHERE project_id = '%s' AND CAST(snapshot_date AS STRING) LIKE '%s%%' " +
                ") WHERE rn = 1 ORDER BY endpoint_name ASC",
                hostProjectId, datasetName, ENDPOINT_SERVING_TABLE, effectiveProjectId, effectiveYearMonth
            );

            TableResult epRes = bigQuery.query(QueryJobConfiguration.newBuilder(endpointSql).build());
            List<EndpointServingMetricsDto.EndpointDetailDto> endpointItems = new ArrayList<>();
            String customerName = "고객사 GCP 프로젝트";
            long totalRequests7d = 0;
            long weightedLatencySum = 0;
            int maxP95 = 0;
            int maxP99 = 0;
            double totalHourlyCost = 0.0;
            int activeCount = 0;
            int totalGpuCount = 0;
            long error4xxSum = 0;
            long error5xxSum = 0;

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
                totalRequests7d += reqs;
                weightedLatencySum += (reqs * avgLat);
                if (p95Lat > maxP95) maxP95 = p95Lat;
                if (p99Lat > maxP99) maxP99 = p99Lat;
                totalHourlyCost += hourlyCost;
                error4xxSum += row.get("error_count_4xx").getLongValue();
                error5xxSum += row.get("error_count_5xx").getLongValue();

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

            int overallAvgLatency = totalRequests7d > 0 ? (int) (weightedLatencySum / totalRequests7d) : 0;
            double err4xxRate = totalRequests7d > 0 ? Math.round(((double) error4xxSum / totalRequests7d * 100.0) * 10.0) / 10.0 : 0.0;
            double err5xxRate = totalRequests7d > 0 ? Math.round(((double) error5xxSum / totalRequests7d * 100.0) * 10.0) / 10.0 : 0.0;
            double successRate = Math.max(0.0, Math.round((100.0 - err4xxRate - err5xxRate) * 10.0) / 10.0);
            double monthlyCost = Math.round(totalHourlyCost * 24 * 30 * 10.0) / 10.0;
            double currentQps = Math.round((totalRequests7d / (7.0 * 86400.0)) * 100.0) / 100.0;

            return EndpointServingMetricsDto.builder()
                    .projectId(effectiveProjectId)
                    .customerName(customerName)
                    .totalRequests7d(totalRequests7d)
                    .currentQps(currentQps)
                    .avgLatencyMs(overallAvgLatency)
                    .p95LatencyMs(maxP95)
                    .p99LatencyMs(maxP99)
                    .errorRate4xxPercent(err4xxRate)
                    .errorRate5xxPercent(err5xxRate)
                    .successRatePercent(successRate)
                    .totalEndpoints(endpointItems.size())
                    .activeEndpoints(activeCount)
                    .totalAllocatedGpus(totalGpuCount)
                    .totalEstimatedHourlyCost(Math.round(totalHourlyCost * 100.0) / 100.0)
                    .totalEstimatedMonthlyCost(monthlyCost)
                    .dates(dates)
                    .dailyRequestsTrend(dailyRequests)
                    .dailyLatencyTrend(dailyLatencies)
                    .dailyQpsTrend(dailyQpsList)
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

    // Legacy Fallback methods
    public VertexAiMetricsDto getVertexAiOperationsMetrics(String targetProjectId, String targetYearMonth) {
        DirectAiMetricsDto d = getDirectAiOperationsMetrics(targetProjectId, targetYearMonth);
        return VertexAiMetricsDto.builder()
                .projectId(d.getProjectId())
                .customerName(d.getCustomerName())
                .dates(d.getDates())
                .inputTokensTrend(d.getInputTokensTrend())
                .outputTokensTrend(d.getOutputTokensTrend())
                .rpmQuotaUsagePercent(d.getRpmQuotaUsagePercent())
                .tpdQuotaUsagePercent(d.getTpdQuotaUsagePercent())
                .currentRpm(d.getCurrentRpm())
                .maxRpmQuota(d.getMaxRpmQuota())
                .currentTpd(d.getCurrentTpd())
                .maxTpdQuota(d.getMaxTpdQuota())
                .quotaAlert(d.getQuotaAlert())
                .estimatedHourlyCost(d.getTotalEstimatedDailyCost() / 24.0)
                .estimatedMonthlyCost(d.getTotalEstimatedMonthlyCost())
                .geminiFlashRatio(d.getGeminiFlashRatio())
                .geminiProRatio(d.getGeminiProRatio())
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
