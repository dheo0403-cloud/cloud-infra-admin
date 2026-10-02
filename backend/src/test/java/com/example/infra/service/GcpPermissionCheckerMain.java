package com.example.infra.service;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.cloud.compute.v1.InstancesSettings;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.cloud.container.v1.ClusterManagerSettings;
import com.google.cloud.resourcemanager.v3.Project;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
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

public class GcpPermissionCheckerMain {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) {
        System.out.println("====================================================================================================");
        System.out.println("🔍 [전수 진단] 변경된 gcp-credentials.json 기반 GCP 4대 영역 IAM 권한 종합 실측 시작");
        System.out.println("====================================================================================================");

        try {
            File credFile = new File("src/main/resources/gcp-credentials.json");
            if (!credFile.exists()) {
                credFile = new File("backend/src/main/resources/gcp-credentials.json");
            }
            System.out.println("🔑 Credentials file path: " + credFile.getAbsolutePath());

            GoogleCredentials credentials;
            try (FileInputStream fis = new FileInputStream(credFile)) {
                credentials = GoogleCredentials.fromStream(fis)
                        .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
            }

            credentials.refreshIfExpired();
            System.out.println("✅ Service Account Credential Loaded Successfully!");

            // 1. 접근 가능 프로젝트 탐색 (ProjectsClient 또는 하드코딩/주요 프로젝트)
            List<String> targetProjects = new ArrayList<>();
            try (ProjectsClient projectsClient = ProjectsClient.create(
                    ProjectsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                for (Project p : projectsClient.searchProjects("").iterateAll()) {
                    targetProjects.add(p.getProjectId());
                }
                System.out.println("📋 Resource Manager로 감지된 GCP 프로젝트 목록 (" + targetProjects.size() + "개): " + targetProjects);
            } catch (Exception e) {
                System.out.println("⚠️ Resource Manager 프로젝트 감지 실패 (projects.search 권한 부족/미사용): " + e.getMessage());
            }

            // 우진산전 (2개) & NSMall (12개) 주요 프로젝트 및 기본 대상 프로젝트 설정
            List<String> priorityProjects = List.of(
                // 우진산전 (2개)
                "wjis-gw-project", "msp-g2cms1-wjis-240118",
                // NSMall (12개)
                "ns-user-data", "ns-mart-data", "ns-extr-data", "ns-intr-data", "ns-bill-data",
                "ns-aiplatform-prd", "ns-aiplatform-dev", "ns-poc-event", "ns-analysis-user",
                "ns-dev-ground", "ns-pipe-srvc-prod-402505", "ns-infr-host-402505"
            );
            for (String pId : priorityProjects) {
                if (!targetProjects.contains(pId)) {
                    targetProjects.add(pId);
                }
            }

            if (targetProjects.isEmpty()) {
                targetProjects = new ArrayList<>(priorityProjects);
            }

            for (String projectId : priorityProjects) {
                System.out.println("\n====================================================================================================");
                System.out.println("📌 Project Diagnostics: [" + projectId + "]");
                System.out.println("====================================================================================================");

                // -----------------------------------------------------------------------------------------
                // 영역 1: BigQuery IAM & INFORMATION_SCHEMA (JOBS / TABLE_STORAGE)
                // -----------------------------------------------------------------------------------------
                System.out.println("\n  1️⃣ [BigQuery] INFORMATION_SCHEMA IAM 권한 점검:");
                List<String> regionsToTest = List.of("asia-northeast3", "us");
                for (String region : regionsToTest) {
                    try {
                        BigQuery bq = BigQueryOptions.newBuilder()
                                .setCredentials(credentials)
                                .setProjectId(projectId)
                                .setLocation(region)
                                .build()
                                .getService();

                        // 1-1. INFORMATION_SCHEMA.JOBS
                        String jobsSql = String.format(
                            "SELECT COUNT(1) AS cnt FROM `%s.region-%s.INFORMATION_SCHEMA.JOBS` WHERE job_type = 'QUERY' AND creation_time >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 7 DAY)",
                            projectId, region.toLowerCase()
                        );
                        TableResult jobsRes = bq.query(QueryJobConfiguration.newBuilder(jobsSql).build());
                        long jobsCnt = 0;
                        for (FieldValueList row : jobsRes.iterateAll()) {
                            jobsCnt = row.get("cnt").getLongValue();
                        }
                        System.out.println("     - ✅ [JOBS] Region " + region + " JOBS Query SUCCESS (Recent 7-Day Query Jobs: " + jobsCnt + ")");
                    } catch (BigQueryException e) {
                        System.out.println("     - ❌ [JOBS] Region " + region + " JOBS Query FAILED: Error Code " + e.getCode() + " | Message: " + e.getMessage());
                    } catch (Exception e) {
                        System.out.println("     - ❌ [JOBS] Region " + region + " JOBS Query EXCEPTION: " + e.getMessage());
                    }

                    try {
                        BigQuery bq = BigQueryOptions.newBuilder()
                                .setCredentials(credentials)
                                .setProjectId(projectId)
                                .setLocation(region)
                                .build()
                                .getService();

                        // 1-2. INFORMATION_SCHEMA.TABLE_STORAGE_BY_PROJECT
                        String storageSql = String.format(
                            "SELECT COUNT(1) AS cnt FROM `%s.region-%s.INFORMATION_SCHEMA.TABLE_STORAGE_BY_PROJECT`",
                            projectId, region.toLowerCase()
                        );
                        TableResult storageRes = bq.query(QueryJobConfiguration.newBuilder(storageSql).build());
                        long storageCnt = 0;
                        for (FieldValueList row : storageRes.iterateAll()) {
                            storageCnt = row.get("cnt").getLongValue();
                        }
                        System.out.println("     - ✅ [TABLE_STORAGE] Region " + region + " Table Storage Query SUCCESS (Tables Count: " + storageCnt + ")");
                    } catch (BigQueryException e) {
                        System.out.println("     - ❌ [TABLE_STORAGE] Region " + region + " Table Storage Query FAILED: Error Code " + e.getCode() + " | Message: " + e.getMessage());
                    } catch (Exception e) {
                        System.out.println("     - ❌ [TABLE_STORAGE] Region " + region + " Table Storage Query EXCEPTION: " + e.getMessage());
                    }
                }

                // -----------------------------------------------------------------------------------------
                // 영역 2: 고객사 인프라 자원 수집 (Compute Engine VM, Cloud Storage, GKE)
                // -----------------------------------------------------------------------------------------
                System.out.println("\n  2️⃣ [Resource Fetcher] Compute Engine VM, Storage Bucket, GKE Cluster 수집 권한 점검:");
                // VM
                try (InstancesClient client = InstancesClient.create(
                        InstancesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                    int vmCount = 0;
                    for (var entry : client.aggregatedList(projectId).iterateAll()) {
                        if (entry.getValue().getInstancesList() != null) {
                            vmCount += entry.getValue().getInstancesList().size();
                        }
                    }
                    System.out.println("     - ✅ [VM] Compute Engine Instance List SUCCESS (Total VM Count: " + vmCount + ")");
                } catch (Exception e) {
                    System.out.println("     - ❌ [VM] Compute Engine Instance List FAILED: " + e.getMessage());
                }

                // Storage
                try {
                    Storage storage = StorageOptions.newBuilder().setCredentials(credentials).setProjectId(projectId).build().getService();
                    int bucketCount = 0;
                    for (var b : storage.list(projectId).iterateAll()) {
                        bucketCount++;
                    }
                    System.out.println("     - ✅ [Storage] Cloud Storage Bucket List SUCCESS (Total Bucket Count: " + bucketCount + ")");
                } catch (Exception e) {
                    System.out.println("     - ❌ [Storage] Cloud Storage Bucket List FAILED: " + e.getMessage());
                }

                // GKE
                try (ClusterManagerClient containerClient = ClusterManagerClient.create(
                        ClusterManagerSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                    var clusters = containerClient.listClusters("projects/" + projectId + "/locations/-").getClustersList();
                    System.out.println("     - ✅ [GKE] GKE Cluster List SUCCESS (Total Cluster Count: " + (clusters != null ? clusters.size() : 0) + ")");
                } catch (Exception e) {
                    System.out.println("     - ❌ [GKE] GKE Cluster List FAILED: " + e.getMessage());
                }

                // -----------------------------------------------------------------------------------------
                // 영역 3: Vertex AI & Gemini API 호출 권한 점검
                // -----------------------------------------------------------------------------------------
                System.out.println("\n  3️⃣ [Vertex AI / Gemini API] 호출 권한 점검:");
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
                        System.out.println("     - ✅ [Vertex AI] Gemini 2.5 Flash GenerateContent Call SUCCESS (HTTP 200)");
                    } else {
                        System.out.println("     - ❌ [Vertex AI] Gemini Call FAILED: Status Code " + response.statusCode() + " | Response Body: " + response.body());
                    }
                } catch (Exception e) {
                    System.out.println("     - ❌ [Vertex AI] Gemini Call EXCEPTION: " + e.getMessage());
                }

                // -----------------------------------------------------------------------------------------
                // 영역 4: GCP Recommender 일일 리소스 추천 권한 점검
                // -----------------------------------------------------------------------------------------
                System.out.println("\n  4️⃣ [GCP Recommender API] 카테고리별 Active Assist 추천 권한 점검:");
                Map<String, String> recommenderMap = new LinkedHashMap<>();
                recommenderMap.put("비용(MachineType)", "google.compute.instance.MachineTypeRecommender");
                recommenderMap.put("비용(IdleDisk)", "google.compute.disk.IdleResourceRecommender");
                recommenderMap.put("보안(IAM Policy)", "google.iam.policy.Recommender");
                recommenderMap.put("성능(CloudSQL Overprovisioned)", "google.cloudsql.instance.OverprovisionedRecommender");

                for (Map.Entry<String, String> entry : recommenderMap.entrySet()) {
                    String categoryLabel = entry.getKey();
                    String recId = entry.getValue();

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
                            System.out.println("     - ✅ [Recommender: " + categoryLabel + "] " + recId + " Call SUCCESS (HTTP 200)");
                        } else {
                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                                    status >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line);
                            }
                            System.out.println("     - ❌ [Recommender: " + categoryLabel + "] " + recId + " FAILED: HTTP " + status + " | " + sb.toString());
                        }
                    } catch (Exception e) {
                        System.out.println("     - ❌ [Recommender: " + categoryLabel + "] " + recId + " EXCEPTION: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("\n====================================================================================================");
        System.out.println("🏁 [실측 점검 완료]");
        System.out.println("====================================================================================================");
    }
}
