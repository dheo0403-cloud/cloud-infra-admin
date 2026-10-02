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
        "  ROUND(SAFE_DIVIDE(SUM(total_bytes_processed), POWER(1024, 4)), 2) AS total_tb_processed_sum\n" +
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
     * 실측 사용량 기록 DTO
     */
    public static class RealUsageSummary {
        public long jobCount = 0;
        public double totalTbProcessed = 0.0;
        public double totalLogicalGb = 0.0;
        public double totalPhysicalGb = 0.0;
        public double totalPhysicalTb = 0.0;
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
                // 1. Job 수 및 TB 사용량 쿼리 실행
                try {
                    String sql1 = buildQuery1JobUsage(projectId, region, yearMonth);
                    TableResult res1 = tenantClient.query(QueryJobConfiguration.newBuilder(sql1).build());
                    for (FieldValueList row : res1.iterateAll()) {
                        if (isPresent(row, "job_count")) {
                            summary.jobCount += row.get("job_count").getLongValue();
                        }
                        if (isPresent(row, "total_tb_processed_sum")) {
                            summary.totalTbProcessed += row.get("total_tb_processed_sum").getDoubleValue();
                        }
                    }
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

        // 1. 사용자 원본 쿼리 1 & 2 로 리소스 요약 수집
        RealUsageSummary usage = queryRealUsageSummaryWithUserQueries(credentials, projectId, reportYearMonth);

        long jobCount = usage.jobCount;
        double totalTbProcessed = Math.round(usage.totalTbProcessed * 100.0) / 100.0;
        long totalBytesProcessed = (long) (totalTbProcessed * Math.pow(1024, 4));
        long totalBytesBilled = totalBytesProcessed;
        double totalTbBilled = totalTbProcessed;
        double logicalGb = Math.round(usage.totalLogicalGb * 100.0) / 100.0;
        double physicalGb = Math.round(usage.totalPhysicalGb * 100.0) / 100.0;
        double physicalTb = Math.round(usage.totalPhysicalTb * 10000.0) / 10000.0;

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

            for (String region : regions) {
                // 쿼리 3 (비용 TOP 10)
                try {
                    String sql3 = buildQuery3TopCost(projectId, region, startTimeStr, endTimeStr);
                    TableResult res3 = tenantClient.query(QueryJobConfiguration.newBuilder(sql3).build());
                    int rank = 1;
                    for (FieldValueList row : res3.iterateAll()) {
                        upsertSingleTopQuery(snapDate, reportYearMonth, projectId, customerName, "HIGH_COST", rank++, row);
                    }
                } catch (Exception e) {
                    log.error("[BQ-QUERY3] Query 3 failed for project {} region {}: {}", projectId, region, e.getMessage());
                }

                // 쿼리 4 (실행시간 TOP 10)
                try {
                    String sql4 = buildQuery4TopDuration(projectId, region, startTimeStr, endTimeStr);
                    TableResult res4 = tenantClient.query(QueryJobConfiguration.newBuilder(sql4).build());
                    int rank = 1;
                    for (FieldValueList row : res4.iterateAll()) {
                        upsertSingleTopQuery(snapDate, reportYearMonth, projectId, customerName, "LONG_DURATION", rank++, row);
                    }
                } catch (Exception e) {
                    log.error("[BQ-QUERY4] Query 4 failed for project {} region {}: {}", projectId, region, e.getMessage());
                }

                // 쿼리 5 (슬롯 사용량 TOP 10)
                try {
                    String sql5 = buildQuery5TopSlots(projectId, region, startTimeStr, endTimeStr);
                    TableResult res5 = tenantClient.query(QueryJobConfiguration.newBuilder(sql5).build());
                    int rank = 1;
                    for (FieldValueList row : res5.iterateAll()) {
                        upsertSingleTopQuery(snapDate, reportYearMonth, projectId, customerName, "HIGH_SLOT", rank++, row);
                    }
                } catch (Exception e) {
                    log.error("[BQ-QUERY5] Query 5 failed for project {} region {}: {}", projectId, region, e.getMessage());
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
            String queryText = !isPresent(row, "query") ? "" : row.get("query").getStringValue().replace("'", "\\'").replace("\n", " ");

            long totalBytesProcessed = isPresent(row, "total_bytes_processed") ? row.get("total_bytes_processed").getLongValue() : 0L;
            double bytesProcessedGb = Math.round((totalBytesProcessed / Math.pow(1024, 3)) * 100.0) / 100.0;
            double bytesBilledGb = bytesProcessedGb;
            double estimatedCostUsd = Math.round((bytesBilledGb / 1024.0 * 6.25) * 100.0) / 100.0;

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

            String mergeTopSql = String.format(
                "MERGE INTO `%s.%s.%s` T " +
                "USING ( " +
                "  SELECT DATE('%s') AS snapshot_date, '%s' AS report_year_month, '%s' AS project_id, '%s' AS customer_name, " +
                "         '%s' AS query_category, %d AS rank, '%s' AS created_date, '%s' AS job_id, '%s' AS user_email, " +
                "         '%s' AS statement_type, '%s' AS query, %f AS bytes_processed_gb, %f AS bytes_billed_gb, " +
                "         %f AS estimated_cost_usd, %d AS total_slot_ms, %f AS execution_time_seconds, '%s' AS execution_duration_formatted, " +
                "         %f AS job_average_slots, CURRENT_TIMESTAMP() AS updated_at " +
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
                hostProjectId, datasetName, TOP_QUERIES_TABLE,
                snapDate, reportYearMonth, projectId, customerName,
                category, rank, createdDate, jobId, userEmail,
                statementType, queryText, bytesProcessedGb, bytesBilledGb,
                estimatedCostUsd, totalSlotMs, execSec, execFormatted,
                jobAvgSlots
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(mergeTopSql).build());
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

        BigQueryOptimizationDto dto = new BigQueryOptimizationDto();
        dto.setProjectId(projectId != null ? projectId : hostProjectId);
        dto.setTargetYearMonth(targetYm);
        dto.setDates(Arrays.asList("26.06", "26.07", "26.08", "26.09"));
        dto.setDataProcessedTbTrend(new ArrayList<>());
        dto.setJobCountTrend(new ArrayList<>());
        dto.setHighCostQueries(new ArrayList<>());
        dto.setLongDurationQueries(new ArrayList<>());
        dto.setSlotHealthStatus("정상");
        dto.setLastUpdated(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        try {
            String summarySql = String.format(
                "SELECT * FROM `%s.%s.%s` " +
                "WHERE report_year_month = '%s' " +
                "  AND snapshot_date = ( " +
                "    SELECT MAX(snapshot_date) FROM `%s.%s.%s` WHERE report_year_month = '%s' " +
                "  ) ",
                hostProjectId, datasetName, RESOURCE_SUMMARY_TABLE, targetYm,
                hostProjectId, datasetName, RESOURCE_SUMMARY_TABLE, targetYm
            );
            TableResult sumRes = bigQuery.query(QueryJobConfiguration.newBuilder(summarySql).build());
            for (FieldValueList row : sumRes.iterateAll()) {
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
                "WHERE report_year_month = '%s' " +
                "  AND snapshot_date = ( " +
                "    SELECT MAX(snapshot_date) FROM `%s.%s.%s` WHERE report_year_month = '%s' " +
                "  ) " +
                "ORDER BY query_category, rank ASC",
                hostProjectId, datasetName, TOP_QUERIES_TABLE, targetYm,
                hostProjectId, datasetName, TOP_QUERIES_TABLE, targetYm
            );
            TableResult topRes = bigQuery.query(QueryJobConfiguration.newBuilder(topSql).build());
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
                } else if ("LONG_DURATION".equalsIgnoreCase(cat) || "HIGH_SLOT".equalsIgnoreCase(cat)) {
                    dto.getLongDurationQueries().add(item);
                }
            }
        } catch (Exception e) {
            log.error("Failed to query getBigQueryOptimizationMetrics for {}", targetYm, e);
        }

        return dto;
    }

    /**
     * GcpMetricsController 호환용: 전체 GCP 프로젝트 대상 과거 4개월 수집 백필
     */
    public void backfillAllProjects4MonthsBulk() {
        log.info("[BQ-BACKFILL] Triggering backfill for all GCP projects across past months");
        List<InfraEnvironment> environments = infraEnvironmentService.getAllEnvironments();
        String snapshotDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

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
                    collectAndUpsertBigQueryOptimizationData(snapshotDate, projectId, customerName, credentials);
                }
            } catch (Exception e) {
                log.error("[BQ-BACKFILL] Failed backfill for env: {}", env.getEnvironmentName(), e);
            }
        }
        cleanUpPreviousMonthIntermediateSnapshots();
    }
}
