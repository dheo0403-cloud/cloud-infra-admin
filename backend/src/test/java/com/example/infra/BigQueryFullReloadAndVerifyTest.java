package com.example.infra;

import com.example.infra.dto.BigQueryOptimizationDto;
import com.example.infra.service.BigQueryOptimizationService;
import com.google.cloud.bigquery.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class BigQueryFullReloadAndVerifyTest {

    @Autowired
    private BigQueryOptimizationService bigQueryOptimizationService;

    @Autowired
    private BigQuery bigQuery;

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("전체 20개 고객사 BigQuery 성능/비용 최적화 4개월 데이터 전면 재적재(Full Reload) 및 타사 실측 교차 검증")
    public void executeFullReloadAndVerifyRandomCustomers() throws Exception {
        System.out.println("\n==========================================================================================");
        System.out.println("🚀 [전 고객사 대상 BigQuery 데이터 전면 재적재(Full Reload) 실행]");
        System.out.println("==========================================================================================");

        long startTime = System.currentTimeMillis();

        // 1. 전체 20개 고객사 대상 4개월치 Clean DML & Bulk Upsert 실행
        bigQueryOptimizationService.backfillAllProjects4MonthsBulk();

        long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println(String.format("✅ [전면 재적재 완료] 20개 전체 프로젝트 4개월치(6~9월) DML 재적재 완료 (소요시간: %d초)\n", elapsedSec));

        // 2. 무작위 타 고객사 3곳 (한앤컴퍼니 hcompany-485701, 밸로프 infra-platform, 우진산전 wjis-gw-project) 실측 검증
        String[] sampleProjects = {"hcompany-485701", "infra-platform", "wjis-gw-project"};
        String[] sampleNames = {"한앤컴퍼니", "밸로프", "우진산전"};

        System.out.println("==========================================================================================");
        System.out.println("🔍 [무작위 타 고객사 3곳 실측 검증 (Grounding & Anti-Hallucination)]");
        System.out.println("==========================================================================================");

        for (int i = 0; i < sampleProjects.length; i++) {
            String pid = sampleProjects[i];
            String cname = sampleNames[i];

            System.out.println(String.format("\n📌 [%d/3] 대상 고객사: %s (프로젝트: %s)", i + 1, cname, pid));

            // DB에서 월별 요약 조회
            String querySummary = String.format(
                "SELECT report_year_month, customer_name, project_id, job_count, " +
                "       total_tb_billed, total_tb_processed, total_logical_gb, total_physical_gb, total_physical_tb, " +
                "       max_slots, min_slots, avg_slots, " +
                "       FORMAT_TIMESTAMP('%%Y-%%m-%%d %%H:%%M:%%S', updated_at, 'Asia/Seoul') AS updated_kst " +
                "FROM `%s.%s.monthly_bq_resource_summary` " +
                "WHERE project_id = '%s' " +
                "ORDER BY report_year_month ASC",
                TARGET_PROJECT, DATASET, pid
            );

            TableResult sumRes = bigQuery.query(QueryJobConfiguration.newBuilder(querySummary).build());
            int rowCount = 0;
            for (FieldValueList row : sumRes.iterateAll()) {
                rowCount++;
                String ym = row.get("report_year_month").getStringValue();
                long jobs = row.get("job_count").getLongValue();
                double tbBilled = row.get("total_tb_billed").getDoubleValue();
                double logGb = row.get("total_logical_gb").getDoubleValue();
                double phyGb = row.get("total_physical_gb").getDoubleValue();
                double maxSlot = row.get("max_slots").getDoubleValue();
                double avgSlot = row.get("avg_slots").getDoubleValue();
                String updatedKst = row.get("updated_kst").getStringValue();

                System.out.println(String.format("   📅 %s | Jobs: %,6d건 | Billed: %6.3f TB | 논리: %6.1f GB | 물리: %6.1f GB | 슬롯: (최대 %.1f / 평균 %.1f) | 갱신: %s",
                        ym, jobs, tbBilled, logGb, phyGb, maxSlot, avgSlot, updatedKst));

                assertTrue(jobs > 0, ym + " Job Count가 0보다 커야 합니다.");
                assertTrue(tbBilled > 0, ym + " Billed TB가 0보다 커야 합니다.");
                assertTrue(logGb > 0, ym + " 논리 스토리지가 0보다 커야 합니다.");
            }
            assertEquals(4, rowCount, pid + "의 4개월치 월별 요약 레코드가 모두 존재해야 합니다.");

            // DB에서 TOP 10 쿼리 수 조회
            String queryTop = String.format(
                "SELECT query_category, COUNT(*) as cnt, " +
                "       FORMAT_TIMESTAMP('%%Y-%%m-%%d %%H:%%M:%%S', MAX(updated_at), 'Asia/Seoul') AS max_updated_kst " +
                "FROM `%s.%s.monthly_bq_top_queries` " +
                "WHERE project_id = '%s' AND report_year_month = '2026-09' " +
                "GROUP BY query_category " +
                "ORDER BY query_category",
                TARGET_PROJECT, DATASET, pid
            );
            TableResult topRes = bigQuery.query(QueryJobConfiguration.newBuilder(queryTop).build());
            for (FieldValueList row : topRes.iterateAll()) {
                String cat = row.get("query_category").getStringValue();
                long cnt = row.get("cnt").getLongValue();
                String topUpdatedKst = row.get("max_updated_kst").getStringValue();
                System.out.println(String.format("   🔥 [9월 TOP 쿼리] 카테고리: %-14s | 건수: %2d건 | 최신갱신: %s", cat, cnt, topUpdatedKst));
                assertEquals(10, cnt, cat + " 카테고리는 10건이어야 합니다.");
            }

            // Service DTO 조회 검증
            BigQueryOptimizationDto dto = bigQueryOptimizationService.getBigQueryOptimizationMetrics(pid, "2026-09");
            assertNotNull(dto);
            assertEquals(pid, dto.getProjectId());
            assertEquals(4, dto.getDataProcessedTbTrend().size());
            assertEquals(4, dto.getJobCountTrend().size());
            assertEquals(10, dto.getHighCostQueries().size());
            assertEquals(10, dto.getLongDurationQueries().size());

            System.out.println(String.format("   ✨ [API DTO 응답 검증 완료] 4개월 TB 추이: %s | 4개월 Job 추이: %s | 당월 슬롯상태: %s",
                    dto.getDataProcessedTbTrend(), dto.getJobCountTrend(), dto.getSlotHealthStatus()));
        }

        // 3. NSMall 실측치(285,447건, 481.08TB, 스토리지 0GB)와 교차 오염 여부 최종 확인
        System.out.println("\n==========================================================================================");
        System.out.println("🎯 [NSMall (ns-user-data) 실측치 및 격리 상태 최종 확인]");
        System.out.println("==========================================================================================");

        BigQueryOptimizationDto nsDto = bigQueryOptimizationService.getBigQueryOptimizationMetrics("ns-user-data", "2026-09");
        assertNotNull(nsDto);
        assertEquals(285447L, nsDto.getCurrentMonthJobCount());
        assertEquals(481.08, nsDto.getCurrentMonthProcessedTb(), 0.05);
        assertEquals(0.0, nsDto.getTotalLogicalStorageGb(), 0.001);
        assertEquals(0.0, nsDto.getTotalPhysicalStorageGb(), 0.001);
        assertEquals(List.of(162.98, 221.17, 341.77, 481.08), nsDto.getDataProcessedTbTrend());
        assertEquals(List.of(318810L, 267205L, 311235L, 285447L), nsDto.getJobCountTrend());

        System.out.println(String.format("• NSMall 9월: %,d건 / %.2f TB | 논리: %.1f GB | 물리: %.1f GB",
                nsDto.getCurrentMonthJobCount(), nsDto.getCurrentMonthProcessedTb(),
                nsDto.getTotalLogicalStorageGb(), nsDto.getTotalPhysicalStorageGb()));
        System.out.println("• NSMall 4개월 TB 추이: " + nsDto.getDataProcessedTbTrend());
        System.out.println("• NSMall 4개월 Job 추이: " + nsDto.getJobCountTrend());
        System.out.println("• NSMall TOP 1 고비용: " + nsDto.getHighCostQueries().get(0).getJobId() + " (" + nsDto.getHighCostQueries().get(0).getUserEmail() + ")");
        System.out.println("• NSMall TOP 1 장기실행: " + nsDto.getLongDurationQueries().get(0).getJobId() + " (" + nsDto.getLongDurationQueries().get(0).getExecutionDurationFormatted() + ")");

        System.out.println("\n🎉 [검증 완료] 전 고객사 대상 BigQuery 데이터 전면 재적재 및 무작위 타 고객사 실측 검증 100% 통과!\n");
    }
}
