package com.example.infra;

import com.example.infra.dto.BigQueryOptimizationDto;
import com.example.infra.service.BigQueryOptimizationService;
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
public class BigQueryOptimizationBatchTest {

    @Autowired
    private BigQueryOptimizationService bigQueryOptimizationService;

    @Autowired
    private BigQuery bigQuery;

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("BigQuery 성능 최적화 수집 배치 2회 연속 실행 후 멱등성(Upsert) 및 중복 방지 교차 검증")
    public void testIdempotentUpsertTwiceExecution() throws Exception {
        String testProjectId = "infra-platform";
        String testCustomerName = "밸로프";
        String snapshotDate = "2026-09-21";
        String targetYm = "2026-09";

        bigQueryOptimizationService.recreateTablesForCleanDml();

        System.out.println("\n[1단계] BigQuery 성능 수집 배치 1회차 실행...");
        bigQueryOptimizationService.collectAndUpsertBigQueryOptimizationData(snapshotDate, testProjectId, testCustomerName, null);

        System.out.println("[2단계] 동일 프로젝트/연월에 대해 BigQuery 성능 수집 배치 2회차 연속 실행 (중복 적재 테스트)...");
        bigQueryOptimizationService.collectAndUpsertBigQueryOptimizationData(snapshotDate, testProjectId, testCustomerName, null);

        // 3. 월별 요약 테이블 검증 (정확히 1건 유지 여부)
        String countSummarySql = String.format(
            "SELECT COUNT(*) AS total_rows, MAX(total_tb_processed) AS tb, MAX(job_count) AS jobs, " +
            "       MAX(total_logical_gb) AS logical_gb, MAX(total_physical_gb) AS physical_gb " +
            "FROM `%s.%s.monthly_bq_resource_summary` " +
            "WHERE report_year_month = '%s' AND project_id = '%s'",
            TARGET_PROJECT, DATASET, targetYm, testProjectId
        );

        TableResult summaryRes = bigQuery.query(QueryJobConfiguration.newBuilder(countSummarySql).build());
        long summaryRows = 0;
        double tb = 0.0;
        long jobs = 0;
        double logicalGb = 0.0;
        double physicalGb = 0.0;

        for (FieldValueList row : summaryRes.iterateAll()) {
            summaryRows = row.get("total_rows").getLongValue();
            tb = row.get("tb").getDoubleValue();
            jobs = row.get("jobs").getLongValue();
            logicalGb = row.get("logical_gb").getDoubleValue();
            physicalGb = row.get("physical_gb").getDoubleValue();
        }

        System.out.println(String.format("• [월별 요약 테이블 실측] 총 행 수: %d건 (기대값: 1건) | 데이터 사용량: %.2f TB | Job 수: %d건 | 논리 스토리지: %.1f GB | 물리 스토리지: %.1f GB",
                summaryRows, tb, jobs, logicalGb, physicalGb));

        assertEquals(1, summaryRows, "2번 연속 배치 실행 후에도 월별 요약 레코드는 정확히 1건이어야 합니다 (중복 방지).");

        // 4. TOP 10 쿼리 테이블 검증 (HIGH_COST 10건, LONG_DURATION 10건 = 총 20건 유지 여부)
        String countTopSql = String.format(
            "SELECT query_category, COUNT(*) AS cat_rows " +
            "FROM `%s.%s.monthly_bq_top_queries` " +
            "WHERE report_year_month = '%s' AND project_id = '%s' " +
            "GROUP BY query_category",
            TARGET_PROJECT, DATASET, targetYm, testProjectId
        );

        TableResult topRes = bigQuery.query(QueryJobConfiguration.newBuilder(countTopSql).build());
        int catCount = 0;
        for (FieldValueList row : topRes.iterateAll()) {
            catCount++;
            String cat = row.get("query_category").getStringValue();
            long count = row.get("cat_rows").getLongValue();
            System.out.println(String.format("• [TOP 10 쿼리 테이블 실측] 카테고리: %s -> 총 %d건 (기대값: 10건)", cat, count));
            assertEquals(10, count, cat + " 카테고리의 쿼리 수는 2회 실행 후에도 정확히 10건이어야 합니다 (20건 뻥튀기 방지).");
        }
        assertEquals(2, catCount, "HIGH_COST 및 LONG_DURATION 2개 카테고리가 존재해야 합니다.");

        // 5. API DTO 조회 검증
        BigQueryOptimizationDto dto = bigQueryOptimizationService.getBigQueryOptimizationMetrics(testProjectId, targetYm);
        assertNotNull(dto);
        assertEquals(testProjectId, dto.getProjectId());
        assertEquals(10, dto.getHighCostQueries().size());
        assertEquals(10, dto.getLongDurationQueries().size());
        assertTrue(dto.getMaxSlotUsage() > 0);

        System.out.println("\n🎉 [BigQueryOptimizationBatchTest] 파티셔닝(TTL) & 멱등적 Upsert 중복 방지 교차 검증 100% 통과!\n");
    }

    @Test
    @DisplayName("전체 GCP 고객사 프로젝트 대상 4개월치 BigQuery 성능 및 비용 최적화 데이터 일괄 Upsert 동기화")
    public void testBackfillAllProjects4Months() {
        String[] months = {"2026-06", "2026-07", "2026-08", "2026-09"};
        java.util.Map<String, String> projectsMap = new java.util.LinkedHashMap<>();
        projectsMap.put("hcompany-485701", "한앤컴퍼니");
        projectsMap.put("skshipping", "한앤컴퍼니");
        projectsMap.put("hcompanycsg", "한앤컴퍼니");
        projectsMap.put("skspecialty", "한앤컴퍼니");
        projectsMap.put("ssycne", "한앤컴퍼니");
        projectsMap.put("secu-390423", "카카오헬스케어");
        projectsMap.put("prd-pasta", "카카오헬스케어");
        projectsMap.put("prd-dfd", "카카오헬스케어");
        projectsMap.put("wjis-gw-project", "우진산전");
        projectsMap.put("infra-platform", "밸로프");
        projectsMap.put("ns-user-data", "NS Mall");
        projectsMap.put("ns-intr-data", "NS Mall");
        projectsMap.put("ns-analysis-user", "NS Mall");
        projectsMap.put("ns-pipe-srvc-prod-402505", "NS Mall");
        projectsMap.put("ns-infr-host-402505", "NS Mall");
        projectsMap.put("ns-aiplatform-dev", "NS Mall");
        projectsMap.put("ns-extr-data", "NS Mall");
        projectsMap.put("ns-mart-data", "NS Mall");
        projectsMap.put("ns-dev-ground", "NS Mall");
        projectsMap.put("ns-aiplatform-prd", "NS Mall");

        for (String ym : months) {
            String snapDate = ym + "-25";
            for (java.util.Map.Entry<String, String> entry : projectsMap.entrySet()) {
                bigQueryOptimizationService.collectAndUpsertBigQueryOptimizationData(snapDate, entry.getKey(), entry.getValue(), null);
            }
        }
        System.out.println("✅ 전체 20개 GCP 프로젝트 대상 4개월치 BigQuery 성능 최적화 데이터 롤업 Upsert 완료!");
    }
}
