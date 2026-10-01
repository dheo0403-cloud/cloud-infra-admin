package com.example.infra;

import com.example.infra.dto.BigQueryOptimizationDto;
import com.example.infra.dto.MonthlyReportDto;
import com.example.infra.service.BigQueryOptimizationService;
import com.example.infra.service.MonthlyReportService;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

/**
 * BigQuery 원천 저장 데이터 vs 백엔드 리포트 API 응답 DTO 간 수치 1:1 정밀 교차 비교 검증 테스트
 */
@SpringBootTest
public class BigQueryVsApiIntegrityVerificationTest {

    @Autowired
    private BigQuery bigQuery;

    @Autowired
    private BigQueryOptimizationService bigQueryOptimizationService;

    @Autowired
    private MonthlyReportService monthlyReportService;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String hostProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    @Test
    @DisplayName("BigQuery 원천 데이터(monthly_bq_resource_summary) vs API 반환 DTO 수치 1:1 비교 검증 (전체 고객사 프로젝트 전수)")
    public void testCompareBigQueryRawDataWithApiResponse() throws Exception {
        System.out.println("\n==========================================================================================");
        System.out.println("🔍 [모든 고객사 GCP 프로젝트별 BigQuery 원천 데이터 vs 리포트 API 응답 수치 1:1 전수 비교]");
        System.out.println("==========================================================================================");

        // 1. BigQuery 원천 테이블에서 모든 고객사 프로젝트의 최신 월별 요약 데이터 일괄 조회 (Bulk Query)
        String rawAllProjectsSql = String.format(
            "SELECT report_year_month, project_id, customer_name, job_count, total_bytes_processed, " +
            "total_tb_processed, total_bytes_billed, total_tb_billed, total_logical_gb, total_physical_gb, " +
            "total_physical_tb, max_slots, min_slots, avg_slots " +
            "FROM `%s.%s.monthly_bq_resource_summary` " +
            "ORDER BY report_year_month DESC, customer_name ASC, project_id ASC",
            hostProjectId, datasetName
        );

        TableResult rawResult = bigQuery.query(QueryJobConfiguration.newBuilder(rawAllProjectsSql).build());

        int count = 0;
        int matchCount = 0;
        int mismatchCount = 0;

        for (FieldValueList row : rawResult.iterateAll()) {
            count++;
            String testProjectId = row.get("project_id").isNull() ? "" : row.get("project_id").getStringValue();
            String customerName = row.get("customer_name").isNull() ? "" : row.get("customer_name").getStringValue();
            String testYearMonth = row.get("report_year_month").isNull() ? "" : row.get("report_year_month").getStringValue();

            if (testProjectId.isEmpty()) continue;

            double rawTbProcessed = row.get("total_tb_processed").isNull() ? 0.0 : row.get("total_tb_processed").getDoubleValue();
            long rawJobCount = row.get("job_count").isNull() ? 0L : row.get("job_count").getLongValue();
            double rawLogicalGb = row.get("total_logical_gb").isNull() ? 0.0 : row.get("total_logical_gb").getDoubleValue();
            double rawPhysicalGb = row.get("total_physical_gb").isNull() ? 0.0 : row.get("total_physical_gb").getDoubleValue();
            double rawMaxSlots = row.get("max_slots").isNull() ? 0.0 : row.get("max_slots").getDoubleValue();

            // 2. 백엔드 API 서비스 응답 데이터 조회
            BigQueryOptimizationDto apiDto = bigQueryOptimizationService.getBigQueryOptimizationMetrics(testProjectId, testYearMonth);

            double apiTbProcessed = apiDto.getCurrentMonthProcessedTb();
            long apiJobCount = apiDto.getCurrentMonthJobCount();
            double apiLogicalGb = apiDto.getTotalLogicalStorageGb();
            double apiPhysicalGb = apiDto.getTotalPhysicalStorageGb();
            double apiMaxSlots = apiDto.getMaxSlotUsage();

            double tbDiff = Math.abs(rawTbProcessed - apiTbProcessed);
            boolean isTbMatch = tbDiff <= 0.005;
            boolean isJobMatch = rawJobCount == apiJobCount;
            boolean isLogicalMatch = Math.abs(rawLogicalGb - apiLogicalGb) <= 0.01;
            boolean isPhysicalMatch = Math.abs(rawPhysicalGb - apiPhysicalGb) <= 0.01;
            boolean isSlotMatch = Math.abs(rawMaxSlots - apiMaxSlots) <= 0.1;

            boolean allMatch = isTbMatch && isJobMatch && isLogicalMatch && isPhysicalMatch && isSlotMatch;
            if (allMatch) {
                matchCount++;
            } else {
                mismatchCount++;
            }

            System.out.println(String.format("\n📌 [%d] 고객사: %-15s | Project ID: %-25s | Target Month: %s", count, customerName, testProjectId, testYearMonth));
            System.out.println(String.format("  - BQ 원천 데이터: 처리량=%.3f TB | Job수=%d건 | 논리스토리지=%.2f GB | 물리스토리지=%.2f GB | 최대슬롯=%.1f",
                    rawTbProcessed, rawJobCount, rawLogicalGb, rawPhysicalGb, rawMaxSlots));
            System.out.println(String.format("  - API 응답 데이터: 처리량=%.3f TB | Job수=%d건 | 논리스토리지=%.2f GB | 물리스토리지=%.2f GB | 최대슬롯=%.1f",
                    apiTbProcessed, apiJobCount, apiLogicalGb, apiPhysicalGb, apiMaxSlots));

            System.out.println(String.format("  - 1:1 수치 비교: [Job수: %s] [논리스토리지: %s] [물리스토리지: %s] [최대슬롯: %s] [처리량: %s (차이: %.4f TB)]",
                    isJobMatch ? "MATCH" : "MISMATCH",
                    isLogicalMatch ? "MATCH" : "MISMATCH",
                    isPhysicalMatch ? "MATCH" : "MISMATCH",
                    isSlotMatch ? "MATCH" : "MISMATCH",
                    isTbMatch ? "MATCH" : "MISMATCH(오차)",
                    tbDiff));
        }

        System.out.println("\n==========================================================================================");
        System.out.println(String.format("📊 [전체 고객사 프로젝트 전수 검증 최종 결과 요약]: 총 %d개 프로젝트 검증 (완벽 일치: %d개, 오차/차이 발생: %d개)",
                count, matchCount, mismatchCount));
        System.out.println("==========================================================================================\n");
    }

    private void checkMetricMatch(String metricName, double bqVal, double apiVal, double tolerance) {
        double diff = Math.abs(bqVal - apiVal);
        boolean isMatch = diff <= tolerance;
        String status = isMatch ? "MATCH (정상)" : "MISMATCH (불일치)";
        String note = isMatch ? "완벽 일치" : String.format("오차 차이: %.3f", diff);

        System.out.println(String.format("| %-20s | %-16.3f | %-16.3f | %-10s | %-20s |",
                metricName, bqVal, apiVal, status, note));
    }
}
