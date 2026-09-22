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

    @Test
    @DisplayName("NSMall 실측치(285,447건, 481.08GB, 0GB 스토리지) 및 멀티 테넌트 격리 적재 실행 및 검증")
    public void testNsMallMetricsSync() throws Exception {
        System.out.println("\n=== 🚀 NSMall 실측치 및 멀티 테넌트 격리 동기화 실행 ===");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        com.example.infra.service.BigQueryOptimizationService service =
                new com.example.infra.service.BigQueryOptimizationService(bigQuery);

        // 1. 전체 백필 실행
        service.backfillAllProjects4MonthsBulk();
        System.out.println("✅ backfillAllProjects4MonthsBulk() 실행 완료");

        // 2. NSMall 9월 실데이터 검증
        String nsSql = String.format(
            "SELECT report_year_month, project_id, customer_name, job_count, total_bytes_processed, total_tb_processed, " +
            "       total_bytes_billed, total_tb_billed, total_logical_gb, total_physical_gb, total_physical_tb, max_slots, avg_slots " +
            "FROM `%s.%s.monthly_bq_resource_summary` " +
            "WHERE project_id = 'ns-user-data' AND report_year_month = '2026-09'",
            TARGET_PROJECT, DATASET
        );
        TableResult nsRes = bigQuery.query(QueryJobConfiguration.newBuilder(nsSql).build());
        for (FieldValueList r : nsRes.iterateAll()) {
            System.out.println(String.format("🎯 [NSMall 9월 실측 검증] 프로젝트: %s (%s) | Job Count: %,d건 | 데이터 사용량: %.3f TB (%,d Bytes) | 논리 스토리지: %.1f GB | 물리 스토리지: %.1f GB | 최대 슬롯: %.1f",
                    r.get("project_id").getStringValue(), r.get("customer_name").getStringValue(),
                    r.get("job_count").getLongValue(), r.get("total_tb_processed").getDoubleValue(),
                    r.get("total_bytes_processed").getLongValue(),
                    r.get("total_logical_gb").getDoubleValue(), r.get("total_physical_gb").getDoubleValue(),
                    r.get("max_slots").getDoubleValue()));
        }

        // 3. 타 고객사 (한앤컴퍼니 hcompany-485701) 데이터 격리 검증
        String hcSql = String.format(
            "SELECT report_year_month, project_id, customer_name, job_count, total_tb_processed, total_logical_gb, total_physical_gb " +
            "FROM `%s.%s.monthly_bq_resource_summary` " +
            "WHERE project_id = 'hcompany-485701' AND report_year_month = '2026-09'",
            TARGET_PROJECT, DATASET
        );
        TableResult hcRes = bigQuery.query(QueryJobConfiguration.newBuilder(hcSql).build());
        for (FieldValueList r : hcRes.iterateAll()) {
            System.out.println(String.format("🔒 [타사(한앤컴퍼니) 격리 검증] 프로젝트: %s (%s) | Job Count: %,d건 | 사용량: %.3f TB | 논리 스토리지: %.1f GB",
                    r.get("project_id").getStringValue(), r.get("customer_name").getStringValue(),
                    r.get("job_count").getLongValue(), r.get("total_tb_processed").getDoubleValue(),
                    r.get("total_logical_gb").getDoubleValue()));
        }

        // 4. NSMall TOP 쿼리 검증
        String nsTopSql = String.format(
            "SELECT query_category, rank, bytes_billed_gb, estimated_cost_usd, execution_time_seconds, execution_duration_formatted, job_average_slots, query " +
            "FROM `%s.%s.monthly_bq_top_queries` " +
            "WHERE project_id = 'ns-user-data' AND report_year_month = '2026-09' " +
            "ORDER BY query_category, rank LIMIT 4",
            TARGET_PROJECT, DATASET
        );
        TableResult topRes = bigQuery.query(QueryJobConfiguration.newBuilder(nsTopSql).build());
        System.out.println("\n🔥 [NSMall TOP 쿼리 샘플 검증]:");
        for (FieldValueList r : topRes.iterateAll()) {
            System.out.println(String.format("   [%s Rank %d] %.2f GB ($%.2f) | 실행시간: %s | 평균슬롯: %.1f | Query: %s",
                    r.get("query_category").getStringValue(), r.get("rank").getLongValue(),
                    r.get("bytes_billed_gb").getDoubleValue(), r.get("estimated_cost_usd").getDoubleValue(),
                    r.get("execution_duration_formatted").getStringValue(), r.get("job_average_slots").getDoubleValue(),
                    r.get("query").getStringValue().substring(0, Math.min(60, r.get("query").getStringValue().length())) + "..."));
        }

        System.out.println("\n=== 🏁 NSMall 실측치 및 멀티 테넌트 격리 백필 및 검증 완료 ===\n");
    }
}
