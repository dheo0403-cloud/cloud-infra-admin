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
                "daily_reservation_inventory"
        };

        for (String table : tables) {
            try {
                String countSql = String.format(
                        "SELECT COUNT(*) as cnt FROM `%s.%s.%s` WHERE snapshot_date = '%s'",
                        TARGET_PROJECT, DATASET, table, TARGET_DATE
                );
                TableResult countResult = bigQuery.query(QueryJobConfiguration.newBuilder(countSql).build());
                long count = 0;
                for (FieldValueList row : countResult.iterateAll()) {
                    count = row.get("cnt").getLongValue();
                }
                System.out.println(String.format("✅ [%s] 2026-09-07 적재 건수: %d건", table, count));

                // 프로젝트별 수집 건수 요약
                if (count > 0) {
                    String groupSql = String.format(
                            "SELECT project_id, count(*) as p_cnt FROM `%s.%s.%s` WHERE snapshot_date = '%s' GROUP BY project_id ORDER BY p_cnt DESC",
                            TARGET_PROJECT, DATASET, table, TARGET_DATE
                    );
                    TableResult groupResult = bigQuery.query(QueryJobConfiguration.newBuilder(groupSql).build());
                    System.out.print("   ↳ 프로젝트별 분포: ");
                    StringBuilder sb = new StringBuilder();
                    for (FieldValueList row : groupResult.iterateAll()) {
                        sb.append(row.get("project_id").getStringValue()).append(" (").append(row.get("p_cnt").getLongValue()).append("건), ");
                    }
                    String summary = sb.toString();
                    if (summary.endsWith(", ")) summary = summary.substring(0, summary.length() - 2);
                    System.out.println(summary);
                }
            } catch (Exception e) {
                System.out.println(String.format("⚠️ [%s] 조회 중 예외: %s", table, e.getMessage()));
            }
        }

        System.out.println("=== 🏁 BigQuery 검증 완료 ===");
    }
}
