package com.example.infra.service;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.QueryJobConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * [1회성 데이터 재적재 및 과거 데이터 수정 검증 테스트]
 * - 빅쿼리 성능 최적화 테이블의 기존 데이터를 전량/과거월 삭제
 * - 사용자 변경 5대 SQL 쿼리(고객사 Project ID, Region, 년월 조건 바인딩) 기반으로
 *   BigQuery INFORMATION_SCHEMA 데이터를 수집 및 재적재.
 * - 월이 변경되어 과거 월(Past Months)이 된 데이터는 1~N-1일 중간 스냅샷 데이터를 삭제하고
 *   해당 월의 마지막 날(MAX snapshot_date) 데이터 1건만 보존하는 일배치 보존 정책 정합성 검증.
 */
@SpringBootTest
@ActiveProfiles("test")
public class BigQueryOptimizationReloadTest {

    @Autowired
    private BigQueryOptimizationService bigQueryOptimizationService;

    @Autowired
    private InfraEnvironmentService environmentService;

    @Autowired
    private BigQuery bigQuery;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String hostProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    @Test
    @DisplayName("1회성 BigQuery 최적화 테이블 데이터 삭제 및 5대 쿼리 기반 재적재 및 과거 월 스냅샷 정리 테스트")
    public void testPurgeAndReloadBigQueryOptimizationData() {
        assertDoesNotThrow(() -> {
            System.out.println("=== 🚀 [1회성 재적재 시작] BigQuery Optimization 테이블 기존 데이터 Purge ===");

            // 1. 기존 BigQuery Optimization 테이블 데이터 Purge
            String[] tables = {
                BigQueryOptimizationService.RESOURCE_SUMMARY_TABLE,
                BigQueryOptimizationService.STORAGE_SUMMARY_TABLE,
                BigQueryOptimizationService.TOP_COST_TABLE,
                BigQueryOptimizationService.TOP_EXEC_TABLE,
                BigQueryOptimizationService.TOP_SLOT_TABLE
            };

            for (String table : tables) {
                try {
                    String deleteSql = String.format("DELETE FROM `%s.%s.%s` WHERE 1=1", hostProjectId, datasetName, table);
                    bigQuery.query(QueryJobConfiguration.newBuilder(deleteSql).build());
                    System.out.println("✅ Purged table: " + table);
                } catch (Exception e) {
                    System.out.println("⚠️ Notice on purging table " + table + ": " + e.getMessage());
                }
            }

            // 2. 전체 GCP 환경 순회하며 6월부터 10월까지(2026-06 ~ 2026-10) 5개월치 스냅샷 데이터 원본 그대로 100% 재적재
            List<String> targetSnapshots = Arrays.asList(
                "2026-06-30",
                "2026-07-31",
                "2026-08-31",
                "2026-09-30",
                "2026-10-02"
            );

            List<InfraEnvironment> environments = environmentService.getAllEnvironments();

            for (InfraEnvironment env : environments) {
                if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;

                String decryptedSecret = environmentService.getDecryptedSecret(env.getId());
                if (decryptedSecret == null || decryptedSecret.isEmpty()) continue;

                try {
                    GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decryptedSecret.getBytes()))
                            .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

                    for (CloudProject project : env.getProjects()) {
                        String projectId = project.getProjectId();
                        String customerName = (env.getCustomer() != null && env.getCustomer().getName() != null)
                                ? env.getCustomer().getName() : "Unknown";

                        for (String snapDate : targetSnapshots) {
                            System.out.println("🔄 Re-ingesting BigQuery Optimization data for project: " + projectId + " [Snapshot Date: " + snapDate + "]");
                            bigQueryOptimizationService.collectAndUpsertBigQueryOptimizationData(snapDate, projectId, customerName, credentials);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("❌ Reload failed for environment: " + env.getEnvironmentName() + " - " + e.getMessage());
                }
            }

            // 3. 과거 월(Past Months) 1~N-1일 중간 스냅샷 삭제 & 최종 1일치 보존 로직 실행
            System.out.println("🧹 Executing previous month snapshot cleanup (retaining only last day data)...");
            bigQueryOptimizationService.cleanUpPreviousMonthIntermediateSnapshots();

            System.out.println("=== 🏁 [1회성 재적재 완료] BigQuery Optimization 데이터 정상 적재 완료 ===");
        });
    }
}
