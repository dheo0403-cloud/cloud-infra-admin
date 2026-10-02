package com.example.infra.service;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.cloud.compute.v1.InstancesSettings;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.cloud.container.v1.ClusterManagerSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * [전체 GCP 고객사 프로젝트 4대 핵심 분야 IAM 권한 및 수집 가능 여부 전수 진단 테스트]
 * 1. BigQuery (INFORMATION_SCHEMA.JOBS & TABLE_STORAGE)
 * 2. 인프라 리소스 (VM, Storage, GKE)
 * 3. AI / Vertex AI Gemini API
 * 4. GCP Recommender (비용, 보안, 성능, 안정성)
 */
@SpringBootTest
@ActiveProfiles("test")
public class GcpFullPermissionsCheckTest {

    @Autowired
    private InfraEnvironmentService environmentService;

    @Autowired
    private BigQueryOptimizationService bigQueryOptimizationService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("전체 GCP 프로젝트 4대 핵심 기능 IAM 권한 종합 전수 점검")
    public void checkAllGcpPermissions() {
        assertDoesNotThrow(() -> {
            System.out.println("====================================================================================================");
            System.out.println("🔍 [전수 진단] gcp-credentials.json 변경에 따른 전체 GCP 프로젝트 4대 분야 IAM 권한 전수 진단");
            System.out.println("====================================================================================================");

            List<InfraEnvironment> environments = environmentService.getAllEnvironments();
            System.out.println("📋 등록된 전체 환경 수: " + environments.size());

            int totalProjects = 0;

            for (InfraEnvironment env : environments) {
                if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;

                String customerName = (env.getCustomer() != null && env.getCustomer().getName() != null)
                        ? env.getCustomer().getName() : "Unknown";
                String envName = env.getEnvironmentName();
                String decryptedSecret = environmentService.getDecryptedSecret(env.getId());

                System.out.println("\n====================================================================================================");
                System.out.println("🌐 [고객사: " + customerName + " | 환경: " + envName + " (ID: " + env.getId() + ")]");

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
                    System.out.println("\n📌 Project Check: [" + projectId + "]");

                    // -----------------------------------------------------------------------------------------
                    // 1. BigQuery IAM 및 INFORMATION_SCHEMA 접근 권한 점검
                    // -----------------------------------------------------------------------------------------
                    System.out.println("  1️⃣ [BigQuery] INFORMATION_SCHEMA IAM 점검:");
                    Set<String> discoveredRegions;
                    try {
                        discoveredRegions = bigQueryOptimizationService.discoverProjectRegions(credentials, projectId);
                        System.out.println("     - Discovered Regions: " + discoveredRegions);
                    } catch (Exception e) {
                        System.out.println("     - ❌ Region Discovery Failed: " + e.getMessage());
                        discoveredRegions = Set.of("asia-northeast3", "us");
                    }

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
                            System.out.println("     - ✅ Region " + region + " JOBS Query Success (Count: " + cnt + ")");
                        } catch (BigQueryException e) {
                            System.out.println("     - ❌ Region " + region + " JOBS Query Failed: Error Code " + e.getCode() + " | " + e.getMessage());
                        } catch (Exception e) {
                            System.out.println("     - ❌ Region " + region + " JOBS Query Failed: " + e.getMessage());
                        }
                    }

                    // -----------------------------------------------------------------------------------------
                    // 2. GCP 인프라 리소스 수집 권한 점검 (Compute VM, Storage, GKE)
                    // -----------------------------------------------------------------------------------------
                    System.out.println("  2️⃣ [Resource Fetcher] Compute Engine, Storage, GKE 수집 점검:");
                    // VM
                    try (InstancesClient client = InstancesClient.create(
                            InstancesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        int vmCount = 0;
                        for (var entry : client.aggregatedList(projectId).iterateAll()) {
                            if (entry.getValue().getInstancesList() != null) {
                                vmCount += entry.getValue().getInstancesList().size();
                            }
                        }
                        System.out.println("     - ✅ Compute VM List Success (Found VM: " + vmCount + ")");
                    } catch (Exception e) {
                        System.out.println("     - ❌ Compute VM List Failed: " + e.getMessage());
                    }

                    // Cloud Storage
                    try {
                        Storage storage = StorageOptions.newBuilder().setCredentials(credentials).setProjectId(projectId).build().getService();
                        int bucketCount = 0;
                        for (var b : storage.list(projectId).iterateAll()) {
                            bucketCount++;
                        }
                        System.out.println("     - ✅ Cloud Storage List Success (Found Buckets: " + bucketCount + ")");
                    } catch (Exception e) {
                        System.out.println("     - ❌ Cloud Storage List Failed: " + e.getMessage());
                    }

                    // GKE
                    try (ClusterManagerClient containerClient = ClusterManagerClient.create(
                            ClusterManagerSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        var clusters = containerClient.listClusters("projects/" + projectId + "/locations/-").getClustersList();
                        System.out.println("     - ✅ GKE Cluster List Success (Found Clusters: " + (clusters != null ? clusters.size() : 0) + ")");
                    } catch (Exception e) {
                        System.out.println("     - ❌ GKE Cluster List Failed: " + e.getMessage());
                    }

                    // -----------------------------------------------------------------------------------------
                    // 3. Vertex AI / Gemini API 호출 권한 점검
                    // -----------------------------------------------------------------------------------------
                    System.out.println("  3️⃣ [Vertex AI / Gemini] API 호출 권한 점검:");
                    try {
                        credentials.refreshIfExpired();
                        String accessToken = credentials.getAccessToken().getTokenValue();
                        String vertexUrl = String.format(
                            "https://us-central1-aiplatform.googleapis.com/v1/projects/%s/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent",
                            projectId
                        );
                        String requestBody = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Ping test. Respond with OK.\"}]}]}";

                        HttpRequest httpRequest = HttpRequest.newBuilder()
                                .uri(URI.create(vertexUrl))
                                .header("Authorization", "Bearer " + accessToken)
                                .header("Content-Type", "application/json; charset=utf-8")
                                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                                .timeout(Duration.ofSeconds(10))
                                .build();

                        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        if (response.statusCode() == 200) {
                            System.out.println("     - ✅ Vertex AI Gemini 2.5 Flash Call Success (HTTP 200)");
                        } else {
                            System.out.println("     - ❌ Vertex AI Gemini Call Failed: Status Code " + response.statusCode() + " | Response: " + response.body());
                        }
                    } catch (Exception e) {
                        System.out.println("     - ❌ Vertex AI Gemini Call Exception: " + e.getMessage());
                    }

                    // -----------------------------------------------------------------------------------------
                    // 4. GCP Recommender API 권한 점검
                    // -----------------------------------------------------------------------------------------
                    System.out.println("  4️⃣ [Recommender API] 비용/보안/성능/안정성 Recommender 점검:");
                    String[] testRecommenders = {
                        "google.compute.instance.MachineTypeRecommender",
                        "google.iam.policy.Recommender",
                        "google.cloudsql.instance.OverprovisionedRecommender"
                    };

                    for (String recId : testRecommenders) {
                        try {
                            credentials.refreshIfExpired();
                            String token = credentials.getAccessToken().getTokenValue();
                            String recUrl = String.format(
                                "https://recommender.googleapis.com/v1/projects/%s/locations/global/recommenders/%s/recommendations",
                                projectId, recId
                            );
                            URL url = new URI(recUrl).toURL();
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setRequestProperty("Authorization", "Bearer " + token);
                            conn.setRequestProperty("Accept", "application/json");
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(5000);

                            int status = conn.getResponseCode();
                            if (status == 200) {
                                System.out.println("     - ✅ Recommender [" + recId + "] Call Success (HTTP 200)");
                            } else {
                                StringBuilder sb = new StringBuilder();
                                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                                        status >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = br.readLine()) != null) sb.append(line);
                                }
                                System.out.println("     - ❌ Recommender [" + recId + "] Failed: HTTP " + status + " | " + sb.toString());
                            }
                        } catch (Exception e) {
                            System.out.println("     - ❌ Recommender [" + recId + "] Exception: " + e.getMessage());
                        }
                    }
                }
            }
            System.out.println("\n====================================================================================================");
            System.out.println("🏁 [전수 진단 완료]");
            System.out.println("====================================================================================================");
        });
    }
}
