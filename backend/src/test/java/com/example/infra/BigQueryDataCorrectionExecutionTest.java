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

@SpringBootTest
public class BigQueryDataCorrectionExecutionTest {

    @Autowired
    private BigQueryBatchService bigQueryBatchService;

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("신규 듀얼 고객 분류 체계(Direct AI & Endpoint Serving) BigQuery 초기화 및 전체 고객사 실데이터 적재/검증")
    public void executeRealBigQueryDataCorrection() throws Exception {
        System.out.println("================================================================================");
        System.out.println("🚀 [신규 듀얼 AI 고객 분류 체계 BigQuery 전면 재구축 및 적재 실행]");
        System.out.println("================================================================================");

        // 1. 듀얼 AI 파이프라인 전면 초기화 및 20개 프로젝트 실데이터 수집/적재
        System.out.println("\n👉 [과업 실행] 레거시 테이블 삭제, 듀얼 테이블 신설 및 전체 프로젝트 실데이터 수집...");
        bigQueryBatchService.cleanAndResyncAllDualAiMetrics();
        System.out.println("✅ [과업 완료] 듀얼 AI 지표 데이터 프로젝트별 재적재 완료");

        System.out.println("\n================================================================================");
        System.out.println("🔍 [검증] BigQuery 실제 적재 결과 확인 (투명성 교차 검증)");
        System.out.println("================================================================================");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        // 1. Direct AI Usage 데이터 검증
        String directSql = String.format(
                "SELECT snapshot_date, project_id, customer_name, total_tokens, pretrained_api_calls, training_node_hours, total_estimated_daily_cost " +
                "FROM `%s.%s.daily_direct_ai_metrics` " +
                "ORDER BY snapshot_date DESC, created_at DESC",
                TARGET_PROJECT, DATASET
        );
        TableResult directRes = bigQuery.query(QueryJobConfiguration.newBuilder(directSql).build());
        System.out.println("\n📊 [검증 1] Direct AI Usage 데이터 적재 현황:");
        int directCount = 0;
        for (FieldValueList row : directRes.iterateAll()) {
            directCount++;
            String sdate = row.get("snapshot_date").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cname = row.get("customer_name").isNull() ? "N/A" : row.get("customer_name").getStringValue();
            long tokens = row.get("total_tokens").getLongValue();
            long pretrained = row.get("pretrained_api_calls").getLongValue();
            double trainingHours = row.get("training_node_hours").getDoubleValue();
            double cost = row.get("total_estimated_daily_cost").getDoubleValue();
            System.out.println(String.format("   📅 [%s] %s (%s) | Tokens: %,d | Pretrained: %,d | Training: %.1fh | Cost: $%.2f",
                    sdate, pid, cname, tokens, pretrained, trainingHours, cost));
        }
        System.out.println(String.format("👉 Direct AI 총 적재 레코드: %d건", directCount));

        // 2. Endpoint Serving 데이터 검증
        String servingSql = String.format(
                "SELECT snapshot_date, project_id, customer_name, endpoint_id, endpoint_name, deployed_model_name, total_requests, qps, avg_latency_ms, accelerator_type, hourly_serving_cost " +
                "FROM `%s.%s.daily_endpoint_serving_metrics` " +
                "ORDER BY snapshot_date DESC, created_at DESC",
                TARGET_PROJECT, DATASET
        );
        TableResult servingRes = bigQuery.query(QueryJobConfiguration.newBuilder(servingSql).build());
        System.out.println("\n📊 [검증 2] Endpoint Serving 데이터 적재 현황:");
        int servingCount = 0;
        for (FieldValueList row : servingRes.iterateAll()) {
            servingCount++;
            String sdate = row.get("snapshot_date").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cname = row.get("customer_name").isNull() ? "N/A" : row.get("customer_name").getStringValue();
            String epId = row.get("endpoint_id").getStringValue();
            String model = row.get("deployed_model_name").getStringValue();
            long reqs = row.get("total_requests").getLongValue();
            double qps = row.get("qps").getDoubleValue();
            int lat = (int) row.get("avg_latency_ms").getLongValue();
            String gpu = row.get("accelerator_type").getStringValue();
            double cost = row.get("hourly_serving_cost").getDoubleValue();
            System.out.println(String.format("   📅 [%s] %s (%s) | EP: %s (%s) | Reqs: %,d | QPS: %.2f | Lat: %dms | GPU: %s | Cost: $%.2f/h",
                    sdate, pid, cname, epId, model, reqs, qps, lat, gpu, cost));
        }
        System.out.println(String.format("👉 Endpoint Serving 총 적재 레코드: %d건", servingCount));

        // 3. GROUP BY project_id 검증 쿼리
        String groupDirectSql = String.format(
                "SELECT project_id, COUNT(*) as cnt FROM `%s.%s.daily_direct_ai_metrics` GROUP BY project_id",
                TARGET_PROJECT, DATASET
        );
        TableResult grpDirectRes = bigQuery.query(QueryJobConfiguration.newBuilder(groupDirectSql).build());
        System.out.println("\n📊 [검증 3] Direct AI Project Grouping:");
        for (FieldValueList row : grpDirectRes.iterateAll()) {
            System.out.println(String.format("   📌 Project: %s -> %d건", row.get("project_id").getStringValue(), row.get("cnt").getLongValue()));
        }

        String groupServingSql = String.format(
                "SELECT project_id, COUNT(*) as cnt FROM `%s.%s.daily_endpoint_serving_metrics` GROUP BY project_id",
                TARGET_PROJECT, DATASET
        );
        TableResult grpServingRes = bigQuery.query(QueryJobConfiguration.newBuilder(groupServingSql).build());
        System.out.println("\n📊 [검증 4] Endpoint Serving Project Grouping:");
        for (FieldValueList row : grpServingRes.iterateAll()) {
            System.out.println(String.format("   📌 Project: %s -> %d건", row.get("project_id").getStringValue(), row.get("cnt").getLongValue()));
        }
    }
}
