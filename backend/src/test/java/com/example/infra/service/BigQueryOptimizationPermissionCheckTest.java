package com.example.infra.service;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * [전체 GCP 고객사 프로젝트 BigQuery IAM 권한 및 수집 가능 여부 전수 진단 테스트]
 */
@SpringBootTest
@ActiveProfiles("test")
public class BigQueryOptimizationPermissionCheckTest {

    @Autowired
    private InfraEnvironmentService environmentService;

    @Autowired
    private BigQueryOptimizationService bigQueryOptimizationService;

    @Test
    @DisplayName("전체 GCP 고객사 프로젝트 BigQuery IAM 권한 및 INFORMATION_SCHEMA 접근성 전수 점검")
    public void checkAllProjectsBigQueryPermissions() {
        assertDoesNotThrow(() -> {
            System.out.println("==========================================================================================");
            System.out.println("🔍 [전수 진단] 전체 GCP 고객사 프로젝트 BigQuery Access 및 INFORMATION_SCHEMA IAM 권한 점검 시작");
            System.out.println("==========================================================================================");

            List<InfraEnvironment> environments = environmentService.getAllEnvironments();
            System.out.println("📋 등록된 전체 환경 수: " + environments.size());

            int totalProjects = 0;
            int successProjects = 0;
            int failedProjects = 0;

            for (InfraEnvironment env : environments) {
                if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;

                String customerName = (env.getCustomer() != null && env.getCustomer().getName() != null)
                        ? env.getCustomer().getName() : "Unknown";
                String envName = env.getEnvironmentName();
                String decryptedSecret = environmentService.getDecryptedSecret(env.getId());

                System.out.println("\n🌐 [고객사: " + customerName + " | 환경: " + envName + " (ID: " + env.getId() + ")]");

                if (decryptedSecret == null || decryptedSecret.trim().isEmpty()) {
                    System.out.println("❌ [시크릿키 부재] GCP Service Account Key가 설정되어 있지 않습니다.");
                    continue;
                }

                GoogleCredentials credentials;
                try {
                    credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decryptedSecret.getBytes()))
                            .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));
                } catch (Exception e) {
                    System.out.println("❌ [인증키 파싱 실패] " + e.getMessage());
                    continue;
                }

                if (env.getProjects() == null || env.getProjects().isEmpty()) {
                    System.out.println("⚠️ 등록된 CloudProject가 없습니다.");
                    continue;
                }

                for (CloudProject project : env.getProjects()) {
                    totalProjects++;
                    String projectId = project.getProjectId();
                    System.out.println("\n  📌 Projects Check: [" + projectId + "]");

                    // 1. Dataset 목록 조회 및 리전 탐색 테스트
                    Set<String> discoveredRegions;
                    try {
                        discoveredRegions = bigQueryOptimizationService.discoverProjectRegions(credentials, projectId);
                        System.out.println("    ✅ [리전 탐색 성공] Found Regions: " + discoveredRegions);
                    } catch (Exception e) {
                        System.out.println("    ❌ [리전 탐색 실패] Error: " + e.getMessage());
                        discoveredRegions = Set.of("asia-northeast3", "us");
                    }

                    // 2. 각 탐색된 리전별 INFORMATION_SCHEMA.JOBS 쿼리 실행 테스트
                    boolean projectSuccess = false;
                    for (String region : discoveredRegions) {
                        try {
                            BigQuery tenantClient = BigQueryOptions.newBuilder()
                                    .setCredentials(credentials)
                                    .setProjectId(projectId)
                                    .setLocation(region)
                                    .build()
                                    .getService();

                            String testSql = String.format(
                                "SELECT COUNT(1) AS cnt FROM `%s.region-%s.INFORMATION_SCHEMA.JOBS` WHERE job_type = 'QUERY' AND creation_time >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 7 DAY)",
                                projectId, region.toLowerCase()
                            );

                            TableResult res = tenantClient.query(QueryJobConfiguration.newBuilder(testSql).build());
                            long cnt = 0;
                            for (FieldValueList row : res.iterateAll()) {
                                cnt = row.get("cnt").getLongValue();
                            }

                            System.out.println("    ✅ [QUERY 성공] Region: " + region + " | 최근 7일 Query Job 수: " + cnt);
                            projectSuccess = true;
                        } catch (BigQueryException e) {
                            System.out.println("    ❌ [QUERY 실패] Region: " + region + " | Error Code: " + e.getCode() + " | Message: " + e.getMessage());
                        } catch (Exception e) {
                            System.out.println("    ❌ [QUERY 실패] Region: " + region + " | Exception: " + e.getClass().getName() + " - " + e.getMessage());
                        }
                    }

                    if (projectSuccess) {
                        successProjects++;
                    } else {
                        failedProjects++;
                    }
                }
            }

            System.out.println("\n==========================================================================================");
            System.out.println("📊 [진단 결과 요약]");
            System.out.println("  - 총 프로젝트 수: " + totalProjects);
            System.out.println("  - 정상 접근 가능 프로젝트: " + successProjects);
            System.out.println("  - 권한 부족 / 오류 프로젝트: " + failedProjects);
            System.out.println("==========================================================================================");
        });
    }
}
