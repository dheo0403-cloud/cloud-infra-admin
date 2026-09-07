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

public class BigQueryDataResetTest {

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";
    private static final String TARGET_DATE = "2026-09-07";

    @Test
    @DisplayName("2026-09-07 일자 빅쿼리 데이터 삭제 및 확인")
    public void resetBigQueryDataForToday() throws Exception {
        System.out.println("=== 🚀 BigQuery 2026-09-07 데이터 정리 시작 ===");

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
                "jira_issue_inventory"
        };

        for (String table : tables) {
            try {
                // 1. 삭제 전 건수 확인
                String countSql = String.format(
                        "SELECT COUNT(*) as cnt FROM `%s.%s.%s` WHERE snapshot_date = '%s'",
                        TARGET_PROJECT, DATASET, table, TARGET_DATE
                );
                TableResult countResult = bigQuery.query(QueryJobConfiguration.newBuilder(countSql).build());
                long beforeCount = 0;
                for (FieldValueList row : countResult.iterateAll()) {
                    beforeCount = row.get("cnt").getLongValue();
                }
                System.out.println(String.format("📊 [%s] 2026-09-07 건수: %d건", table, beforeCount));

                if (beforeCount > 0) {
                    // DML DELETE 시도 -> 스트리밍 버퍼 에러 시 CREATE OR REPLACE TABLE (CTAS) 로 대체
                    try {
                        String deleteSql = String.format(
                                "DELETE FROM `%s.%s.%s` WHERE snapshot_date = '%s'",
                                TARGET_PROJECT, DATASET, table, TARGET_DATE
                        );
                        bigQuery.query(QueryJobConfiguration.newBuilder(deleteSql).build());
                        System.out.println(String.format("🗑️ [%s] DML DELETE 완료 (%d건 삭제됨)", table, beforeCount));
                    } catch (Exception dmlEx) {
                        if (dmlEx.getMessage() != null && dmlEx.getMessage().contains("streaming buffer")) {
                            System.out.println(String.format("⚡ [%s] 스트리밍 버퍼 감지 -> CREATE OR REPLACE TABLE (CTAS) 로 정제 실행", table));
                            String ctasSql = String.format(
                                    "CREATE OR REPLACE TABLE `%s.%s.%s` AS " +
                                    "SELECT * FROM `%s.%s.%s` WHERE snapshot_date != '%s'",
                                    TARGET_PROJECT, DATASET, table, TARGET_PROJECT, DATASET, table, TARGET_DATE
                            );
                            bigQuery.query(QueryJobConfiguration.newBuilder(ctasSql).build());
                            System.out.println(String.format("✅ [%s] CTAS 테이블 정제 완료 (2026-09-07 데이터 전량 제외)", table));
                        } else {
                            throw dmlEx;
                        }
                    }

                    // 2. 삭제 후 건수 재확인
                    TableResult afterResult = bigQuery.query(QueryJobConfiguration.newBuilder(countSql).build());
                    long afterCount = 0;
                    for (FieldValueList row : afterResult.iterateAll()) {
                        afterCount = row.get("cnt").getLongValue();
                    }
                    System.out.println(String.format("✨ [%s] 삭제 후 2026-09-07 잔여 건수: %d건", table, afterCount));
                } else {
                    System.out.println(String.format("ℹ️ [%s] 삭제 대상 2026-09-07 데이터 없음", table));
                }
            } catch (Exception e) {
                System.out.println(String.format("⚠️ [%s] 처리 중 예외: %s", table, e.getMessage()));
            }
        }

        System.out.println("=== ✅ BigQuery 2026-09-07 데이터 정리 완료 ===");
    }
}
