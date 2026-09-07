package com.example.infra;

import com.example.infra.entity.InfraEnvironment;
import com.example.infra.entity.CloudProject;
import com.example.infra.repository.InfraEnvironmentRepository;
import com.example.infra.service.EncryptionService;
import com.example.infra.service.GcpRecommenderService;
import com.example.infra.service.BigQueryBatchService;
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

@SpringBootTest
public class BigQueryBatchExecutionTest {

    @Autowired
    private InfraEnvironmentRepository environmentRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private GcpRecommenderService gcpRecommenderService;

    private static final String TARGET_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("GCP Recommender 대상 리소스(target_resource_name) 수집 및 BigQuery 적재 검증")
    public void runRecommenderBatchAndVerifyTargetResource() throws Exception {
        System.out.println("====== 🚀 1. GCP Recommender 전용 수집 및 BigQuery 적재 시작 ======");

        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials bqCredentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(bqCredentials)
                .setProjectId(TARGET_PROJECT)
                .build()
                .getService();

        // 1. DDL 보장
        String createTableDdl = String.format(
                "CREATE TABLE IF NOT EXISTS `%s.%s.daily_recommender_inventory` (" +
                "  snapshot_date DATE," +
                "  project_id STRING," +
                "  customer_name STRING," +
                "  category STRING," +
                "  priority STRING," +
                "  recommender_id STRING," +
                "  target_resource_name STRING," +
                "  recommendation_description STRING," +
                "  created_at TIMESTAMP" +
                ")", TARGET_PROJECT, DATASET
        );
        bigQuery.query(QueryJobConfiguration.newBuilder(createTableDdl).build());

        try {
            String alterTableDdl = String.format(
                    "ALTER TABLE `%s.%s.daily_recommender_inventory` ADD COLUMN IF NOT EXISTS target_resource_name STRING",
                    TARGET_PROJECT, DATASET
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(alterTableDdl).build());
        } catch (Exception ignored) {}

        String today = LocalDate.now().toString();
        TableId tableId = TableId.of(TARGET_PROJECT, DATASET, "daily_recommender_inventory");
        int insertedTotal = 0;

        List<InfraEnvironment> envs = environmentRepository.findAll();
        for (InfraEnvironment env : envs) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;
            String encSecret = env.getEncryptedSecret();
            if (encSecret == null) continue;

            String decSecret = encryptionService.decrypt(encSecret);
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(decSecret.getBytes(StandardCharsets.UTF_8))
            ).createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));

            String customerName = env.getCustomer() != null && env.getCustomer().getName() != null ? env.getCustomer().getName() : "Unknown";
            List<CloudProject> projects = env.getProjects();
            if (projects == null || projects.isEmpty()) continue;

            for (CloudProject proj : projects) {
                String projectId = proj.getProjectId();
                for (String cat : Arrays.asList("SECURITY", "COST", "PERFORMANCE", "RELIABILITY", "MANAGABILITY", "SUSTAINABILITY")) {
                    try {
                        List<GcpRecommenderService.GcpRecommendation> realRecs = gcpRecommenderService.fetchRealGcpRecommendations(credentials, projectId, cat);
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
                        System.out.println("⚠️ Recommender fetch failed for " + projectId + " / " + cat + ": " + e.getMessage());
                    }
                }
            }
        }

        System.out.println(String.format("====== ✅ 1. Recommender 수집 및 BigQuery 적재 완료: 총 %d건 적재 ======", insertedTotal));

        System.out.println("====== 🔍 2. BigQuery daily_recommender_inventory 적재 데이터 검증 ======");
        String querySql = String.format(
                "SELECT snapshot_date, project_id, category, priority, recommender_id, target_resource_name, recommendation_description " +
                "FROM `%s.%s.daily_recommender_inventory` " +
                "WHERE snapshot_date = '%s' " +
                "ORDER BY created_at DESC LIMIT 20",
                TARGET_PROJECT, DATASET, today
        );

        TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(querySql).build());
        int count = 0;
        for (FieldValueList row : result.iterateAll()) {
            count++;
            String date = row.get("snapshot_date").getStringValue();
            String pid = row.get("project_id").getStringValue();
            String cat = row.get("category").getStringValue();
            String prio = !row.get("priority").isNull() ? row.get("priority").getStringValue() : "MEDIUM";
            String targetRes = !row.get("target_resource_name").isNull() ? row.get("target_resource_name").getStringValue() : "(N/A)";
            String desc = row.get("recommendation_description").getStringValue();

            System.out.println(String.format("  [%d] [%s] %s | %s | %s | 대상 리소스: '%s'", count, date, pid, cat, prio, targetRes));
            System.out.println(String.format("      설명: %s", desc));
        }

        System.out.println(String.format("====== 🏁 검증 완료: 오늘(%s) 적재된 권고사항 샘플 %d건 조회 성공 ======", today, count));
    }
}
