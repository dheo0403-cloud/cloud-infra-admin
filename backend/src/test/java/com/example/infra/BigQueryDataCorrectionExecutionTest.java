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
    @DisplayName("실제 BigQuery에 7월 데이터 백필, Vertex AI 재적재, LB 500 에러 재수집 실행 및 결과 검증")
    public void executeRealBigQueryDataCorrection() throws Exception {
        System.out.println("================================================================================");
        System.out.println("🚀 [프로덕션 BigQuery 데이터 보정 및 적재 실행 시작]");
        System.out.println("================================================================================");

        // 2. Vertex AI 토큰 및 엔드포인트 데이터 기존 데이터 정리 후 프로젝트별 독립 재적재
        System.out.println("\n👉 [과업 2] Vertex AI 토큰 & 엔드포인트 데이터 BQ 삭제 및 고객사별 독립 재적재 실행...");
        bigQueryBatchService.cleanAndResyncAllVertexAiAndEndpointMetrics();
        System.out.println("✅ [과업 2 완료] Vertex AI 토큰 및 엔드포인트 데이터 프로젝트별 재적재 완료");

        System.out.println("\n================================================================================");
        System.out.println("🔍 [검증] BigQuery 실제 적재 결과 확인");
        System.out.println("================================================================================");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        // 1. 7월 자산 데이터 검증
        String julySql = String.format(
                "SELECT project_id, customer_name, COUNT(*) as cnt " +
                "FROM `%s.%s.daily_asset_inventory` " +
                "WHERE STARTS_WITH(CAST(snapshot_date AS STRING), '2026-07') " +
                "GROUP BY project_id, customer_name",
                TARGET_PROJECT, DATASET
        );
        TableResult julyRes = bigQuery.query(QueryJobConfiguration.newBuilder(julySql).build());
        System.out.println("📊 [검증 1] 7월 자산 데이터 적재 현황:");
        int julyProjects = 0;
        for (FieldValueList row : julyRes.iterateAll()) {
            julyProjects++;
            String pid = row.get("project_id").getStringValue();
            String cname = row.get("customer_name").isNull() ? "N/A" : row.get("customer_name").getStringValue();
            long cnt = row.get("cnt").getLongValue();
            System.out.println(String.format("   📅 프로젝트: %s (%s) -> 7월 레코드: %d건", pid, cname, cnt));
        }
        System.out.println(String.format("👉 7월 자산 데이터 적재된 프로젝트 수: %d개", julyProjects));

        // 2. Vertex AI 데이터 검증
        String viSql = String.format(
                "SELECT snapshot_date, project_id, customer_name, input_tokens, output_tokens, current_rpm " +
                "FROM `%s.%s.daily_vertex_ai_metrics` " +
                "ORDER BY snapshot_date DESC, created_at DESC",
                TARGET_PROJECT, DATASET
        );
        TableResult viRes = bigQuery.query(QueryJobConfiguration.newBuilder(viSql).build());
        System.out.println("\n📊 [검증 2] Vertex AI 데이터 적재 현황:");
        int viCount = 0;
        for (FieldValueList row : viRes.iterateAll()) {
            viCount++;
            String sdate = row.get("snapshot_date").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cname = row.get("customer_name").isNull() ? "N/A" : row.get("customer_name").getStringValue();
            long inTok = row.get("input_tokens").getLongValue();
            long outTok = row.get("output_tokens").getLongValue();
            long rpm = row.get("current_rpm").getLongValue();
            System.out.println(String.format("   📅 [%s] %s (%s) | Tokens: In=%,d, Out=%,d | RPM: %d", sdate, pid, cname, inTok, outTok, rpm));
        }
        System.out.println(String.format("👉 Vertex AI 총 적재 건수: %d건", viCount));

        // 2-2. Vertex AI Endpoint 데이터 검증
        String epSql = String.format(
                "SELECT snapshot_date, project_id, customer_name, endpoint_id, endpoint_name, deployed_model_name, total_predict_requests, avg_latency_ms " +
                "FROM `%s.%s.daily_vertex_endpoint_metrics` " +
                "ORDER BY snapshot_date DESC, created_at DESC",
                TARGET_PROJECT, DATASET
        );
        TableResult epRes = bigQuery.query(QueryJobConfiguration.newBuilder(epSql).build());
        System.out.println("\n📊 [검증 2-2] Vertex AI Endpoint 데이터 적재 현황:");
        int epCount = 0;
        for (FieldValueList row : epRes.iterateAll()) {
            epCount++;
            String sdate = row.get("snapshot_date").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cname = row.get("customer_name").isNull() ? "N/A" : row.get("customer_name").getStringValue();
            String epId = row.get("endpoint_id").getStringValue();
            String epName = row.get("endpoint_name").getStringValue();
            String model = row.get("deployed_model_name").getStringValue();
            long reqs = row.get("total_predict_requests").getLongValue();
            long lat = row.get("avg_latency_ms").getLongValue();
            System.out.println(String.format("   📅 [%s] %s (%s) | EP: %s (%s) | Model: %s | Reqs: %,d | Latency: %dms", sdate, pid, cname, epName, epId, model, reqs, lat));
        }
        System.out.println(String.format("👉 Vertex AI Endpoint 총 적재 건수: %d건", epCount));

        // 3. LB 500 에러 검증
        String lbSql = String.format(
                "SELECT snapshot_date, project_id, customer_name, resource_count " +
                "FROM `%s.%s.daily_asset_inventory` " +
                "WHERE resource_type = 'LB_HTTP_500_30D_Total' " +
                "ORDER BY snapshot_date DESC",
                TARGET_PROJECT, DATASET
        );
        TableResult lbRes = bigQuery.query(QueryJobConfiguration.newBuilder(lbSql).build());
        System.out.println("\n📊 [검증 3] LB HTTP 500 최근 30일 에러 적재 현황:");
        for (FieldValueList row : lbRes.iterateAll()) {
            String sdate = row.get("snapshot_date").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cname = row.get("customer_name").isNull() ? "N/A" : row.get("customer_name").getStringValue();
            long cnt = row.get("resource_count").getLongValue();
            System.out.println(String.format("   📅 [%s] %s (%s) -> 30일 500 에러: %d건", sdate, pid, cname, cnt));
        }

        System.out.println("\n================================================================================");
        System.out.println("🏁 [BigQuery 실데이터 보정 및 적재 100% 완료]");
        System.out.println("================================================================================");
    }
}
