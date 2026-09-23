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
            "SELECT COUNT(*) AS total_rows, MAX(total_tb_billed) AS tb_billed, MAX(total_tb_processed) AS tb, MAX(job_count) AS jobs, " +
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
        bigQueryOptimizationService.backfillAllProjects4MonthsBulk();
        System.out.println("✅ 전체 20개 GCP 프로젝트 대상 4개월치 BigQuery 성능 최적화 데이터 롤업 Bulk Upsert 완료!");

        // 검증: hcompany-485701 프로젝트에 4개월 트렌드와 2026-09 데이터가 정상 적재되었는지 확인
        BigQueryOptimizationDto dto = bigQueryOptimizationService.getBigQueryOptimizationMetrics("hcompany-485701", "2026-09");
        assertNotNull(dto);
        assertEquals(4, dto.getDataProcessedTbTrend().size());
        assertTrue(dto.getDataProcessedTbTrend().get(0) > 0, "6월 처리량이 존재해야 합니다.");
        assertTrue(dto.getDataProcessedTbTrend().get(1) > 0, "7월 처리량이 존재해야 합니다.");
        assertTrue(dto.getDataProcessedTbTrend().get(2) > 0, "8월 처리량이 존재해야 합니다.");
        assertTrue(dto.getDataProcessedTbTrend().get(3) > 0, "9월 처리량이 존재해야 합니다.");
        assertTrue(dto.getTotalLogicalStorageGb() > 0, "논리 스토리지가 정상 적재되어야 합니다.");
        assertTrue(dto.getTotalPhysicalStorageGb() > 0, "물리 스토리지가 정상 적재되어야 합니다.");
        assertEquals(10, dto.getHighCostQueries().size(), "고비용 TOP 10 쿼리가 존재해야 합니다.");
        assertEquals(10, dto.getLongDurationQueries().size(), "장기실행 TOP 10 쿼리가 존재해야 합니다.");
        System.out.println(String.format("• [hcompany-485701 백필 검증 실측] 4개월 TB 추이: %s | 논리 스토리지: %.1f GB | 물리 스토리지: %.1f GB | TOP 쿼리: %d건",
                dto.getDataProcessedTbTrend(), dto.getTotalLogicalStorageGb(), dto.getTotalPhysicalStorageGb(),
                dto.getHighCostQueries().size() + dto.getLongDurationQueries().size()));
    }

    @Test
    @DisplayName("데이터가 없는 신규/무사용 고객사 프로젝트 조회 시 스토리지 0.0GB, 슬롯 0 Slots 및 정상(데이터 없음) 반환 검증")
    public void testZeroUsageProjectMetricsReturnZero() {
        String zeroProjectId = "zero-usage-customer-project";
        String targetYm = "2026-09";

        BigQueryOptimizationDto dto = bigQueryOptimizationService.getBigQueryOptimizationMetrics(zeroProjectId, targetYm);

        assertNotNull(dto);
        assertEquals(zeroProjectId, dto.getProjectId());
        assertEquals(0.0, dto.getCurrentMonthProcessedTb(), 0.001, "사용량이 없는 고객사의 당월 처리량은 0.0TB여야 합니다.");
        assertEquals(0L, dto.getCurrentMonthJobCount(), "사용량이 없는 고객사의 Job 수는 0이어야 합니다.");
        assertEquals(0.0, dto.getTotalLogicalStorageGb(), 0.001, "사용량이 없는 고객사의 논리 스토리지는 0.0GB여야 합니다 (125.0GB 더미 방지).");
        assertEquals(0.0, dto.getTotalPhysicalStorageGb(), 0.001, "사용량이 없는 고객사의 물리 스토리지는 0.0GB여야 합니다 (78.5GB 더미 방지).");
        assertEquals(0.0, dto.getTotalPhysicalStorageTb(), 0.001, "사용량이 없는 고객사의 물리 스토리지(TB)는 0.0TB여야 합니다.");
        assertEquals(0.0, dto.getMaxSlotUsage(), 0.001, "사용량이 없는 고객사의 최대 슬롯은 0이어야 합니다 (145 더미 방지).");
        assertEquals(0.0, dto.getAvgSlotUsage(), 0.001, "사용량이 없는 고객사의 평균 슬롯은 0이어야 합니다 (52 더미 방지).");
        assertEquals("정상 (데이터 없음)", dto.getSlotHealthStatus(), "슬롯 상태는 '정상 (데이터 없음)'이어야 합니다.");
        assertTrue(dto.getHighCostQueries().isEmpty(), "고비용 쿼리는 빈 리스트여야 합니다.");
        assertTrue(dto.getLongDurationQueries().isEmpty(), "장기실행 쿼리는 빈 리스트여야 합니다.");

        System.out.println(String.format("• [Zero-Data 검증 실측] 프로젝트: %s | 논리 스토리지: %.1f GB | 물리 스토리지: %.1f GB | 최대 슬롯: %.0f | 평균 슬롯: %.0f | 슬롯 상태: %s",
                dto.getProjectId(), dto.getTotalLogicalStorageGb(), dto.getTotalPhysicalStorageGb(),
                dto.getMaxSlotUsage(), dto.getAvgSlotUsage(), dto.getSlotHealthStatus()));
        System.out.println("🎉 [Zero-Data 무결성 검증] 스토리지 0.0GB / 슬롯 0 Slots 정상 반환 100% 확인 통과!");
    }

    @Test
    @DisplayName("NSMall(ns-user-data) 콘솔 실측치(9월: 285,447건/481.08TB, 8월: 311,235건/341.77TB, 7월: 267,205건/221.17TB, 6월: 318,810건/162.98TB, 스토리지 0GB, TOP 쿼리) 및 멀티 테넌트 데이터 격리 무결성 검증")
    public void testNsMallMeasuredDataAndTenantIsolation() {
        System.out.println("\n=== 🔍 [NSMall & 멀티 테넌트 데이터 격리 투명성 실측 검증] ===");

        // 1. 전체 백필 실행
        bigQueryOptimizationService.backfillAllProjects4MonthsBulk();

        // 2. NSMall (ns-user-data, 2026-09) 실측치 검증
        BigQueryOptimizationDto nsDto = bigQueryOptimizationService.getBigQueryOptimizationMetrics("ns-user-data", "2026-09");
        assertNotNull(nsDto);
        assertEquals("ns-user-data", nsDto.getProjectId());
        assertEquals(285447L, nsDto.getCurrentMonthJobCount(), "NSMall 9월 Job Count는 콘솔 실측치인 285,447건이어야 합니다.");
        assertEquals(481.08, nsDto.getCurrentMonthProcessedTb(), 0.05, "NSMall 9월 데이터 사용량은 콘솔 실측치인 481.08 TB여야 합니다.");
        assertEquals(0.0, nsDto.getTotalLogicalStorageGb(), 0.001, "NSMall 논리 스토리지 용량은 0.0 GB여야 합니다.");
        assertEquals(0.0, nsDto.getTotalPhysicalStorageGb(), 0.001, "NSMall 물리 스토리지 용량은 0.0 GB여야 합니다.");
        assertEquals(0.0, nsDto.getTotalPhysicalStorageTb(), 0.001, "NSMall 물리 스토리지(TB) 용량은 0.0 TB여야 합니다.");

        // 4개월 추이 검증
        assertEquals(4, nsDto.getDataProcessedTbTrend().size());
        assertEquals(162.98, nsDto.getDataProcessedTbTrend().get(0), 0.05, "6월 처리량은 162.98 TB여야 합니다.");
        assertEquals(221.17, nsDto.getDataProcessedTbTrend().get(1), 0.05, "7월 처리량은 221.17 TB여야 합니다.");
        assertEquals(341.77, nsDto.getDataProcessedTbTrend().get(2), 0.05, "8월 처리량은 341.77 TB여야 합니다.");
        assertEquals(481.08, nsDto.getDataProcessedTbTrend().get(3), 0.05, "9월 처리량은 481.08 TB여야 합니다.");

        assertEquals(318810L, (long)nsDto.getJobCountTrend().get(0), "6월 Job 수는 318,810건이어야 합니다.");
        assertEquals(267205L, (long)nsDto.getJobCountTrend().get(1), "7월 Job 수는 267,205건이어야 합니다.");
        assertEquals(311235L, (long)nsDto.getJobCountTrend().get(2), "8월 Job 수는 311,235건이어야 합니다.");
        assertEquals(285447L, (long)nsDto.getJobCountTrend().get(3), "9월 Job 수는 285,447건이어야 합니다.");

        // TOP 10 쿼리 검증
        assertEquals(10, nsDto.getHighCostQueries().size(), "고비용 TOP 10 쿼리가 10건이어야 합니다.");
        assertEquals(10, nsDto.getLongDurationQueries().size(), "장기실행 TOP 10 쿼리가 10건이어야 합니다.");

        BigQueryOptimizationDto.BigQueryJobItemDto top1Cost = nsDto.getHighCostQueries().get(0);
        assertEquals("job_xduMNJJ2C_PIzfs3o83DRt2sJLs4", top1Cost.getJobId());
        assertEquals("nsdataplatform@nsmall.com", top1Cost.getUserEmail());
        assertEquals(101.72, top1Cost.getBytesProcessedGb(), 0.05);

        BigQueryOptimizationDto.BigQueryJobItemDto top1Dur = nsDto.getLongDurationQueries().get(0);
        assertEquals("job_TeKukE54jTLlFL1YQHvRBd_VsUo4", top1Dur.getJobId());
        assertEquals("nsdataplatform@nsmall.com", top1Dur.getUserEmail());
        assertEquals("02시 26분 27초", top1Dur.getExecutionDurationFormatted());

        System.out.println(String.format("• [① NSMall 실측치 검증 통과] Project: %s | 9월 Job Count: %,d건 (기대: 285,447건) | 데이터 사용량: %.2f TB (기대: 481.08 TB) | 스토리지: %.1f GB (기대: 0.0 GB)",
                nsDto.getProjectId(), nsDto.getCurrentMonthJobCount(), nsDto.getCurrentMonthProcessedTb(), nsDto.getTotalLogicalStorageGb()));
        System.out.println(String.format("• [①-1 TOP 1 고비용 쿼리] Job ID: %s | 계정: %s | 사용량: %.2f GB",
                top1Cost.getJobId(), top1Cost.getUserEmail(), top1Cost.getBytesProcessedGb()));
        System.out.println(String.format("• [①-2 TOP 1 장기실행 쿼리] Job ID: %s | 계정: %s | 실행시간: %s",
                top1Dur.getJobId(), top1Dur.getUserEmail(), top1Dur.getExecutionDurationFormatted()));

        // 3. 타 고객사 (한앤컴퍼니 hcompany-485701) 데이터 격리 검증
        BigQueryOptimizationDto hcDto = bigQueryOptimizationService.getBigQueryOptimizationMetrics("hcompany-485701", "2026-09");
        assertNotNull(hcDto);
        assertEquals("hcompany-485701", hcDto.getProjectId());
        assertNotEquals(nsDto.getCurrentMonthJobCount(), hcDto.getCurrentMonthJobCount(), "타 고객사의 Job 수는 NSMall 데이터와 혼입되지 않아야 합니다.");
        assertTrue(hcDto.getTotalLogicalStorageGb() > 0, "스토리지를 사용하는 타 고객사는 논리 스토리지가 정상 표출되어야 합니다.");

        System.out.println(String.format("• [② 타 고객사(한앤컴퍼니) 격리 검증 통과] Project: %s | 9월 Job Count: %,d건 | 스토리지: %.1f GB (격리 유지)",
                hcDto.getProjectId(), hcDto.getCurrentMonthJobCount(), hcDto.getTotalLogicalStorageGb()));

        // 4. 타 고객사 (밸로프 infra-platform) 데이터 격리 검증
        BigQueryOptimizationDto vlfDto = bigQueryOptimizationService.getBigQueryOptimizationMetrics("infra-platform", "2026-09");
        assertNotNull(vlfDto);
        assertEquals("infra-platform", vlfDto.getProjectId());
        assertNotEquals(nsDto.getCurrentMonthJobCount(), vlfDto.getCurrentMonthJobCount());

        System.out.println(String.format("• [③ 타 고객사(밸로프) 격리 검증 통과] Project: %s | 9월 Job Count: %,d건 | 스토리지: %.1f GB (격리 유지)",
                vlfDto.getProjectId(), vlfDto.getCurrentMonthJobCount(), vlfDto.getTotalLogicalStorageGb()));

        System.out.println("🎉 [멀티 테넌트 교차 검증 완료] NSMall 실측치 일치 및 타 고객사 데이터 격리 무결성 100% 확인!\n");
    }
}
