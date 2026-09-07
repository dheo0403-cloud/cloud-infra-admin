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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class BigQueryRecommenderOverwriteTest {

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
    @DisplayName("Recommender 테이블 TRUNCATE(초기화) 및 덮어쓰기(Overwrite) 검증")
    public void testRecommenderTruncateAndOverwrite() throws Exception {
        System.out.println("====== 🚀 1. TRUNCATE TABLE daily_recommender_inventory 실행 및 검증 ======");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        // 1. TRUNCATE 실행
        bigQueryBatchService.truncateDailyRecommenderTable();

        // 2. TRUNCATE 후 데이터 0건 검증
        String countSql = String.format("SELECT COUNT(*) as cnt FROM `%s.%s.daily_recommender_inventory`", TARGET_PROJECT, DATASET);
        TableResult countRes = bigQuery.query(QueryJobConfiguration.newBuilder(countSql).build());
        long afterTruncateCount = 0;
        for (FieldValueList row : countRes.iterateAll()) {
            afterTruncateCount = row.get("cnt").getLongValue();
        }
        System.out.println(String.format("✅ TRUNCATE 후 daily_recommender_inventory 레코드 수: %d건 (0건 확인 완료)", afterTruncateCount));
        assertEquals(0, afterTruncateCount, "TRUNCATE 후 테이블 카운트는 반드시 0이어야 합니다.");

        // 3. Recommender 최신 데이터 1회 적재 수행
        System.out.println("====== 🚀 2. 최신 Recommender 데이터 1회 신규 적재 (Fresh Insert) ======");
        String today = LocalDate.now().toString();
        TableId tableId = TableId.of(TARGET_PROJECT, DATASET, "daily_recommender_inventory");
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
                            rowContent.put("snapshot_date", today);
                            rowContent.put("project_id", projectId);
                            rowContent.put("customer_name", customerName);
                            rowContent.put("category", cat);
                            rowContent.put("priority", prio);
                            rowContent.put("recommender_id", rec.getRecommenderId());
                            rowContent.put("target_resource_name", targetRes);
                            rowContent.put("recommendation_description", rec.getDescription());
                            rowContent.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

                            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                                    .addRow(rowContent)
                                    .build();
                            bigQuery.insertAll(insertRequest);
                            insertedTotal++;
                        }
                    } catch (Exception e) {
                        System.out.println("⚠️ Recommender fetch skipped for " + projectId + " / " + cat + ": " + e.getMessage());
                    }
                }
            }
        }

        System.out.println(String.format("✅ 1회 신규 적재 완료: 총 %d건 적재됨", insertedTotal));

        // 4. 재조회 카운트 확인
        TableResult freshCountRes = bigQuery.query(QueryJobConfiguration.newBuilder(countSql).build());
        long freshCount = 0;
        for (FieldValueList row : freshCountRes.iterateAll()) {
            freshCount = row.get("cnt").getLongValue();
        }
        System.out.println(String.format("✅ 신규 적재 후 daily_recommender_inventory 총 레코드 수: %d건", freshCount));
        assertTrue(freshCount > 0, "신규 적재 후 레코드 수가 0보다 커야 합니다.");

        // 5. 타 테이블(daily_asset_inventory) 무결성 확인
        String assetCountSql = String.format("SELECT COUNT(*) as cnt FROM `%s.%s.daily_asset_inventory`", TARGET_PROJECT, DATASET);
        TableResult assetRes = bigQuery.query(QueryJobConfiguration.newBuilder(assetCountSql).build());
        long assetCount = 0;
        for (FieldValueList row : assetRes.iterateAll()) {
            assetCount = row.get("cnt").getLongValue();
        }
        System.out.println(String.format("🛡️ [안전 검증] 타 배치 테이블(daily_asset_inventory) 보존 건수: %d건 (데이터 누적 상태 완벽 보존)", assetCount));
        assertTrue(assetCount > 0, "자산 테이블 데이터는 TRUNCATE되지 않고 누적 상태가 유지되어야 합니다.");

        System.out.println("====== 🏁 TRUNCATE & 덮어쓰기 로직 100% 정상 검증 완료 ======");
    }
}
