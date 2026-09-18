package com.example.infra;

import com.example.infra.service.BigQueryBatchService;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class BigQueryGlobalSnapshotBatchTest {

    @Autowired
    private BigQueryBatchService bigQueryBatchService;

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("전체 일일 배치 실행 및 Vertex AI 인서트 & 과거 월 1건 스냅샷 압축 검증")
    public void testFullDailyBatchAndSnapshotCleanups() throws Exception {
        System.out.println("================================================================================");
        System.out.println("🚀 [1단계] 전체 일일 배치 파이프라인 (runDailySnapshotBatch) 로컬 단독 1회 실행 시작");
        System.out.println("================================================================================");

        // 1. 배치 전체 실행
        bigQueryBatchService.runDailySnapshotBatch();

        System.out.println("✅ [1단계 완료] 전체 일일 배치 및 과거 월 통합 스냅샷 정리 실행 완료");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        String currentMonth = LocalDate.now().toString().substring(0, 7); // "2026-09"

        System.out.println("\n================================================================================");
        System.out.println("🔍 [검증 1] Direct AI 테이블 (`daily_direct_ai_metrics`) 9월(당월) 데이터 인서트 및 건수 확인");
        System.out.println("================================================================================");

        String vertexAiSql = String.format(
                "SELECT project_id, CAST(snapshot_date AS STRING) as sdate, customer_name, input_tokens, output_tokens, current_rpm, created_at " +
                "FROM `%s.%s.daily_direct_ai_metrics` " +
                "WHERE STARTS_WITH(CAST(snapshot_date AS STRING), '%s') " +
                "ORDER BY snapshot_date DESC, created_at DESC",
                TARGET_PROJECT, DATASET, currentMonth
        );

        TableResult vertexRes = bigQuery.query(QueryJobConfiguration.newBuilder(vertexAiSql).build());
        int vertexCount = 0;
        for (FieldValueList row : vertexRes.iterateAll()) {
            vertexCount++;
            String pid = row.get("project_id").getStringValue();
            String sdate = row.get("sdate").getStringValue();
            String cname = row.get("customer_name").isNull() ? "N/A" : row.get("customer_name").getStringValue();
            long inTok = row.get("input_tokens").getLongValue();
            long outTok = row.get("output_tokens").getLongValue();
            long rpm = row.get("current_rpm").getLongValue();
            System.out.println(String.format("  [%d] 날짜: %s | 프로젝트: %s (%s) | InputTokens: %,d | OutputTokens: %,d | RPM: %d",
                    vertexCount, sdate, pid, cname, inTok, outTok, rpm));
        }
        System.out.println(String.format("👉 9월 Direct AI 데이터 총 건수: %d건 적재 확인 완료!", vertexCount));
        assertTrue(vertexCount > 0, "Direct AI 9월 데이터가 반드시 1건 이상 존재해야 합니다.");

        System.out.println("\n================================================================================");
        System.out.println("🔍 [검증 2] 과거 데이터 정리 쿼리 검증 (과거 5, 6, 7, 8월 월별 1개 일자 보존 & 당월 일별 누적)");
        System.out.println("================================================================================");

        String[] tables = {
                "daily_asset_inventory",
                "daily_reservation_inventory",
                "daily_recommender_inventory",
                "daily_direct_ai_metrics",
                "daily_endpoint_serving_metrics"
        };

        for (String table : tables) {
            String monthlyGroupSql = String.format(
                    "SELECT " +
                    "  SUBSTR(CAST(snapshot_date AS STRING), 1, 7) as ym, " +
                    "  COUNT(*) as row_count, " +
                    "  COUNT(DISTINCT snapshot_date) as distinct_dates, " +
                    "  MAX(CAST(snapshot_date AS STRING)) as latest_snapshot " +
                    "FROM `%s.%s.%s` " +
                    "GROUP BY ym ORDER BY ym ASC",
                    TARGET_PROJECT, DATASET, table
            );
            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(monthlyGroupSql).build());
            System.out.println(String.format("\n📌 테이블: `%s`", table));
            for (FieldValueList row : result.iterateAll()) {
                String ym = row.get("ym").getStringValue();
                long count = row.get("row_count").getLongValue();
                long distinctDates = row.get("distinct_dates").getLongValue();
                String maxDate = row.get("latest_snapshot").getStringValue();

                boolean isCurrentMonth = ym.equals(currentMonth);
                String status = isCurrentMonth ? "🟢 당월 (일별 누적 유지)" : (distinctDates == 1 ? "🟢 과거 월 (최신 1일치 압축 완료)" : "⚠️ 과거 월 다건 일자 존재");

                System.out.println(String.format("   📅 %s월 | 총 레코드: %,6d건 | 보존 일자 수: %d개 (최신일자: %s) -> %s",
                        ym, count, distinctDates, maxDate, status));

                if (!isCurrentMonth) {
                    assertTrue(distinctDates <= 1, String.format("과거 월(%s)은 최대 1개 일자 스냅샷만 남아야 합니다. (현재: %d개)", ym, distinctDates));
                }
            }
        }

        System.out.println("\n================================================================================");
        System.out.println("🏁 [최종 검증 완료] 모든 검증 기준을 100% 만족하였습니다!");
        System.out.println("================================================================================");
    }
}
