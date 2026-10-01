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
    @DisplayName("BigQuery 원천 데이터(monthly_bq_resource_summary) vs API 반환 DTO 수치 1:1 비교 검증")
    public void testCompareBigQueryRawDataWithApiResponse() throws Exception {
        System.out.println("\n==========================================================================================");
        System.out.println("🔍 [BigQuery 원천 저장 데이터 vs 리포트 API 응답 DTO 수치 1:1 정밀 교차 비교 검증]");
        System.out.println("==========================================================================================");

        // 1. BigQuery 원천 테이블에서 데이터가 수집된 실제 프로젝트 ID 및 연월 탐색
        String findSampleProjectsSql = String.format(
            "SELECT DISTINCT project_id, customer_name, report_year_month " +
            "FROM `%s.%s.monthly_bq_resource_summary` " +
            "ORDER BY report_year_month DESC LIMIT 5",
            hostProjectId, datasetName
        );

        TableResult sampleResult = bigQuery.query(QueryJobConfiguration.newBuilder(findSampleProjectsSql).build());
        List<String[]> targetProjects = new ArrayList<>();
        for (FieldValueList row : sampleResult.iterateAll()) {
            String pId = row.get("project_id").isNull() ? "" : row.get("project_id").getStringValue();
            String cName = row.get("customer_name").isNull() ? "" : row.get("customer_name").getStringValue();
            String ym = row.get("report_year_month").isNull() ? "" : row.get("report_year_month").getStringValue();
            if (!pId.isEmpty()) {
                targetProjects.add(new String[]{pId, cName, ym});
            }
        }

        if (targetProjects.isEmpty()) {
            System.out.println("⚠️ BigQuery 원천 테이블(`monthly_bq_resource_summary`)에 적재된 프로젝트 데이터가 없습니다.");
            return;
        }

        for (String[] target : targetProjects) {
            String testProjectId = target[0];
            String customerName = target[1];
            String testYearMonth = target[2];

            System.out.println(String.format("\n📌 Target Project ID: %-25s | Customer: %-15s | Target Month: %s", testProjectId, customerName, testYearMonth));

            // 1. BigQuery 원천 테이블 직접 SQL 조회 (Direct Raw Query)
            String rawBqSql = String.format(
                "SELECT report_year_month, project_id, customer_name, job_count, total_bytes_processed, " +
                "total_tb_processed, total_bytes_billed, total_tb_billed, total_logical_gb, total_physical_gb, " +
                "total_physical_tb, max_slots, min_slots, avg_slots " +
                "FROM `%s.%s.monthly_bq_resource_summary` " +
                "WHERE project_id = '%s' AND report_year_month = '%s' " +
                "ORDER BY updated_at DESC LIMIT 1",
                hostProjectId, datasetName, testProjectId, testYearMonth
            );

            TableResult rawResult = bigQuery.query(QueryJobConfiguration.newBuilder(rawBqSql).build());

            double rawTbProcessed = 0.0;
            long rawJobCount = 0L;
            double rawLogicalGb = 0.0;
            double rawPhysicalGb = 0.0;
            double rawMaxSlots = 0.0;
            boolean rawDataExists = false;

            for (FieldValueList row : rawResult.iterateAll()) {
                rawDataExists = true;
                rawTbProcessed = row.get("total_tb_processed").isNull() ? 0.0 : row.get("total_tb_processed").getDoubleValue();
                rawJobCount = row.get("job_count").isNull() ? 0L : row.get("job_count").getLongValue();
                rawLogicalGb = row.get("total_logical_gb").isNull() ? 0.0 : row.get("total_logical_gb").getDoubleValue();
                rawPhysicalGb = row.get("total_physical_gb").isNull() ? 0.0 : row.get("total_physical_gb").getDoubleValue();
                rawMaxSlots = row.get("max_slots").isNull() ? 0.0 : row.get("max_slots").getDoubleValue();
            }

            System.out.println("📊 [1. BigQuery 원천 테이블 실측 데이터 (monthly_bq_resource_summary)]");
            if (rawDataExists) {
                System.out.println(String.format("  - 원천 처리량 (TB): %.3f TB | 원천 Job 수: %d 건 | 논리 스토리지: %.2f GB | 물리 스토리지: %.2f GB | 최대 슬롯: %.1f",
                        rawTbProcessed, rawJobCount, rawLogicalGb, rawPhysicalGb, rawMaxSlots));
            }

            // 2. 백엔드 API 서비스 데이터 조회 (BigQueryOptimizationService)
            BigQueryOptimizationDto apiDto = bigQueryOptimizationService.getBigQueryOptimizationMetrics(testProjectId, testYearMonth);

            System.out.println("📊 [2. 백엔드 API 서비스 응답 데이터 (BigQueryOptimizationDto)]");
            System.out.println(String.format("  - API 처리량 (TB): %.3f TB | API Job 수: %d 건 | 논리 스토리지: %.2f GB | 물리 스토리지: %.2f GB | 최대 슬롯: %.1f",
                    apiDto.getCurrentMonthProcessedTb(), apiDto.getCurrentMonthJobCount(), apiDto.getTotalLogicalStorageGb(), apiDto.getTotalPhysicalStorageGb(), apiDto.getMaxSlotUsage()));

            // 3. 1:1 수치 정밀 교차 검증 (Difference & Accuracy Analysis)
            System.out.println("⚖️ [3. 1:1 수치 정밀 교차 검증 (Difference & Accuracy Analysis)]");
            System.out.println("------------------------------------------------------------------------------------------");
            System.out.println(String.format("| %-20s | %-16s | %-16s | %-10s | %-20s |", "지표 항목 (Metric)", "BigQuery 원천 데이터", "API 응답 DTO 수치", "일치 여부", "오차/차이 원인"));
            System.out.println("------------------------------------------------------------------------------------------");

            checkMetricMatch("Data Processed (TB)", rawTbProcessed, apiDto.getCurrentMonthProcessedTb(), 0.001);
            checkMetricMatch("Job Count (건)", (double) rawJobCount, (double) apiDto.getCurrentMonthJobCount(), 0.0);
            checkMetricMatch("Logical Storage (GB)", rawLogicalGb, apiDto.getTotalLogicalStorageGb(), 0.01);
            checkMetricMatch("Physical Storage (GB)", rawPhysicalGb, apiDto.getTotalPhysicalStorageGb(), 0.01);
            checkMetricMatch("Max Slot Usage", rawMaxSlots, apiDto.getMaxSlotUsage(), 0.1);

            System.out.println("------------------------------------------------------------------------------------------");

            // 4. TOP 5 고비용 쿼리 수치 일치 검증
            String rawTopQuerySql = String.format(
                "SELECT rank, job_id, query_category, estimated_cost_usd, bytes_processed_gb " +
                "FROM `%s.%s.monthly_bq_top_queries` " +
                "WHERE project_id = '%s' AND report_year_month = '%s' AND query_category = 'HIGH_COST' " +
                "ORDER BY rank ASC LIMIT 3",
                hostProjectId, datasetName, testProjectId, testYearMonth
            );
            TableResult rawTopResult = bigQuery.query(QueryJobConfiguration.newBuilder(rawTopQuerySql).build());
            System.out.println("  [BigQuery 원천 TOP 3 고비용 쿼리]");
            for (FieldValueList row : rawTopResult.iterateAll()) {
                long rank = row.get("rank").getLongValue();
                String jobId = row.get("job_id").getStringValue();
                double cost = row.get("estimated_cost_usd").getDoubleValue();
                double gb = row.get("bytes_processed_gb").getDoubleValue();
                System.out.println(String.format("    - BQ Raw  Rank %d: JobID=%s | Cost=$%.4f | Processed=%.2f GB", rank, jobId, cost, gb));
            }

            if (apiDto.getHighCostQueries() != null && !apiDto.getHighCostQueries().isEmpty()) {
                System.out.println("  [API 응답 TOP 3 고비용 쿼리]");
                for (int i = 0; i < Math.min(3, apiDto.getHighCostQueries().size()); i++) {
                    var item = apiDto.getHighCostQueries().get(i);
                    System.out.println(String.format("    - API Dto Rank %d: JobID=%s | Cost=$%.4f | Processed=%.2f GB",
                            item.getRank(), item.getJobId(), item.getEstimatedCostUsd(), item.getBytesProcessedGb()));
                }
            }
        }
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
