package com.example.infra.service;

import com.example.infra.dto.BigQueryOptimizationDto;
import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * BigQuery 성능 및 비용 최적화 분석 관제 서비스
 * - 사용자가 제공한 5대 INFORMATION_SCHEMA 쿼리 원본 구조 보존
 * - 고객사별 project_id, region (asia-northeast3 등), 검색 조건년월 (YYYY-MM 및 Timestamp 범위) 동적 파라미터화
 * - 일배치 매일 누적 적재 및 월 변경 시 past month 1~N-1일 중간 스냅샷 자동 삭제 (최종일 1건만 보존)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BigQueryOptimizationService {

    private final BigQuery bigQuery;
    private final InfraEnvironmentService infraEnvironmentService;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String hostProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    public static final String RESOURCE_SUMMARY_TABLE = "monthly_bq_resource_summary";
    public static final String STORAGE_SUMMARY_TABLE = "monthly_bq_storage_summary";
    public static final String TOP_COST_TABLE = "monthly_bq_top_cost";
    public static final String TOP_EXEC_TABLE = "monthly_bq_top_exec";
    public static final String TOP_SLOT_TABLE = "monthly_bq_top_slot";
    public static final String TOP_QUERIES_TABLE = "monthly_bq_top_queries";

    // =========================================================================
    // [사용자 제공 원본 5대 SQL 쿼리 템플릿]
    // =========================================================================

    // 1. Job수와 데이터 사용량 쿼리
    public static final String QUERY_1_JOB_USAGE =
        "SELECT\n" +
        "  FORMAT_DATE('%%Y-%%m', DATE(DATETIME(creation_time, 'Asia/Seoul'))) AS crt_dt,\n" +
        "  COUNT(job_id) AS job_count,\n" +
        "  SUM(total_bytes_processed) AS total_bytes_processed_sum,\n" +
        "  SUM(total_bytes_billed) AS total_bytes_billed_sum,\n" +
        "  ROUND(SAFE_DIVIDE(SUM(total_bytes_processed), POWER(1024, 4)), 4) AS total_tb_processed_sum,\n" +
        "  ROUND(SAFE_DIVIDE(SUM(total_bytes_billed), POWER(1024, 4)), 4) AS total_tb_billed_sum\n" +
        "FROM `%s.region-%s.INFORMATION_SCHEMA.JOBS`\n" +
        "WHERE job_type = 'QUERY'\n" +
        "  AND state = 'DONE'\n" +
        "  AND FORMAT_DATE('%%Y-%%m', DATE(DATETIME(creation_time, 'Asia/Seoul'))) = '%s'\n" +
        "GROUP BY 1\n" +
        "ORDER BY 1";

    // 2. 전체 데이터셋 스토리지 용량(GB)
    public static final String QUERY_2_STORAGE_GB =
        "SELECT\n" +
        "  FORMAT_DATE('%%Y-%%m', DATE(DATETIME(creation_time, 'Asia/Seoul'))) AS create_month,\n" +
        "  project_id,\n" +
        "  table_schema,\n" +
        "  SUM(ROUND(SAFE_DIVIDE(total_logical_bytes, POWER(1024, 3)), 2)) AS total_logical_gb,\n" +
        "  SUM(ROUND(SAFE_DIVIDE(total_physical_bytes, POWER(1024, 3)), 2)) AS total_physical_gb,\n" +
        "  SUM(ROUND(SAFE_DIVIDE(total_physical_bytes, POWER(1024, 4)), 4)) AS total_physical_tb\n" +
        "FROM `%s.region-%s.INFORMATION_SCHEMA.TABLE_STORAGE_BY_PROJECT` \n" +
        "WHERE\n" +
        "  deleted = false\n" +
        "  AND fail_safe_physical_bytes <> 0\n" +
        "  AND table_schema NOT LIKE '_script%%'\n" +
        "  AND FORMAT_DATE('%%Y-%%m', DATE(DATETIME(creation_time, 'Asia/Seoul'))) = '%s'\n" +
        "GROUP BY\n" +
        "  1, 2, 3\n" +
        "ORDER BY\n" +
        "  3";

    // 3. 월에 가장 많은 데이터 비용을 사용한 TOP 10
    public static final String QUERY_3_TOP_COST =
        "SELECT\n" +
        "  DATE(DATETIME(creation_time,'Asia/Seoul')) as crt_dt,\n" +
        "  priority,\n" +
        "  job_id,\n" +
        "  project_id,\n" +
        "  user_email,\n" +
        "  job_type,\n" +
        "  statement_type,\n" +
        "  start_time,\n" +
        "  end_time,\n" +
        "  query,\n" +
        "  total_bytes_processed,\n" +
        "  total_slot_ms,\n" +
        "  error_result.reason AS error_reason,\n" +
        "  error_result.message AS error_message\n" +
        "FROM `%s.region-%s.INFORMATION_SCHEMA.JOBS`\n" +
        "WHERE job_type = 'QUERY'\n" +
        "  AND creation_time >= TIMESTAMP('%s', 'Asia/Seoul')\n" +
        "  AND creation_time < TIMESTAMP('%s', 'Asia/Seoul')\n" +
        "  AND state = 'DONE'\n" +
        "ORDER BY total_bytes_processed DESC\n" +
        "LIMIT 10";

    // 4. 월간 실행 시간이 가장 길었던 Job 식별 TOP 10
    public static final String QUERY_4_TOP_EXECUTION_TIME =
        "SELECT\n" +
        "  DATE(DATETIME(creation_time, 'Asia/Seoul')) AS crt_dt,\n" +
        "  job_id,\n" +
        "  project_id,\n" +
        "  user_email,\n" +
        "  job_type,\n" +
        "  query,\n" +
        "  statement_type,\n" +
        "  start_time,\n" +
        "  end_time,\n" +
        "  TIMESTAMP_DIFF(end_time, start_time, MILLISECOND) AS execution_time_ms,\n" +
        "  FORMAT_TIMESTAMP('%%H시 %%M분 %%S초', TIMESTAMP_MILLIS(TIMESTAMP_DIFF(end_time, start_time, MILLISECOND))) AS execution_time_formatted,\n" +
        "  total_slot_ms,\n" +
        "  ROUND(SAFE_DIVIDE(total_slot_ms, TIMESTAMP_DIFF(end_time, start_time, MILLISECOND)), 2) AS job_average_slots,\n" +
        "  total_bytes_processed,\n" +
        "  error_result.reason AS error_reason,\n" +
        "  error_result.message AS error_message\n" +
        "FROM `%s.region-%s.INFORMATION_SCHEMA.JOBS`\n" +
        "WHERE job_type = 'QUERY'\n" +
        "  AND creation_time >= TIMESTAMP('%s', 'Asia/Seoul')\n" +
        "  AND creation_time < TIMESTAMP('%s', 'Asia/Seoul')\n" +
        "  AND state = 'DONE'\n" +
        "ORDER BY execution_time_ms DESC\n" +
        "LIMIT 10";

    // 5. 월간 슬롯 사용량 분석 (Slot Throttling 또는 병목 현상 파악) TOP 10
    public static final String QUERY_5_TOP_SLOTS =
        "SELECT\n" +
        "  DATE(DATETIME(creation_time,'Asia/Seoul')) as crt_dt,\n" +
        "  job_id,\n" +
        "  project_id,\n" +
        "  user_email,\n" +
        "  job_type,\n" +
        "  statement_type,\n" +
        "  query,\n" +
        "  start_time,\n" +
        "  end_time,\n" +
        "  total_slot_ms,\n" +
        "  ROUND(SAFE_DIVIDE(total_slot_ms, TIMESTAMP_DIFF(end_time, start_time, MILLISECOND)), 2) AS job_average_slots,\n" +
        "  total_bytes_processed,\n" +
        "  TIMESTAMP_DIFF(end_time, start_time, MILLISECOND) AS execution_time_ms\n" +
        "FROM `%s.region-%s.INFORMATION_SCHEMA.JOBS`\n" +
        "WHERE job_type = 'QUERY'\n" +
        "  AND creation_time >= TIMESTAMP('%s', 'Asia/Seoul')\n" +
        "  AND creation_time < TIMESTAMP('%s', 'Asia/Seoul')\n" +
        "  AND state = 'DONE'\n" +
        "ORDER BY total_slot_ms DESC\n" +
        "LIMIT 10";

    // =========================================================================
    // [동적 쿼리 빌더 메서드]
    // =========================================================================

    public String buildQuery1JobUsage(String projectId, String region, String yearMonth) {
        return String.format(QUERY_1_JOB_USAGE, projectId, region, yearMonth);
    }

    public String buildQuery2StorageGb(String projectId, String region, String yearMonth) {
        return String.format(QUERY_2_STORAGE_GB, projectId, region, yearMonth);
    }

    public String buildQuery3TopCost(String projectId, String region, String startTimeStr, String endTimeStr) {
        return String.format(QUERY_3_TOP_COST, projectId, region, startTimeStr, endTimeStr);
    }

    public String buildQuery4TopDuration(String projectId, String region, String startTimeStr, String endTimeStr) {
        return String.format(QUERY_4_TOP_EXECUTION_TIME, projectId, region, startTimeStr, endTimeStr);
    }

    public String buildQuery5TopSlots(String projectId, String region, String startTimeStr, String endTimeStr) {
        return String.format(QUERY_5_TOP_SLOTS, projectId, region, startTimeStr, endTimeStr);
    }

    /**
     * 필드 존재 및 non-null 여부 안전 검사 헬퍼
     */
    private boolean isPresent(FieldValueList row, String colName) {
        try {
            FieldValue val = row.get(colName);
            return val != null && !val.isNull();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 테이블이 생성되어 있는지 확인 및 생성
     */
    public void ensureTablesExist() {
        try {
            DatasetId datasetId = DatasetId.of(hostProjectId, datasetName);
            if (bigQuery.getDataset(datasetId) == null) {
                bigQuery.create(DatasetInfo.newBuilder(datasetId).setLocation("asia-northeast3").build());
                log.info("[BQ-OPTIMIZATION] Created dataset `{}`", datasetName);
            }

            bigQuery.query(QueryJobConfiguration.newBuilder(String.format(
                "CREATE TABLE IF NOT EXISTS `%s.%s.%s` (" +
                "  snapshot_date DATE," +
                "  report_year_month STRING," +
                "  project_id STRING," +
                "  customer_name STRING," +
                "  job_count INT64," +
                "  total_bytes_processed INT64," +
                "  total_tb_processed FLOAT64," +
                "  total_bytes_billed INT64," +
                "  total_tb_billed FLOAT64," +
                "  total_logical_gb FLOAT64," +
                "  total_physical_gb FLOAT64," +
                "  total_physical_tb FLOAT64," +
                "  max_slots FLOAT64," +
                "  min_slots FLOAT64," +
                "  avg_slots FLOAT64," +
                "  updated_at TIMESTAMP" +
                ") PARTITION BY snapshot_date OPTIONS (partition_expiration_days = 365)",
                hostProjectId, datasetName, RESOURCE_SUMMARY_TABLE
            )).build());

            bigQuery.query(QueryJobConfiguration.newBuilder(String.format(
                "CREATE TABLE IF NOT EXISTS `%s.%s.%s` (" +
                "  snapshot_date DATE," +
                "  report_year_month STRING," +
                "  project_id STRING," +
                "  customer_name STRING," +
                "  query_category STRING," +
                "  rank INT64," +
                "  created_date STRING," +
                "  job_id STRING," +
                "  user_email STRING," +
                "  statement_type STRING," +
                "  query STRING," +
                "  bytes_processed_gb FLOAT64," +
                "  bytes_billed_gb FLOAT64," +
                "  estimated_cost_usd FLOAT64," +
                "  total_slot_ms INT64," +
                "  execution_time_seconds FLOAT64," +
                "  execution_duration_formatted STRING," +
                "  job_average_slots FLOAT64," +
                "  updated_at TIMESTAMP" +
                ") PARTITION BY snapshot_date OPTIONS (partition_expiration_days = 180)",
                hostProjectId, datasetName, TOP_QUERIES_TABLE
            )).build());

        } catch (Exception e) {
            log.warn("[BQ-OPTIMIZATION] ensureTablesExist notice: {}", e.getMessage());
        }
    }

    /**
     * 고객사 프로젝트 리전 자동 탐색
     */
    public Set<String> discoverProjectRegions(GoogleCredentials credentials, String projectId) {
        Set<String> locations = new LinkedHashSet<>();
        if (credentials != null) {
            try {
                BigQuery client = BigQueryOptions.newBuilder()
                        .setCredentials(credentials)
                        .setProjectId(projectId)
                        .build()
                        .getService();

                for (Dataset ds : client.listDatasets(projectId).iterateAll()) {
                    Dataset detailed = client.getDataset(ds.getDatasetId());
                    if (detailed != null && detailed.getLocation() != null && !detailed.getLocation().trim().isEmpty()) {
                        locations.add(detailed.getLocation().toLowerCase().trim());
                    }
                }
            } catch (Exception e) {
                log.debug("[BQ-REGION] Dynamic region discovery fallback for {}: {}", projectId, e.getMessage());
            }
        }

        if (locations.isEmpty()) {
            locations.add("asia-northeast3");
            locations.add("us");
        }
        return locations;
    }

    /**
     * 실측 사용량 기록 DTO (가공 없는 원본 수치 보존)
     */
    public static class RealUsageSummary {
        public long jobCount = 0;
        public long totalBytesProcessed = 0L;
        public long totalBytesBilled = 0L;
        public double totalTbProcessed = 0.0;
        public double totalTbBilled = 0.0;
        public double totalLogicalGb = 0.0;
        public double totalPhysicalGb = 0.0;
        public double totalPhysicalTb = 0.0;
        public boolean jobQuerySucceeded = false;   // 쿼리 1이 한 리전 이상에서 성공했는지 (실패 시 0 적재 방지)
    }

    /**
     * 사용자가 공유한 5개 쿼리 중 1번(Job/TB) & 2번(Storage)을 실행하여 실측 리소스 요약을 산출
     */
    public RealUsageSummary queryRealUsageSummaryWithUserQueries(GoogleCredentials credentials, String projectId, String yearMonth) {
        RealUsageSummary summary = new RealUsageSummary();
        if (credentials == null) {
            log.warn("[BQ-OPTIMIZATION-REAL] No credentials provided for project {}", projectId);
            return summary;
        }

        Set<String> regions = discoverProjectRegions(credentials, projectId);

        try {
            BigQuery tenantClient = BigQueryOptions.newBuilder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build()
                    .getService();

            for (String region : regions) {
                // 1. Job 수 및 Raw Bytes / TB 사용량 쿼리 실행 (무가공 원본 수치 수집)
                try {
                    String sql1 = buildQuery1JobUsage(projectId, region, yearMonth);
                    TableResult res1 = tenantClient.query(QueryJobConfiguration.newBuilder(sql1).build());
                    for (FieldValueList row : res1.iterateAll()) {
                        if (isPresent(row, "job_count")) {
                            summary.jobCount += row.get("job_count").getLongValue();
                        }
                        if (isPresent(row, "total_bytes_processed_sum")) {
                            summary.totalBytesProcessed += row.get("total_bytes_processed_sum").getLongValue();
                        }
                        if (isPresent(row, "total_bytes_billed_sum")) {
                            summary.totalBytesBilled += row.get("total_bytes_billed_sum").getLongValue();
                        }
                        if (isPresent(row, "total_tb_processed_sum")) {
                            summary.totalTbProcessed += row.get("total_tb_processed_sum").getDoubleValue();
                        }
                        if (isPresent(row, "total_tb_billed_sum")) {
                            summary.totalTbBilled += row.get("total_tb_billed_sum").getDoubleValue();
                        }
                    }
                    summary.jobQuerySucceeded = true;
                } catch (Exception e) {
                    log.error("[BQ-QUERY1] Query 1 failed for project {} region {}: {}", projectId, region, e.getMessage());
                }

                // 2. 스토리지 용량 쿼리 실행
                try {
                    String sql2 = buildQuery2StorageGb(projectId, region, yearMonth);
                    TableResult res2 = tenantClient.query(QueryJobConfiguration.newBuilder(sql2).build());
                    for (FieldValueList row : res2.iterateAll()) {
                        if (isPresent(row, "total_logical_gb")) {
                            summary.totalLogicalGb += row.get("total_logical_gb").getDoubleValue();
                        }
                        if (isPresent(row, "total_physical_gb")) {
                            summary.totalPhysicalGb += row.get("total_physical_gb").getDoubleValue();
                        }
                        if (isPresent(row, "total_physical_tb")) {
                            summary.totalPhysicalTb += row.get("total_physical_tb").getDoubleValue();
                        }
                    }
                } catch (Exception e) {
                    log.error("[BQ-QUERY2] Query 2 failed for project {} region {}: {}", projectId, region, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[BQ-OPTIMIZATION-REAL] Failed client build for project {}: {}", projectId, e.getMessage());
        }

        return summary;
    }

    /**
     * 일배치 데이터 수집 및 MERGE 적재
     */
    public void collectAndUpsertBigQueryOptimizationData(String snapshotDate, String projectId, String customerName, GoogleCredentials credentials) {
        ensureTablesExist();
        String snapDate = (snapshotDate != null && snapshotDate.matches("^\\d{4}-\\d{2}-\\d{2}$"))
                ? snapshotDate
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String reportYearMonth = snapDate.substring(0, 7);

        log.info("[BQ-OPTIMIZATION] Dynamic parameterization & collection for project `{}` ({}) snapDate={}",
                projectId, customerName, snapDate);

        // 1. 사용자 원본 쿼리 1 & 2 로 리소스 요약 수집 (가공 없는 RAW 데이터 원본 100% 보존)
        RealUsageSummary usage = queryRealUsageSummaryWithUserQueries(credentials, projectId, reportYearMonth);
        if (!usage.jobQuerySucceeded) {
            // 모든 리전에서 쿼리 1 실패: 0으로 덮어쓰지 않고 기존 데이터 유지
            log.error("[BQ-OPTIMIZATION] Query 1 failed in all regions for {} {} - resource summary NOT written", projectId, reportYearMonth);
            collectTopQueriesWithUserQueries(snapDate, reportYearMonth, projectId, customerName, credentials);
            return;
        }

        long jobCount = usage.jobCount;
        long totalBytesProcessed = usage.totalBytesProcessed;
        long totalBytesBilled = usage.totalBytesBilled;
        double totalTbProcessed = usage.totalTbProcessed;
        double totalTbBilled = usage.totalTbBilled > 0 ? usage.totalTbBilled : usage.totalTbProcessed;
        double logicalGb = usage.totalLogicalGb;
        double physicalGb = usage.totalPhysicalGb;
        double physicalTb = usage.totalPhysicalTb;

        // Resource Summary MERGE INTO
        try {
            String mergeSummarySql = String.format(
                "MERGE INTO `%s.%s.%s` T " +
                "USING ( " +
                "  SELECT DATE('%s') AS snapshot_date, '%s' AS report_year_month, '%s' AS project_id, '%s' AS customer_name, " +
                "         %d AS job_count, %d AS total_bytes_processed, %f AS total_tb_processed, " +
                "         %d AS total_bytes_billed, %f AS total_tb_billed, " +
                "         %f AS total_logical_gb, %f AS total_physical_gb, %f AS total_physical_tb, " +
                "         0.0 AS max_slots, 0.0 AS min_slots, 0.0 AS avg_slots, CURRENT_TIMESTAMP() AS updated_at " +
                ") S " +
                "ON T.snapshot_date = S.snapshot_date AND T.project_id = S.project_id " +
                "WHEN MATCHED THEN " +
                "  UPDATE SET customer_name = S.customer_name, job_count = S.job_count, " +
                "             total_bytes_processed = S.total_bytes_processed, total_tb_processed = S.total_tb_processed, " +
                "             total_bytes_billed = S.total_bytes_billed, total_tb_billed = S.total_tb_billed, " +
                "             total_logical_gb = S.total_logical_gb, total_physical_gb = S.total_physical_gb, " +
                "             total_physical_tb = S.total_physical_tb, updated_at = S.updated_at " +
                "WHEN NOT MATCHED THEN " +
                "  INSERT ROW",
                hostProjectId, datasetName, RESOURCE_SUMMARY_TABLE,
                snapDate, reportYearMonth, projectId, customerName,
                jobCount, totalBytesProcessed, totalTbProcessed,
                totalBytesBilled, totalTbBilled,
                logicalGb, physicalGb, physicalTb
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(mergeSummarySql).build());
            log.info("[BQ-OPTIMIZATION] Successfully upserted resource summary for {} / {} (Jobs: {}, TB: {})",
                    reportYearMonth, projectId, jobCount, totalTbProcessed);
        } catch (Exception e) {
            log.error("Failed to upsert resource summary for {}", projectId, e);
        }

        // 2. 사용자 원본 쿼리 3, 4, 5 로 TOP 10 쿼리 수집
        collectTopQueriesWithUserQueries(snapDate, reportYearMonth, projectId, customerName, credentials);
    }

    /**
     * 사용자 쿼리 3, 4, 5를 통한 TOP 10 수집 및 저장
     */
    private void collectTopQueriesWithUserQueries(String snapDate, String reportYearMonth, String projectId, String customerName, GoogleCredentials credentials) {
        if (credentials == null) return;

        YearMonth ym = YearMonth.parse(reportYearMonth);
        String startTimeStr = reportYearMonth + "-01 00:00:00";
        String endTimeStr = ym.plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM")) + "-01 00:00:00";

        Set<String> regions = discoverProjectRegions(credentials, projectId);

        try {
            BigQuery tenantClient = BigQueryOptions.newBuilder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build()
                    .getService();

            // 분류별 {쿼리 SQL 빌더, 정렬 컬럼}: 리전별 결과를 합쳐 전체 TOP 10을 다시 뽑는다
            String[][] categories = {
                {"HIGH_COST", "total_bytes_processed"},
                {"LONG_DURATION", "execution_time_ms"},
                {"HIGH_SLOT", "total_slot_ms"}
            };
            for (String[] cat : categories) {
                String category = cat[0], sortCol = cat[1];
                List<FieldValueList> rows = new ArrayList<>();
                boolean anySucceeded = false;
                for (String region : regions) {
                    try {
                        String sql = "HIGH_COST".equals(category) ? buildQuery3TopCost(projectId, region, startTimeStr, endTimeStr)
                                : "LONG_DURATION".equals(category) ? buildQuery4TopDuration(projectId, region, startTimeStr, endTimeStr)
                                : buildQuery5TopSlots(projectId, region, startTimeStr, endTimeStr);
                        tenantClient.query(QueryJobConfiguration.newBuilder(sql).build()).iterateAll().forEach(rows::add);
                        anySucceeded = true;
                    } catch (Exception e) {
                        log.error("[BQ-TOP-{}] Query failed for project {} region {}: {}", category, projectId, region, e.getMessage());
                    }
                }
                if (!anySucceeded) {
                    // 모든 리전 실패: 기존 데이터를 지우지 않고 유지
                    log.error("[BQ-TOP-{}] All regions failed for {} {} - existing rows kept", category, projectId, snapDate);
                    continue;
                }

                rows.sort(Comparator.comparingLong((FieldValueList r) -> isPresent(r, sortCol) ? r.get(sortCol).getLongValue() : 0L).reversed());

                // 이전 실행의 잔존 행 제거 후 새 TOP 10 적재
                try {
                    String deleteSql = String.format(
                        "DELETE FROM `%s.%s.%s` WHERE snapshot_date = DATE('%s') AND project_id = '%s' AND query_category = '%s'",
                        hostProjectId, datasetName, TOP_QUERIES_TABLE, snapDate, projectId, category);
                    bigQuery.query(QueryJobConfiguration.newBuilder(deleteSql).build());
                } catch (Exception e) {
                    log.error("[BQ-TOP-{}] Failed to clear previous rows for {} {}: {}", category, projectId, snapDate, e.getMessage());
                    continue;
                }
                int rank = 1;
                for (FieldValueList row : rows.subList(0, Math.min(10, rows.size()))) {
                    upsertSingleTopQuery(snapDate, reportYearMonth, projectId, customerName, category, rank++, row);
                }
            }
        } catch (Exception e) {
            log.error("[BQ-TOP-QUERIES] Failed client build for project {}: {}", projectId, e.getMessage());
        }
    }

    private void upsertSingleTopQuery(String snapDate, String reportYearMonth, String projectId, String customerName,
                                      String category, int rank, FieldValueList row) {
        try {
            String createdDate = !isPresent(row, "crt_dt") ? snapDate : row.get("crt_dt").getStringValue();
            String jobId = !isPresent(row, "job_id") ? "unknown_job" : row.get("job_id").getStringValue();
            String userEmail = !isPresent(row, "user_email") ? "system" : row.get("user_email").getStringValue();
            String statementType = !isPresent(row, "statement_type") ? "SELECT" : row.get("statement_type").getStringValue();
            String queryText = !isPresent(row, "query") ? "" : row.get("query").getStringValue();

            long totalBytesProcessed = isPresent(row, "total_bytes_processed") ? row.get("total_bytes_processed").getLongValue() : 0L;
            double bytesProcessedGb = Math.round((totalBytesProcessed / Math.pow(1024, 3)) * 100.0) / 100.0;
            double estimatedCostUsd = Math.round((bytesProcessedGb / 1024.0 * 6.25) * 100.0) / 100.0;

            long totalSlotMs = isPresent(row, "total_slot_ms") ? row.get("total_slot_ms").getLongValue() : 0L;

            double execSec = 0.0;
            String execFormatted = "00초";
            if (isPresent(row, "execution_time_ms")) {
                long ms = row.get("execution_time_ms").getLongValue();
                execSec = Math.round((ms / 1000.0) * 100.0) / 100.0;
            }
            if (isPresent(row, "execution_time_formatted")) {
                execFormatted = row.get("execution_time_formatted").getStringValue();
            }

            double jobAvgSlots = 0.0;
            if (isPresent(row, "job_average_slots")) {
                jobAvgSlots = row.get("job_average_slots").getDoubleValue();
            }

            // 값은 쿼리 파라미터로 전달 (쿼리 본문의 줄바꿈·백슬래시·작은따옴표로 SQL이 깨져 행이 누락되던 문제 방지)
            String mergeTopSql = String.format(
                "MERGE INTO `%s.%s.%s` T " +
                "USING ( " +
                "  SELECT DATE(@snap) AS snapshot_date, @ym AS report_year_month, @pid AS project_id, @cust AS customer_name, " +
                "         @cat AS query_category, @rank AS rank, @crt AS created_date, @job AS job_id, @user AS user_email, " +
                "         @stmt AS statement_type, @query AS query, @gb AS bytes_processed_gb, @gb AS bytes_billed_gb, " +
                "         @cost AS estimated_cost_usd, @slotMs AS total_slot_ms, @execSec AS execution_time_seconds, @execFmt AS execution_duration_formatted, " +
                "         @avgSlots AS job_average_slots, CURRENT_TIMESTAMP() AS updated_at " +
                ") S " +
                "ON T.snapshot_date = S.snapshot_date AND T.project_id = S.project_id AND T.query_category = S.query_category AND T.rank = S.rank " +
                "WHEN MATCHED THEN " +
                "  UPDATE SET customer_name = S.customer_name, created_date = S.created_date, job_id = S.job_id, user_email = S.user_email, " +
                "             statement_type = S.statement_type, query = S.query, bytes_processed_gb = S.bytes_processed_gb, " +
                "             bytes_billed_gb = S.bytes_billed_gb, estimated_cost_usd = S.estimated_cost_usd, total_slot_ms = S.total_slot_ms, " +
                "             execution_time_seconds = S.execution_time_seconds, execution_duration_formatted = S.execution_duration_formatted, " +
                "             job_average_slots = S.job_average_slots, updated_at = S.updated_at " +
                "WHEN NOT MATCHED THEN " +
                "  INSERT ROW",
                hostProjectId, datasetName, TOP_QUERIES_TABLE
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(mergeTopSql)
                    .addNamedParameter("snap", QueryParameterValue.string(snapDate))
                    .addNamedParameter("ym", QueryParameterValue.string(reportYearMonth))
                    .addNamedParameter("pid", QueryParameterValue.string(projectId))
                    .addNamedParameter("cust", QueryParameterValue.string(customerName))
                    .addNamedParameter("cat", QueryParameterValue.string(category))
                    .addNamedParameter("rank", QueryParameterValue.int64(rank))
                    .addNamedParameter("crt", QueryParameterValue.string(createdDate))
                    .addNamedParameter("job", QueryParameterValue.string(jobId))
                    .addNamedParameter("user", QueryParameterValue.string(userEmail))
                    .addNamedParameter("stmt", QueryParameterValue.string(statementType))
                    .addNamedParameter("query", QueryParameterValue.string(queryText))
                    .addNamedParameter("gb", QueryParameterValue.float64(bytesProcessedGb))
                    .addNamedParameter("cost", QueryParameterValue.float64(estimatedCostUsd))
                    .addNamedParameter("slotMs", QueryParameterValue.int64(totalSlotMs))
                    .addNamedParameter("execSec", QueryParameterValue.float64(execSec))
                    .addNamedParameter("execFmt", QueryParameterValue.string(execFormatted))
                    .addNamedParameter("avgSlots", QueryParameterValue.float64(jobAvgSlots))
                    .build());
        } catch (Exception e) {
            log.error("Failed to upsert single top query for {} {}: {}", projectId, category, e.getMessage());
        }
    }

    /**
     * 월 변경 시 과거 월의 중간 일자 스냅샷(1일~말일 직전) 데이터를 삭제하고
     * 각 과거 월의 최종 일자(MAX snapshot_date, 예: 9월 30일) 스냅샷 1건만 보존
     */
    public void cleanUpPreviousMonthIntermediateSnapshots() {
        String currentYearMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        log.info("[BQ-CLEANUP] Starting snapshot pruning for past months (current month: {})", currentYearMonth);

        try {
            // 1. monthly_bq_resource_summary 정리
            String cleanupResourceSql = String.format(
                "DELETE FROM `%s.%s.%s` " +
                "WHERE report_year_month < '%s' " +
                "  AND snapshot_date < ( " +
                "    SELECT MAX(S.snapshot_date) " +
                "    FROM `%s.%s.%s` S " +
                "    WHERE S.report_year_month = %s.report_year_month " +
                "      AND S.project_id = %s.project_id " +
                "  )",
                hostProjectId, datasetName, RESOURCE_SUMMARY_TABLE, currentYearMonth,
                hostProjectId, datasetName, RESOURCE_SUMMARY_TABLE,
                RESOURCE_SUMMARY_TABLE, RESOURCE_SUMMARY_TABLE
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(cleanupResourceSql).build());
            log.info("[BQ-CLEANUP] Successfully cleaned up intermediate snapshots for {}", RESOURCE_SUMMARY_TABLE);

            // 2. monthly_bq_top_queries 정리
            String cleanupTopQueriesSql = String.format(
                "DELETE FROM `%s.%s.%s` " +
                "WHERE report_year_month < '%s' " +
                "  AND snapshot_date < ( " +
                "    SELECT MAX(S.snapshot_date) " +
                "    FROM `%s.%s.%s` S " +
                "    WHERE S.report_year_month = %s.report_year_month " +
                "      AND S.project_id = %s.project_id " +
                "  )",
                hostProjectId, datasetName, TOP_QUERIES_TABLE, currentYearMonth,
                hostProjectId, datasetName, TOP_QUERIES_TABLE,
                TOP_QUERIES_TABLE, TOP_QUERIES_TABLE
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(cleanupTopQueriesSql).build());
            log.info("[BQ-CLEANUP] Successfully cleaned up intermediate snapshots for {}", TOP_QUERIES_TABLE);

        } catch (Exception e) {
            log.error("[BQ-CLEANUP] Failed to prune past month intermediate snapshots: {}", e.getMessage(), e);
        }
    }

    /**
     * GcpMetricsController 호환용: BigQuery Optimization metrics 조회
     */
    public BigQueryOptimizationDto getBigQueryOptimizationMetrics(String projectId, String targetYearMonth) {
        ensureTablesExist();
        String targetYm = (targetYearMonth != null && targetYearMonth.matches("^\\d{4}-\\d{2}$"))
                ? targetYearMonth
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        String pid = (projectId != null && !projectId.trim().isEmpty()) ? projectId.trim() : hostProjectId;

        // 조회 월 기준 최근 4개월 (오래된 월 → 조회 월)
        YearMonth target = YearMonth.parse(targetYm);
        List<String> months = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            YearMonth m = target.minusMonths(i);
            months.add(m.toString());
            dates.add(m.format(DateTimeFormatter.ofPattern("yy.MM")));
        }

        BigQueryOptimizationDto dto = new BigQueryOptimizationDto();
        dto.setProjectId(pid);
        dto.setTargetYearMonth(targetYm);
        dto.setDates(dates);
        // 적재되지 않은 월은 null (가짜 0 대신 데이터 없음 표시)
        dto.setDataProcessedTbTrend(new ArrayList<>(Collections.nCopies(4, (Double) null)));
        dto.setJobCountTrend(new ArrayList<>(Collections.nCopies(4, (Long) null)));
        dto.setHighCostQueries(new ArrayList<>());
        dto.setLongDurationQueries(new ArrayList<>());
        dto.setHighSlotQueries(new ArrayList<>());
        dto.setSlotHealthStatus("정상");
        dto.setLastUpdated(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        try {
            // 프로젝트별 · 월별 최신 스냅샷 1건씩
            String summarySql = String.format(
                "SELECT * FROM `%s.%s.%s` " +
                "WHERE project_id = @projectId AND report_year_month IN UNNEST(@months) " +
                "QUALIFY ROW_NUMBER() OVER (PARTITION BY report_year_month ORDER BY snapshot_date DESC) = 1",
                hostProjectId, datasetName, RESOURCE_SUMMARY_TABLE
            );
            TableResult sumRes = bigQuery.query(QueryJobConfiguration.newBuilder(summarySql)
                    .addNamedParameter("projectId", QueryParameterValue.string(pid))
                    .addNamedParameter("months", QueryParameterValue.array(months.toArray(new String[0]), String.class))
                    .build());
            for (FieldValueList row : sumRes.iterateAll()) {
                int idx = months.indexOf(row.get("report_year_month").getStringValue());
                if (idx < 0) continue;
                if (isPresent(row, "total_tb_processed")) dto.getDataProcessedTbTrend().set(idx, row.get("total_tb_processed").getDoubleValue());
                if (isPresent(row, "job_count")) dto.getJobCountTrend().set(idx, row.get("job_count").getLongValue());
                if (idx != 3) continue;  // 아래 당월 값은 조회 월 행에서만

                if (isPresent(row, "total_tb_processed")) {
                    dto.setCurrentMonthProcessedTb(row.get("total_tb_processed").getDoubleValue());
                }
                if (isPresent(row, "job_count")) {
                    dto.setCurrentMonthJobCount(row.get("job_count").getLongValue());
                }
                if (isPresent(row, "total_logical_gb")) {
                    dto.setTotalLogicalStorageGb(row.get("total_logical_gb").getDoubleValue());
                }
                if (isPresent(row, "total_physical_gb")) {
                    dto.setTotalPhysicalStorageGb(row.get("total_physical_gb").getDoubleValue());
                }
                if (isPresent(row, "total_physical_tb")) {
                    dto.setTotalPhysicalStorageTb(row.get("total_physical_tb").getDoubleValue());
                }
            }

            String topSql = String.format(
                "SELECT * FROM `%s.%s.%s` " +
                "WHERE project_id = @projectId AND report_year_month = @ym " +
                "  AND snapshot_date = ( " +
                "    SELECT MAX(snapshot_date) FROM `%s.%s.%s` WHERE project_id = @projectId AND report_year_month = @ym " +
                "  ) " +
                "ORDER BY query_category, rank ASC",
                hostProjectId, datasetName, TOP_QUERIES_TABLE,
                hostProjectId, datasetName, TOP_QUERIES_TABLE
            );
            TableResult topRes = bigQuery.query(QueryJobConfiguration.newBuilder(topSql)
                    .addNamedParameter("projectId", QueryParameterValue.string(pid))
                    .addNamedParameter("ym", QueryParameterValue.string(targetYm))
                    .build());
            for (FieldValueList row : topRes.iterateAll()) {
                BigQueryOptimizationDto.BigQueryJobItemDto item = BigQueryOptimizationDto.BigQueryJobItemDto.builder()
                        .rank(isPresent(row, "rank") ? (int) row.get("rank").getLongValue() : 0)
                        .createdDate(isPresent(row, "created_date") ? row.get("created_date").getStringValue() : "")
                        .jobId(isPresent(row, "job_id") ? row.get("job_id").getStringValue() : "")
                        .userEmail(isPresent(row, "user_email") ? row.get("user_email").getStringValue() : "")
                        .statementType(isPresent(row, "statement_type") ? row.get("statement_type").getStringValue() : "")
                        .query(isPresent(row, "query") ? row.get("query").getStringValue() : "")
                        .bytesProcessedGb(isPresent(row, "bytes_processed_gb") ? row.get("bytes_processed_gb").getDoubleValue() : 0.0)
                        .estimatedCostUsd(isPresent(row, "estimated_cost_usd") ? row.get("estimated_cost_usd").getDoubleValue() : 0.0)
                        .totalSlotMs(isPresent(row, "total_slot_ms") ? row.get("total_slot_ms").getLongValue() : 0L)
                        .executionTimeSeconds(isPresent(row, "execution_time_seconds") ? row.get("execution_time_seconds").getDoubleValue() : 0.0)
                        .executionDurationFormatted(isPresent(row, "execution_duration_formatted") ? row.get("execution_duration_formatted").getStringValue() : "")
                        .jobAverageSlots(isPresent(row, "job_average_slots") ? row.get("job_average_slots").getDoubleValue() : 0.0)
                        .build();

                String cat = isPresent(row, "query_category") ? row.get("query_category").getStringValue() : "";
                if ("HIGH_COST".equalsIgnoreCase(cat)) {
                    dto.getHighCostQueries().add(item);
                } else if ("LONG_DURATION".equalsIgnoreCase(cat)) {
                    dto.getLongDurationQueries().add(item);
                } else if ("HIGH_SLOT".equalsIgnoreCase(cat)) {
                    dto.getHighSlotQueries().add(item);
                }
            }
        } catch (Exception e) {
            log.error("Failed to query getBigQueryOptimizationMetrics for {}", targetYm, e);
        }

        return dto;
    }

    /**
     * 단일 프로젝트 · 지정 월만 수집/적재 (전체 백필 대신 프로젝트별로 실행)
     * 스냅샷 일자: 지난 달은 말일, 당월은 오늘
     */
    public Map<String, Object> collectProjectMonths(String projectId, List<String> yearMonths) throws Exception {
        for (InfraEnvironment env : infraEnvironmentService.getAllEnvironments()) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;
            if (env.getProjects().stream().noneMatch(p -> projectId.equals(p.getProjectId()))) continue;

            String secret = infraEnvironmentService.getDecryptedSecret(env.getId());
            if (secret == null || secret.isEmpty()) throw new IllegalStateException("자격증명 없음: " + projectId);
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(secret.getBytes()))
                    .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));
            String customerName = (env.getCustomer() != null && env.getCustomer().getName() != null)
                    ? env.getCustomer().getName() : "Unknown";

            YearMonth current = YearMonth.now();
            List<String> snapshots = new ArrayList<>();
            for (String ym : yearMonths) {
                YearMonth m = YearMonth.parse(ym.trim());
                String snapDate = (m.equals(current) ? LocalDate.now() : m.atEndOfMonth()).toString();
                log.info("[BQ-COLLECT] {} ({}) snapDate={}", projectId, customerName, snapDate);
                collectAndUpsertBigQueryOptimizationData(snapDate, projectId, customerName, credentials);
                snapshots.add(snapDate);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("projectId", projectId);
            res.put("customerName", customerName);
            res.put("snapshots", snapshots);
            return res;
        }
        throw new IllegalArgumentException("등록된 GCP 프로젝트가 아님: " + projectId);
    }

    /**
     * GcpMetricsController 호환용: 전체 GCP 프로젝트 대상 6월부터 10월까지(2026-06 ~ 2026-10) 5개월치 수집 일괄 백필
     */
    public void backfillAllProjects4MonthsBulk() {
        log.info("[BQ-BACKFILL] Triggering 5-month (June~October 2026) full backfill for all GCP projects without conditional filtering or data alteration");
        List<InfraEnvironment> environments = infraEnvironmentService.getAllEnvironments();
        List<String> targetSnapshots = Arrays.asList(
            "2026-06-30",
            "2026-07-31",
            "2026-08-31",
            "2026-09-30",
            "2026-10-02"
        );

        for (InfraEnvironment env : environments) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;
            String decryptedSecret = infraEnvironmentService.getDecryptedSecret(env.getId());
            if (decryptedSecret == null || decryptedSecret.isEmpty()) continue;

            try {
                GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decryptedSecret.getBytes()))
                        .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

                for (CloudProject project : env.getProjects()) {
                    String projectId = project.getProjectId();
                    String customerName = (env.getCustomer() != null && env.getCustomer().getName() != null)
                            ? env.getCustomer().getName() : "Unknown";

                    for (String snapDate : targetSnapshots) {
                        log.info("[BQ-BACKFILL-MONTH] Ingesting month snapshot `{}` for project `{}` ({})", snapDate, projectId, customerName);
                        collectAndUpsertBigQueryOptimizationData(snapDate, projectId, customerName, credentials);
                    }
                }
            } catch (Exception e) {
                log.error("[BQ-BACKFILL] Failed backfill for env: {}", env.getEnvironmentName(), e);
            }
        }
        cleanUpPreviousMonthIntermediateSnapshots();
    }
}
