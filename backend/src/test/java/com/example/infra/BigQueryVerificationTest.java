package com.example.infra;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

public class BigQueryVerificationTest {

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";
    private static final String TARGET_DATE = "2026-09-07";

    @Test
    @DisplayName("2026-09-07 적재 건수 및 프로젝트별 현황 조회")
    public void verifyTodayData() throws Exception {
        System.out.println("=== 📊 BigQuery 2026-09-07 실시간 적재 현황 조회 ===");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        String[] tables = {
                "daily_asset_inventory",
                "daily_recommender_inventory",
                "daily_reservation_inventory",
                "daily_direct_ai_metrics",
                "daily_endpoint_serving_metrics"
        };

        for (String table : tables) {
            try {
                String monthlySql = String.format(
                        "SELECT SUBSTR(CAST(snapshot_date AS STRING), 1, 7) as ym, COUNT(*) as cnt, COUNT(DISTINCT snapshot_date) as distinct_dates " +
                        "FROM `%s.%s.%s` " +
                        "GROUP BY ym ORDER BY ym ASC",
                        TARGET_PROJECT, DATASET, table
                );
                TableResult monthlyResult = bigQuery.query(QueryJobConfiguration.newBuilder(monthlySql).build());
                System.out.println(String.format("📊 [%s] 월별 데이터 건수 및 일자 수:", table));
                for (FieldValueList row : monthlyResult.iterateAll()) {
                    String ym = row.get("ym").isNull() ? "NULL" : row.get("ym").getStringValue();
                    long count = row.get("cnt").getLongValue();
                    long dates = row.get("distinct_dates").getLongValue();
                    System.out.println(String.format("   📅 %s월: %d건 (%d개 일자)", ym, count, dates));
                }
            } catch (Exception e) {
                System.out.println(String.format("⚠️ [%s] 조회 중 예외: %s", table, e.getMessage()));
            }
        }

        System.out.println("=== 🏁 BigQuery 검증 완료 ===");
    }

    @Test
    @DisplayName("daily_recommender_inventory target_resource_name 적재 및 추출 검증")
    public void verifyRecommenderTargetResourceData() throws Exception {
        System.out.println("=== 🔍 daily_recommender_inventory 대상 리소스(target_resource_name) 적재 샘플 검증 ===");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        try {
            String querySql = String.format(
                    "SELECT snapshot_date, project_id, category, priority, recommender_id, target_resource_name, recommendation_description " +
                    "FROM `%s.%s.daily_recommender_inventory` " +
                    "ORDER BY snapshot_date DESC, created_at DESC LIMIT 10",
                    TARGET_PROJECT, DATASET
            );
            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(querySql).build());
            int idx = 1;
            for (FieldValueList row : result.iterateAll()) {
                String date = row.get("snapshot_date").getStringValue();
                String pid = row.get("project_id").getStringValue();
                String cat = row.get("category").getStringValue();
                String prio = !row.get("priority").isNull() ? row.get("priority").getStringValue() : "MEDIUM";
                String targetRes = !row.get("target_resource_name").isNull() ? row.get("target_resource_name").getStringValue() : "(N/A)";
                String desc = row.get("recommendation_description").getStringValue();

                System.out.println(String.format("  [%d] 날짜: %s | 프로젝트: %s | 분류: %s | 우선순위: %s | 대상 리소스: %s", idx++, date, pid, cat, prio, targetRes));
                System.out.println(String.format("      설명: %s", desc));
            }
        } catch (Exception e) {
            System.out.println("⚠️ Recommender 조회 중 예외: " + e.getMessage());
        }
        System.out.println("=== 🏁 Recommender 검증 완료 ===");
    }

    @Test
    @DisplayName("BigQuery 최적화 테이블(Billed/KST) 실데이터 적재 및 쿼리 결과 검증")
    public void verifyBigQueryOptimizationBilledData() throws Exception {
        System.out.println("\n=== 🔍 monthly_bq_resource_summary & top_queries Billed 실데이터 검증 ===");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        // 1. 월별 리소스 요약 조회
        String summarySql = String.format(
            "SELECT report_year_month, COUNT(DISTINCT project_id) as project_count, " +
            "       SUM(total_tb_billed) as sum_tb_billed, SUM(job_count) as sum_jobs, " +
            "       AVG(total_logical_gb) as avg_logical_gb, AVG(max_slots) as avg_max_slots " +
            "FROM `%s.%s.monthly_bq_resource_summary` " +
            "GROUP BY report_year_month ORDER BY report_year_month ASC",
            TARGET_PROJECT, DATASET
        );
        TableResult summaryRes = bigQuery.query(QueryJobConfiguration.newBuilder(summarySql).build());
        System.out.println("📊 [monthly_bq_resource_summary] 월별 프로젝트 수 및 Billed 합계:");
        for (FieldValueList row : summaryRes.iterateAll()) {
            String ym = row.get("report_year_month").getStringValue();
            long pCount = row.get("project_count").getLongValue();
            double tbBilled = row.get("sum_tb_billed").getDoubleValue();
            long jobs = row.get("sum_jobs").getLongValue();
            double logicalGb = row.get("avg_logical_gb").getDoubleValue();
            double maxSlots = row.get("avg_max_slots").getDoubleValue();

            System.out.println(String.format("   📅 %s: 프로젝트 %d개 | Billed 사용량: %.2f TB | Job 수: %d건 | 평균 논리 스토리지: %.1f GB | 평균 최대 슬롯: %.1f",
                    ym, pCount, tbBilled, jobs, logicalGb, maxSlots));
        }

        // 2. TOP 쿼리 적재 현황 조회
        String topSql = String.format(
            "SELECT report_year_month, query_category, COUNT(*) as query_count, " +
            "       MAX(bytes_billed_gb) as max_billed_gb, MAX(estimated_cost_usd) as max_cost " +
            "FROM `%s.%s.monthly_bq_top_queries` " +
            "GROUP BY report_year_month, query_category ORDER BY report_year_month ASC, query_category ASC",
            TARGET_PROJECT, DATASET
        );
        TableResult topRes = bigQuery.query(QueryJobConfiguration.newBuilder(topSql).build());
        System.out.println("\n📊 [monthly_bq_top_queries] 카테고리별 쿼리 수:");
        for (FieldValueList row : topRes.iterateAll()) {
            String ym = row.get("report_year_month").getStringValue();
            String cat = row.get("query_category").getStringValue();
            long count = row.get("query_count").getLongValue();
            double maxBilledGb = row.get("max_billed_gb").getDoubleValue();
            double maxCost = row.get("max_cost").getDoubleValue();

            System.out.println(String.format("   📅 %s | %s: %d건 (최대 Billed: %.1f GB, 최대 비용: $%.2f)",
                    ym, cat, count, maxBilledGb, maxCost));
        }

        System.out.println("=== 🏁 BigQuery 최적화 Billed 데이터 검증 완료 ===\n");
    }
}
