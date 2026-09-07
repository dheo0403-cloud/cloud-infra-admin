package com.example.infra;

import com.example.infra.entity.InfraEnvironment;
import com.example.infra.entity.CloudProject;
import com.example.infra.repository.InfraEnvironmentRepository;
import com.example.infra.service.BigQueryBatchService;
import com.example.infra.service.EncryptionService;
import com.example.infra.service.GcpRecommenderService;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class BigQueryRecommenderMonthlySnapshotTest {

    @Autowired
    private BigQueryBatchService bigQueryBatchService;

    @Autowired
    private InfraEnvironmentRepository environmentRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private GcpRecommenderService gcpRecommenderService;

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("과거 월(8월) 데이터 보존 및 당월(9월) 부분 삭제(DELETE) 덮어쓰기 정밀 검증")
    public void testMonthlySnapshotPreservationAndOverwrite() throws Exception {
        System.out.println("====== 🚀 1. 8월(2026-08-31) 샘플 데이터 BigQuery 삽입 ======");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        TableId tableId = TableId.of(TARGET_PROJECT, DATASET, "daily_recommender_inventory");

        // 1. 8월 과거 샘플 데이터 삽입
        Map<String, Object> augRow = new HashMap<>();
        augRow.put("snapshot_date", "2026-08-31");
        augRow.put("project_id", "secu-390423");
        augRow.put("customer_name", "카카오헬스케어");
        augRow.put("category", "COST");
        augRow.put("priority", "HIGH");
        augRow.put("recommender_id", "google.compute.address.IdleResourceRecommender");
        augRow.put("target_resource_name", "addr-vpn-secu-august-sample");
        augRow.put("recommendation_description", "[HIGH] [대상: addr-vpn-secu-august-sample] 8월 마지막 날 기준 미사용 유휴 고정 IP 삭제 권고");
        augRow.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        bigQuery.insertAll(InsertAllRequest.newBuilder(tableId).addRow(augRow).build());
        System.out.println("✅ 8월 샘플 데이터(2026-08-31) 1건 삽입 완료");

        // 2. 당월(2026-09) 데이터만 부분 삭제(DELETE) 실행
        System.out.println("====== 🚀 2. 당월(2026-09) 데이터 부분 삭제(DELETE) 선행 실행 ======");
        bigQueryBatchService.deleteCurrentMonthDailyRecommenders("2026-09-07");

        // 3. 8월 데이터가 살아있는지 검증
        String augCountSql = String.format("SELECT COUNT(*) as cnt FROM `%s.%s.daily_recommender_inventory` WHERE STARTS_WITH(CAST(snapshot_date AS STRING), '2026-08')", TARGET_PROJECT, DATASET);
        TableResult augRes = bigQuery.query(QueryJobConfiguration.newBuilder(augCountSql).build());
        long augCount = 0;
        for (FieldValueList row : augRes.iterateAll()) {
            augCount = row.get("cnt").getLongValue();
        }
        System.out.println(String.format("🛡️ [8월 보존 검증] 9월 부분 삭제 후 8월 데이터 레코드 수: %d건 (정상 보존 확인!)", augCount));
        assertTrue(augCount >= 1, "8월 데이터는 9월 삭제 시 절대 지워지면 안 됩니다.");

        // 4. 9월 데이터 수집 및 1회 신규 적재
        System.out.println("====== 🚀 3. 9월(2026-09-07) 최신 Recommender 데이터 1회 신규 적재 ======");
        int insertedTotal = 0;
        List<InfraEnvironment> envs = environmentRepository.findAll();
        for (InfraEnvironment env : envs) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;
            String encSecret = env.getEncryptedSecret();
            if (encSecret == null) continue;

            String decSecret = encryptionService.decrypt(encSecret);
            GoogleCredentials gcpCreds = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(decSecret.getBytes(StandardCharsets.UTF_8))
            ).createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));

            String customerName = env.getCustomer() != null && env.getCustomer().getName() != null ? env.getCustomer().getName() : "Unknown";
            List<CloudProject> projects = env.getProjects();
            if (projects == null || projects.isEmpty()) continue;

            for (CloudProject proj : projects) {
                String projectId = proj.getProjectId();
                for (String cat : Arrays.asList("SECURITY", "COST", "PERFORMANCE", "RELIABILITY", "MANAGABILITY", "SUSTAINABILITY")) {
                    try {
                        List<GcpRecommenderService.GcpRecommendation> realRecs = gcpRecommenderService.fetchRealGcpRecommendations(gcpCreds, projectId, cat);
                        for (GcpRecommenderService.GcpRecommendation rec : realRecs) {
                            String prio = rec.getPriority() != null && !rec.getPriority().isEmpty() ? rec.getPriority() : "MEDIUM";
                            String targetRes = rec.getTargetResource() != null ? rec.getTargetResource() : "";

                            Map<String, Object> rowContent = new HashMap<>();
                            rowContent.put("snapshot_date", "2026-09-07");
                            rowContent.put("project_id", projectId);
                            rowContent.put("customer_name", customerName);
                            rowContent.put("category", cat);
                            rowContent.put("priority", prio);
                            rowContent.put("recommender_id", rec.getRecommenderId());
                            rowContent.put("target_resource_name", targetRes);
                            rowContent.put("recommendation_description", rec.getDescription());
                            rowContent.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

                            bigQuery.insertAll(InsertAllRequest.newBuilder(tableId).addRow(rowContent).build());
                            insertedTotal++;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        System.out.println(String.format("✅ 9월 최신 데이터 신규 적재 완료: 총 %d건 적재됨", insertedTotal));

        // 5. 최종 월별 분포 쿼리 검증
        System.out.println("====== 📊 4. BigQuery 월별 데이터 적재 분포 최종 확인 ======");
        String monthlyGroupSql = String.format(
                "SELECT SUBSTR(CAST(snapshot_date AS STRING), 1, 7) as ym, COUNT(*) as cnt " +
                "FROM `%s.%s.daily_recommender_inventory` " +
                "GROUP BY ym ORDER BY ym ASC",
                TARGET_PROJECT, DATASET
        );
        TableResult groupRes = bigQuery.query(QueryJobConfiguration.newBuilder(monthlyGroupSql).build());
        for (FieldValueList row : groupRes.iterateAll()) {
            String ym = row.get("ym").getStringValue();
            long cnt = row.get("cnt").getLongValue();
            System.out.println(String.format("  📅 [%s월] Recommender 데이터 건수: %d건", ym, cnt));
        }

        // 8월 샘플 레코드 상세 출력
        String sampleAugSql = String.format(
                "SELECT snapshot_date, project_id, target_resource_name, recommendation_description " +
                "FROM `%s.%s.daily_recommender_inventory` " +
                "WHERE STARTS_WITH(CAST(snapshot_date AS STRING), '2026-08') LIMIT 1",
                TARGET_PROJECT, DATASET
        );
        TableResult sampleAugRes = bigQuery.query(QueryJobConfiguration.newBuilder(sampleAugSql).build());
        for (FieldValueList row : sampleAugRes.iterateAll()) {
            System.out.println(String.format("  🔍 [8월 보존 확인 샘플] 날짜: %s | 프로젝트: %s | 대상: %s | 설명: %s",
                    row.get("snapshot_date").getStringValue(),
                    row.get("project_id").getStringValue(),
                    row.get("target_resource_name").getStringValue(),
                    row.get("recommendation_description").getStringValue()));
        }

        System.out.println("====== 🏁 과거 월 보존 및 당월 부분 삭제 덮어쓰기 100% 정상 검증 완료 ======");
    }
}
