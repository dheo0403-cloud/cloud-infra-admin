package com.example.infra.service;

import com.example.infra.dto.DirectAiMetricsDto;
import com.example.infra.dto.VertexAiMetricsDto;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
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
 * GCP AI 서비스 직접 사용 (Direct AI Usage) 관제 메트릭 집계 서비스 (4개월 추이 + 기준월 모델별 호출·에러율)
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
    private static final String MONTHLY_DIRECT_AI_SUMMARY_TABLE = "monthly_direct_ai_summary";
    private static final String MODEL_USAGE_TABLE = "daily_ai_model_usage";

    /** 요청으로 받은 프로젝트 ID·연월은 SQL 문자열에 넣지 않고 이름 붙은 파라미터(@pid, @fromYm, @toYm)로 전달 (SQL 인젝션 방지) */
    private TableResult query(String sql, String projectId, String fromYm, String toYm) throws InterruptedException {
        return bigQuery.query(QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("pid", QueryParameterValue.string(projectId))
                .addNamedParameter("fromYm", QueryParameterValue.string(fromYm))
                .addNamedParameter("toYm", QueryParameterValue.string(toYm))
                .build());
    }

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
                    "WHERE project_id = @pid AND report_year_month BETWEEN @fromYm AND @toYm " +
                    "ORDER BY ym ASC",
                    hostProjectId, datasetName, MONTHLY_DIRECT_AI_SUMMARY_TABLE
                );
                TableResult summaryResult = query(summarySql, effectiveProjectId, startYm, endYm);
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
                    "WHERE project_id = @pid AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) BETWEEN @fromYm AND @toYm " +
                    "GROUP BY ym ORDER BY ym ASC",
                    hostProjectId, datasetName, DIRECT_AI_TABLE
                );

                TableResult monthlyResult = query(monthlySql, effectiveProjectId, startYm, endYm);
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
            long targetMonthTotTok = 0;
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
                        // 월간 누적 토큰 = 모든 type 합계 (캐시 등 포함)
                        targetMonthTotTok = r.get("monthly_tot_tokens").isNull() ? inTok + outTok : r.get("monthly_tot_tokens").getLongValue();
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

            // 2. 기준월 모델별 호출·토큰 합계 (daily_ai_model_usage). 테이블이 아직 없으면 빈 목록
            List<DirectAiMetricsDto.ModelUsageDto> models = new ArrayList<>();
            long totalInvocations = 0L;
            try {
                String modelSql = String.format(
                    "SELECT publisher, model, SUM(invocations) AS inv, SUM(input_tokens) AS in_tok, SUM(output_tokens) AS out_tok, " +
                    "  SUM(error_invocations) AS err, SUM(IF(error_invocations IS NULL, 0, invocations)) AS inv_with_err " +
                    "FROM `%s.%s.%s` " +
                    "WHERE project_id = @pid AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) BETWEEN @fromYm AND @toYm " +
                    "GROUP BY publisher, model ORDER BY inv DESC, model ASC",
                    hostProjectId, datasetName, MODEL_USAGE_TABLE
                );
                for (FieldValueList row : query(modelSql, effectiveProjectId, effectiveYearMonth, effectiveYearMonth).iterateAll()) {
                    long inv = row.get("inv").isNull() ? 0L : row.get("inv").getLongValue();
                    totalInvocations += inv;
                    models.add(DirectAiMetricsDto.ModelUsageDto.builder()
                            .publisher(row.get("publisher").isNull() ? "" : row.get("publisher").getStringValue())
                            .model(row.get("model").isNull() ? "" : row.get("model").getStringValue())
                            .invocations(inv)
                            .inputTokens(row.get("in_tok").isNull() ? 0L : row.get("in_tok").getLongValue())
                            .outputTokens(row.get("out_tok").isNull() ? 0L : row.get("out_tok").getLongValue())
                            .errorInvocations(row.get("err").isNull() ? null : row.get("err").getLongValue())
                            // 월 에러율은 에러 수를 수집한 날의 호출만 분모로 사용 (수집 전 일자 제외)
                            .errorRate(row.get("err").isNull() || row.get("inv_with_err").getLongValue() == 0 ? null
                                    : Math.round(row.get("err").getLongValue() * 10000.0 / row.get("inv_with_err").getLongValue()) / 100.0)
                            .build());
                }
            } catch (Exception ex) {
                log.debug("Model usage query skipped: {}", ex.getMessage());
            }

            // 3. 기준월 일별 에러율 (호출 많은 상위 3개 모델)
            List<String> errorRateDates = new ArrayList<>();
            List<DirectAiMetricsDto.ErrorRateSeriesDto> errorRateSeries = new ArrayList<>();
            try {
                fillDailyErrorRates(effectiveProjectId, targetYm, models, errorRateDates, errorRateSeries);
            } catch (Exception ex) {
                log.debug("Daily error rate query skipped: {}", ex.getMessage());
            }

            // 4. 기준월 중 권한 없음으로 수집하지 못한 일수 (컬럼이 아직 없으면 0)
            long permissionDeniedDays = 0L;
            try {
                String deniedSql = String.format(
                    "SELECT COUNT(DISTINCT snapshot_date) AS n FROM `%s.%s.%s` " +
                    "WHERE project_id = @pid AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) BETWEEN @fromYm AND @toYm " +
                    "AND collect_status = 'PERMISSION_DENIED'",
                    hostProjectId, datasetName, DIRECT_AI_TABLE
                );
                for (FieldValueList row : query(deniedSql, effectiveProjectId, effectiveYearMonth, effectiveYearMonth).iterateAll()) {
                    permissionDeniedDays = row.get("n").getLongValue();
                }
            } catch (Exception ex) {
                log.debug("Permission denied days query skipped: {}", ex.getMessage());
            }

            // 학습/Workbench/Quota/비용은 수집 근거가 없어 0으로 반환 (화면에서 표시하지 않음)
            return DirectAiMetricsDto.builder()
                    .projectId(effectiveProjectId)
                    .customerName(customerName)
                    .dates(labelList)
                    .inputTokensTrend(inputTokensTrend)
                    .outputTokensTrend(outputTokensTrend)
                    .pretrainedApiCallsTrend(pretrainedCallsTrend)
                    .totalInputTokens(targetMonthInTok)
                    .totalOutputTokens(targetMonthOutTok)
                    .totalTokens(targetMonthTotTok)
                    .totalPretrainedApiCalls(targetMonthPre)
                    .visionApiCalls(targetMonthVision)
                    .speechApiCalls(targetMonthSpeech)
                    .translationApiCalls(targetMonthTrans)
                    .nlpApiCalls(targetMonthNlp)
                    .trainingNodeHours(0.0)
                    .pipelineRunsCount(0)
                    .workbenchUptimeHours(0.0)
                    .activeWorkbenchCount(0)
                    .currentRpm(0)
                    .maxRpmQuota(0)
                    .rpmQuotaUsagePercent(0.0)
                    .currentTpd(0L)
                    .maxTpdQuota(0L)
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
                    .totalInvocations(totalInvocations)
                    .models(models)
                    .errorRateDates(errorRateDates)
                    .errorRateSeries(errorRateSeries)
                    .permissionDeniedDays(permissionDeniedDays)
                    .lastUpdated(LocalDateTime.now().format(timeFormatter))
                    .build();

        } catch (Exception e) {
            log.warn("[DIRECT-AI-METRICS] BigQuery query notice: {}. Returning empty data for {}.", e.getMessage(), effectiveYearMonth);
            return createEmptyDirectAiMetrics(effectiveProjectId);
        }
    }

    /**
     * 기준월 1일~말일(당월이면 어제까지) 일별 에러율. 호출이 없거나 에러 수를 수집하기 전 날은 null
     */
    private void fillDailyErrorRates(String projectId, YearMonth ym, List<DirectAiMetricsDto.ModelUsageDto> models,
                                     List<String> dates, List<DirectAiMetricsDto.ErrorRateSeriesDto> series) throws InterruptedException {
        java.time.LocalDate first = ym.atDay(1);
        java.time.LocalDate last = ym.atEndOfMonth();
        java.time.LocalDate yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1);
        if (last.isAfter(yesterday)) last = yesterday;
        if (last.isBefore(first) || models.isEmpty()) return;

        List<DirectAiMetricsDto.ModelUsageDto> top = models.subList(0, Math.min(3, models.size())); // 호출 수 내림차순
        Map<String, Map<String, Double>> rates = new HashMap<>(); // publisher/model -> 날짜 -> 에러율(%)
        String sql = String.format(
            "SELECT CAST(snapshot_date AS STRING) AS d, publisher, model, SUM(invocations) AS inv, SUM(error_invocations) AS err " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @pid AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) BETWEEN @fromYm AND @toYm " +
            "GROUP BY d, publisher, model",
            hostProjectId, datasetName, MODEL_USAGE_TABLE
        );
        String ymStr = ym.toString();
        for (FieldValueList row : query(sql, projectId, ymStr, ymStr).iterateAll()) {
            long inv = row.get("inv").isNull() ? 0L : row.get("inv").getLongValue();
            if (inv <= 0 || row.get("err").isNull()) continue;
            String key = row.get("publisher").getStringValue() + "/" + row.get("model").getStringValue();
            rates.computeIfAbsent(key, k -> new HashMap<>())
                 .put(row.get("d").getStringValue(), Math.round(row.get("err").getLongValue() * 10000.0 / inv) / 100.0);
        }

        for (java.time.LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            dates.add(d.format(DateTimeFormatter.ofPattern("MM.dd")));
        }
        for (DirectAiMetricsDto.ModelUsageDto m : top) {
            Map<String, Double> byDate = rates.getOrDefault(m.getPublisher() + "/" + m.getModel(), Map.of());
            List<Double> daily = new ArrayList<>();
            for (java.time.LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                daily.add(byDate.get(d.toString()));
            }
            series.add(DirectAiMetricsDto.ErrorRateSeriesDto.builder()
                    .publisher(m.getPublisher())
                    .model(m.getModel())
                    .monthlyErrorRate(m.getErrorRate())
                    .dailyErrorRates(daily)
                    .build());
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
                .maxRpmQuota(0)
                .rpmQuotaUsagePercent(0.0)
                .currentTpd(0L)
                .maxTpdQuota(0L)
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
                .totalInvocations(0L)
                .models(new ArrayList<>())
                .errorRateDates(new ArrayList<>())
                .errorRateSeries(new ArrayList<>())
                .lastUpdated(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
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
}
