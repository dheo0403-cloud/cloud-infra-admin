package com.example.infra;

import com.example.infra.service.BigQueryBatchService;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class BigQueryAiRollupAndSummaryTest {

    @Autowired
    private BigQueryBatchService bigQueryBatchService;

    @Autowired
    private BigQuery bigQuery;

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("AI 데이터 월별 집계 롤업(Roll-up) 및 VI 데이터 합산 검증")
    public void testAiRollupExecutionAndSummaryVerification() throws Exception {
        // 1. 월별 롤업 배치 실행
        bigQueryBatchService.rollupAllMonthlyAiSummaries();

        // 2. Direct AI 월별 요약 테이블 검증 (프로젝트/월별 1줄 요약)
        String directSummaryQuery = String.format(
            "SELECT report_year_month, project_id, customer_name, monthly_total_tokens, monthly_vision_calls, monthly_pretrained_calls " +
            "FROM `%s.%s.monthly_direct_ai_summary` " +
            "WHERE report_year_month = '2026-09' " +
            "ORDER BY project_id LIMIT 5",
            TARGET_PROJECT, DATASET
        );

        TableResult directRes = bigQuery.query(QueryJobConfiguration.newBuilder(directSummaryQuery).build());
        int directCount = 0;

        System.out.println("\n================ [Direct AI 월별 집계 롤업 결과 (2026-09)] ================");
        for (FieldValueList row : directRes.iterateAll()) {
            directCount++;
            String ym = row.get("report_year_month").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cust = row.get("customer_name").getStringValue();
            long totTok = row.get("monthly_total_tokens").getLongValue();
            long viCalls = row.get("monthly_vision_calls").getLongValue();
            long preCalls = row.get("monthly_pretrained_calls").getLongValue();

            System.out.println(String.format("• [%s] %s (%s) -> 토큰: %,d | VI(Vision): %,d | API총호출: %,d",
                    ym, cust, pid, totTok, viCalls, preCalls));

            assertTrue(totTok > 0, "월간 총 토큰이 0보다 커야 합니다.");
            assertTrue(viCalls > 0, "VI(Vision AI) 호출 수가 정상적으로 SUM 합산되어야 합니다.");
        }
        System.out.println("===========================================================================\n");

        assertTrue(directCount > 0, "monthly_direct_ai_summary 테이블에 롤업된 레코드가 존재해야 합니다.");

        // 3. Endpoint Serving 월별 요약 테이블 검증
        String servingSummaryQuery = String.format(
            "SELECT report_year_month, project_id, customer_name, endpoint_id, monthly_total_requests, avg_latency_ms " +
            "FROM `%s.%s.monthly_endpoint_serving_summary` " +
            "WHERE report_year_month = '2026-09' " +
            "ORDER BY project_id LIMIT 5",
            TARGET_PROJECT, DATASET
        );

        TableResult servingRes = bigQuery.query(QueryJobConfiguration.newBuilder(servingSummaryQuery).build());
        int servingCount = 0;

        System.out.println("================ [Endpoint Serving 월별 집계 롤업 결과 (2026-09)] ================");
        for (FieldValueList row : servingRes.iterateAll()) {
            servingCount++;
            String ym = row.get("report_year_month").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cust = row.get("customer_name").getStringValue();
            String epId = row.get("endpoint_id").getStringValue();
            long totReq = row.get("monthly_total_requests").getLongValue();
            long latency = row.get("avg_latency_ms").getLongValue();

            System.out.println(String.format("• [%s] %s (%s - %s) -> 월 총요청: %,d | 평균지연: %dms",
                    ym, cust, pid, epId, totReq, latency));

            assertTrue(totReq > 0, "월간 총 요청수가 0보다 커야 합니다.");
        }
        System.out.println("================================================================================\n");

        assertTrue(servingCount > 0, "monthly_endpoint_serving_summary 테이블에 롤업된 레코드가 존재해야 합니다.");
    }
}
