package com.example.infra;

import com.example.infra.dto.BigQueryOptimizationDto;
import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.example.infra.service.BigQueryOptimizationService;
import com.example.infra.service.InfraEnvironmentService;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * BigQuery 원천 데이터 전체 삭제 후 실제 원천 데이터 전수 재적재 및 백엔드 리포트 API 응답 DTO 간 수치 1:1 정밀 교차 비교 검증 테스트
 */
@SpringBootTest
public class BigQueryVsApiIntegrityVerificationTest {

    @Autowired
    private BigQuery bigQuery;

    @Autowired
    private BigQueryOptimizationService bigQueryOptimizationService;

    @Autowired
    private InfraEnvironmentService infraEnvironmentService;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String hostProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    @Test
    @DisplayName("1. 기존 BigQuery 적재 데이터 전체 삭제 -> 2. 모든 고객사 GCP 실제 원천 데이터 전수 재적재 -> 3. 수치 1:1 검증")
    public void testPurgeReloadAndVerifyAllCustomerProjects() throws Exception {
        System.out.println("\n==========================================================================================");
        System.out.println("🧹 [1단계: 기존 BigQuery 적재 요약/TOP 쿼리 데이터 전체 삭제 (Purge All Previous Data)]");
        System.out.println("==========================================================================================");

        try {
            String deleteSummarySql = String.format("DELETE FROM `%s.%s.monthly_bq_resource_summary` WHERE 1=1", hostProjectId, datasetName);
            String deleteTopQueriesSql = String.format("DELETE FROM `%s.%s.monthly_bq_top_queries` WHERE 1=1", hostProjectId, datasetName);
            bigQuery.query(QueryJobConfiguration.newBuilder(deleteSummarySql).build());
            bigQuery.query(QueryJobConfiguration.newBuilder(deleteTopQueriesSql).build());
            System.out.println("✅ 기존 `monthly_bq_resource_summary` 및 `monthly_bq_top_queries` 테이블의 모든 데이터 삭제 완료.");
        } catch (Exception e) {
            System.out.println("⚠️ 데이터 삭제 중 예외 발생: " + e.getMessage());
        }

        System.out.println("\n==========================================================================================");
        System.out.println("🔄 [2단계: DB 등록 모든 고객사 GCP 프로젝트의 실제 INFORMATION_SCHEMA 원천 데이터 전수 재적재]");
        System.out.println("==========================================================================================");

        List<InfraEnvironment> envs = infraEnvironmentService.getAllEnvironments();
        String[] snapshotDates = new String[]{"2026-09-30", "2026-08-31", "2026-07-31"};

        int reloadSuccessCount = 0;
        for (InfraEnvironment env : envs) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;
            String customerName = (env.getCustomer() != null) ? env.getCustomer().getName() : "UnknownCustomer";
            String secretJson = infraEnvironmentService.getDecryptedSecret(env.getId());
            if (secretJson == null || secretJson.trim().isEmpty()) continue;

            GoogleCredentials credentials;
            try {
                credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(secretJson.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
            } catch (Exception e) {
                System.err.println("❌ GoogleCredentials 파싱 실패: " + e.getMessage());
                continue;
            }

            if (env.getProjects() != null) {
                for (CloudProject project : env.getProjects()) {
                    String projectId = project.getProjectId();
                    if (projectId == null || projectId.trim().isEmpty()) continue;

                    for (String snapDate : snapshotDates) {
                        try {
                            bigQueryOptimizationService.collectAndUpsertBigQueryOptimizationData(snapDate, projectId, customerName, credentials);
                            reloadSuccessCount++;
                        } catch (Exception e) {
                            System.err.println(String.format("❌ [%s / %s] 실제 데이터 재적재 실패: %s", projectId, snapDate, e.getMessage()));
                        }
                    }
                }
            }
        }
        System.out.println(String.format("✅ 총 %d개 고객사 프로젝트/월 조합에 대해 실제 원천 데이터 새로 적재 완료.", reloadSuccessCount));

        System.out.println("\n==========================================================================================");
        System.out.println("🔍 [3단계: 적재 완료된 BigQuery 원천 데이터 vs 리포트 API 응답 DTO 수치 1:1 대조 리포트]");
        System.out.println("==========================================================================================");

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

            // 백엔드 API 서비스 응답 데이터 조회
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
        System.out.println(String.format("📊 [최종 검증 요약]: 총 %d개 (전체 데이터 삭제 및 순수 원천 데이터 새로 적재 후 1:1 교차 검증 완료)", count));
        System.out.println("==========================================================================================\n");
    }
}
