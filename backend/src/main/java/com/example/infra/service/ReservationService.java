package com.example.infra.service;

import com.example.infra.dto.ReservationDto;
import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.compute.v1.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final InfraEnvironmentService environmentService;
    private final GcpResourceFetcher gcpResourceFetcher;
    private final BigQuery bigQuery;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    /**
     * BigQuery에서 당일 최신 예약 데이터 조회
     */
    public List<ReservationDto> getReservations(String environmentId) {
        InfraEnvironment env = environmentService.getEnvironment(environmentId);
        if (env == null) return Collections.emptyList();

        String customerName = env.getCustomer() != null ? env.getCustomer().getName() : "Unknown";
        String provider = env.getProviderType();

        // BigQuery에서 최신 snapshot_date의 데이터 조회
        String query = String.format(
            "SELECT * FROM `%s.daily_reservation_inventory` " +
            "WHERE customer_name = '%s' AND provider = '%s' AND provider != 'AZURE_APP' AND (type IS NULL OR type NOT IN ('CLIENT_SECRET', 'CERTIFICATE')) " +
            "AND snapshot_date = (SELECT MAX(snapshot_date) FROM `%s.daily_reservation_inventory` WHERE customer_name = '%s' AND provider = '%s') " +
            "ORDER BY expiry_date ASC",
            datasetName, customerName, provider,
            datasetName, customerName, provider);

        List<ReservationDto> results = new ArrayList<>();
        try {
            TableResult tableResult = bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            for (FieldValueList row : tableResult.iterateAll()) {
                results.add(ReservationDto.builder()
                    .snapshotDate(getStringValue(row, "snapshot_date"))
                    .projectId(getStringValue(row, "project_id"))
                    .customerName(getStringValue(row, "customer_name"))
                    .provider(getStringValue(row, "provider"))
                    .reservationName(getStringValue(row, "reservation_name"))
                    .status(getStringValue(row, "status"))
                    .startDate(getStringValue(row, "start_date"))
                    .expiryDate(getStringValue(row, "expiry_date"))
                    .plan(getStringValue(row, "plan"))
                    .type(getStringValue(row, "type"))
                    .region(getStringValue(row, "region"))
                    .scope(getStringValue(row, "scope"))
                    .resourceDetail(getStringValue(row, "resource_detail"))
                    .build());
            }
        } catch (Exception e) {
            log.error("Failed to query reservations from BigQuery", e);
        }
        return results;
    }

    /**
     * 모든 고객사의 최신 스냅샷 기준, 만료 기간이 100일 미만으로 남은 예약(RI/CUD) 목록 조회
     */
    public List<ReservationDto> getUpcomingExpiryReservations() {
        String query = String.format(
            "WITH latest_snapshots AS (\n" +
            "    SELECT customer_name, provider, MAX(snapshot_date) as max_snapshot\n" +
            "    FROM `%s.daily_reservation_inventory`\n" +
            "    WHERE provider != 'AZURE_APP'\n" +
            "    GROUP BY customer_name, provider\n" +
            ")\n" +
            "SELECT r.* FROM `%s.daily_reservation_inventory` r\n" +
            "JOIN latest_snapshots l ON r.customer_name = l.customer_name \n" +
            "    AND r.provider = l.provider \n" +
            "    AND r.snapshot_date = l.max_snapshot\n" +
            "WHERE r.status = 'ACTIVE'\n" +
            "    AND r.provider != 'AZURE_APP'\n" +
            "    AND (r.type IS NULL OR r.type NOT IN ('CLIENT_SECRET', 'CERTIFICATE'))\n" +
            "    AND SAFE_CAST(r.expiry_date AS DATE) >= CURRENT_DATE()\n" +
            "    AND SAFE_CAST(r.expiry_date AS DATE) < DATE_ADD(CURRENT_DATE(), INTERVAL 100 DAY)\n" +
            "ORDER BY r.expiry_date ASC",
            datasetName, datasetName
        );

        List<ReservationDto> results = new ArrayList<>();
        try {
            TableResult tableResult = bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            for (FieldValueList row : tableResult.iterateAll()) {
                results.add(ReservationDto.builder()
                    .snapshotDate(getStringValue(row, "snapshot_date"))
                    .projectId(getStringValue(row, "project_id"))
                    .customerName(getStringValue(row, "customer_name"))
                    .provider(getStringValue(row, "provider"))
                    .reservationName(getStringValue(row, "reservation_name"))
                    .status(getStringValue(row, "status"))
                    .startDate(getStringValue(row, "start_date"))
                    .expiryDate(getStringValue(row, "expiry_date"))
                    .plan(getStringValue(row, "plan"))
                    .type(getStringValue(row, "type"))
                    .region(getStringValue(row, "region"))
                    .scope(getStringValue(row, "scope"))
                    .resourceDetail(getStringValue(row, "resource_detail"))
                    .build());
            }
        } catch (Exception e) {
            log.error("Failed to query upcoming expiry reservations from BigQuery", e);
        }
        return results;
    }

    /**
     * 수동 새로고침: 즉시 API를 호출하여 BigQuery에 저장 후 결과 반환
     */
    public List<ReservationDto> refreshReservations(String environmentId) {
        InfraEnvironment env = environmentService.getEnvironment(environmentId);
        if (env == null) return Collections.emptyList();

        String snapshotDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String customerName = env.getCustomer() != null ? env.getCustomer().getName() : "Unknown";
        List<ReservationDto> results = new ArrayList<>();

        if ("GCP".equalsIgnoreCase(env.getProviderType())) {
            results = refreshGcpCud(env, snapshotDate, customerName);
        } else if ("AZURE".equalsIgnoreCase(env.getProviderType())) {
            results = refreshAzureRi(env, snapshotDate, customerName);
        }
        return results;
    }

    private List<ReservationDto> refreshGcpCud(InfraEnvironment env, String snapshotDate, String customerName) {
        List<ReservationDto> results = new ArrayList<>();
        String decryptedSecret = environmentService.getDecryptedSecret(env.getId());
        if (decryptedSecret == null) return results;

        try {
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decryptedSecret.getBytes()))
                .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

            for (CloudProject project : env.getProjects()) {
                String projectId = project.getProjectId();
                List<Commitment> commitments = gcpResourceFetcher.getCommitments(credentials, projectId);
                for (Commitment c : commitments) {
                    String region = "";
                    if (c.hasRegion()) {
                        String regionUrl = c.getRegion();
                        region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                    }
                    String plan = c.hasPlan() ? c.getPlan() : "";
                    String startDate = c.hasStartTimestamp() ? c.getStartTimestamp().substring(0, 10) : "";
                    String endDate = c.hasEndTimestamp() ? c.getEndTimestamp().substring(0, 10) : "";
                    String status = c.hasStatus() ? c.getStatus() : "";
                    String category = c.hasCategory() ? c.getCategory() : "";
                    StringBuilder resourceDetail = new StringBuilder();
                    if (c.getResourcesList() != null) {
                        for (ResourceCommitment rc : c.getResourcesList()) {
                            if (resourceDetail.length() > 0) resourceDetail.append(", ");
                            resourceDetail.append(rc.getType()).append(": ").append(rc.getAmount());
                        }
                    }

                    insertReservation(snapshotDate, projectId, customerName, "GCP",
                        c.getName(), status, startDate, endDate, plan, category, region, "", resourceDetail.toString());

                    results.add(ReservationDto.builder()
                        .snapshotDate(snapshotDate).projectId(projectId).customerName(customerName).provider("GCP")
                        .reservationName(c.getName()).status(status).startDate(startDate).expiryDate(endDate)
                        .plan(plan).type(category).region(region).scope("").resourceDetail(resourceDetail.toString())
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to refresh GCP CUD", e);
        }
        return results;
    }

    private List<ReservationDto> refreshAzureRi(InfraEnvironment env, String snapshotDate, String customerName) {
        List<ReservationDto> results = new ArrayList<>();
        String tenantId = env.getAzureTenantId();
        String clientId = env.getAzureClientId();
        String clientSecret = environmentService.getDecryptedSecret(env.getId());
        if (tenantId == null || clientId == null || clientSecret == null) return results;

        String subscriptionId = (env.getProjects() != null && !env.getProjects().isEmpty()) ? env.getProjects().get(0).getProjectId() : "";

        // 새로고침 시 당일 기존 데이터 제거하여 누적 중복 방지
        deleteDailyReservations(snapshotDate, customerName, "AZURE");

        try {
            String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("https://management.azure.com/.default", StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";

            HttpURLConnection tokenConn = (HttpURLConnection) new URL(tokenUrl).openConnection();
            tokenConn.setRequestMethod("POST");
            tokenConn.setDoOutput(true);
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = tokenConn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            String tokenResponse;
            try (java.util.Scanner scanner = new java.util.Scanner(tokenConn.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
                tokenResponse = scanner.hasNext() ? scanner.next() : "";
            }
            ObjectMapper mapper = new ObjectMapper();
            String accessToken = mapper.readTree(tokenResponse).get("access_token").asText();

            String riUrl = "https://management.azure.com/providers/Microsoft.Capacity/reservations?api-version=2022-11-01";
            HttpURLConnection riConn = (HttpURLConnection) new URL(riUrl).openConnection();
            riConn.setRequestMethod("GET");
            riConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            riConn.setRequestProperty("Content-Type", "application/json");

            String riResponse;
            try (java.util.Scanner scanner = new java.util.Scanner(riConn.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
                riResponse = scanner.hasNext() ? scanner.next() : "";
            }

            JsonNode riValues = mapper.readTree(riResponse).get("value");
            Set<String> processedRiKeys = new HashSet<>();

            if (riValues != null && riValues.isArray()) {
                for (JsonNode ri : riValues) {
                    JsonNode props = ri.get("properties");
                    if (props == null) continue;

                    String status = props.has("provisioningState") ? props.get("provisioningState").asText() : "";
                    if (status.equalsIgnoreCase("Cancelled") || status.equalsIgnoreCase("Split") || status.equalsIgnoreCase("Merged")) {
                        continue;
                    }

                    String name = ri.has("name") ? ri.get("name").asText() : "";
                    String displayName = props.has("displayName") ? props.get("displayName").asText() : name;
                    String expiryDate = props.has("expiryDate") ? props.get("expiryDate").asText() : "";
                    String effectiveDate = props.has("effectiveDateTime") ? props.get("effectiveDateTime").asText().substring(0, 10) : "";
                    String riType = props.has("reservedResourceType") ? props.get("reservedResourceType").asText() : "";
                    String skuName = ri.has("sku") && ri.get("sku").has("name") ? ri.get("sku").get("name").asText() : "";
                    String location = props.has("location") ? props.get("location").asText() : "";
                    String riScope = props.has("appliedScopeType") ? props.get("appliedScopeType").asText() : "";
                    String term = props.has("term") ? props.get("term").asText() : "";
                    int quantity = props.has("quantity") ? props.get("quantity").asInt() : 1;

                    String resourceDetail = skuName;
                    if (quantity > 1) {
                        resourceDetail += " (수량: " + quantity + ")";
                    }

                    String uniqueKey = (displayName.isEmpty() ? name : displayName) + "_" + effectiveDate + "_" + skuName + "_" + location;
                    if (!processedRiKeys.add(uniqueKey)) continue;

                    String mappedStatus = "Succeeded".equalsIgnoreCase(status) ? "ACTIVE" : status.toUpperCase();
                    if (!expiryDate.isEmpty() && expiryDate.compareTo(snapshotDate) < 0) mappedStatus = "EXPIRED";

                    insertReservation(snapshotDate, subscriptionId, customerName, "AZURE",
                        displayName.isEmpty() ? name : displayName, mappedStatus, effectiveDate, expiryDate, term, riType, location, riScope, resourceDetail);

                    results.add(ReservationDto.builder()
                        .snapshotDate(snapshotDate).projectId(subscriptionId).customerName(customerName).provider("AZURE")
                        .reservationName(displayName.isEmpty() ? name : displayName).status(mappedStatus)
                        .startDate(effectiveDate).expiryDate(expiryDate).plan(term).type(riType)
                        .region(location).scope(riScope).resourceDetail(resourceDetail)
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to refresh Azure RI", e);
        }
        try {
            refreshAzureAppCredentials(env, snapshotDate, tenantId, clientId, clientSecret);
        } catch (Exception e) {
            log.error("Failed to refresh Azure App credentials", e);
        }
        return results;
    }

    private List<ReservationDto> refreshAzureAppCredentials(InfraEnvironment env, String snapshotDate, String tenantId, String clientId, String clientSecret) {
        List<ReservationDto> results = new ArrayList<>();
        String customerName = env.getCustomer() != null && env.getCustomer().getName() != null ? env.getCustomer().getName() : "Unknown";
        String subscriptionId = (env.getProjects() != null && !env.getProjects().isEmpty()) ? env.getProjects().get(0).getProjectId() : "";

        try {
            String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("https://graph.microsoft.com/.default", StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";

            HttpURLConnection tokenConn = (HttpURLConnection) new URL(tokenUrl).openConnection();
            tokenConn.setRequestMethod("POST");
            tokenConn.setDoOutput(true);
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = tokenConn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            if (tokenConn.getResponseCode() != 200) {
                log.error("Failed to get Graph API token for Azure App refresh. Response code: {}", tokenConn.getResponseCode());
                return results;
            }

            String tokenResponse;
            try (Scanner scanner = new Scanner(tokenConn.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
                tokenResponse = scanner.hasNext() ? scanner.next() : "";
            }
            ObjectMapper mapper = new ObjectMapper();
            String accessToken = mapper.readTree(tokenResponse).get("access_token").asText();

            String filter = "startsWith(displayName,'mz-api')";
            String select = "id,appId,displayName,passwordCredentials,keyCredentials,owners";
            String apiUrl = "https://graph.microsoft.com/v1.0/applications?"
                + "$filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&$select=" + URLEncoder.encode(select, StandardCharsets.UTF_8);

            HttpURLConnection apiConn = (HttpURLConnection) new URL(apiUrl).openConnection();
            apiConn.setRequestMethod("GET");
            apiConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            apiConn.setRequestProperty("Content-Type", "application/json");

            if (apiConn.getResponseCode() != 200) {
                log.error("Failed to query Graph API for Azure Apps refresh. Response code: {}", apiConn.getResponseCode());
                return results;
            }

            String apiResponse;
            try (Scanner scanner = new Scanner(apiConn.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
                apiResponse = scanner.hasNext() ? scanner.next() : "";
            }

            JsonNode root = mapper.readTree(apiResponse);
            JsonNode values = root.get("value");
            if (values != null && values.isArray()) {
                for (JsonNode app : values) {
                    String appId = app.has("appId") ? app.get("appId").asText() : "";
                    String displayName = app.has("displayName") ? app.get("displayName").asText() : "";

                    JsonNode secrets = app.get("passwordCredentials");
                    if (secrets != null && secrets.isArray()) {
                        for (JsonNode secret : secrets) {
                            String keyId = secret.has("keyId") ? secret.get("keyId").asText() : "";
                            String endDateTime = secret.has("endDateTime") ? secret.get("endDateTime").asText() : "";
                            ReservationDto dto = buildAndInsertCredential(snapshotDate, subscriptionId, customerName, displayName, "CLIENT_SECRET", keyId, endDateTime);
                            if (dto != null) results.add(dto);
                        }
                    }

                    JsonNode certs = app.get("keyCredentials");
                    if (certs != null && certs.isArray()) {
                        for (JsonNode cert : certs) {
                            String keyId = cert.has("keyId") ? cert.get("keyId").asText() : "";
                            String endDateTime = cert.has("endDateTime") ? cert.get("endDateTime").asText() : "";
                            ReservationDto dto = buildAndInsertCredential(snapshotDate, subscriptionId, customerName, displayName, "CERTIFICATE", keyId, endDateTime);
                            if (dto != null) results.add(dto);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to refresh Azure App registration keys for env {}", env.getEnvironmentName(), e);
        }
        return results;
    }

    private ReservationDto buildAndInsertCredential(String snapshotDate, String subscriptionId, String customerName, 
                                           String appName, String type, String keyId, String endDateTime) {
        if (endDateTime == null || endDateTime.isEmpty()) return null;

        try {
            String expiryDate = endDateTime.substring(0, 10);
            LocalDate expiry = LocalDate.parse(expiryDate);
            LocalDate today = LocalDate.now();

            String status = "ACTIVE";
            if (expiry.isBefore(today)) {
                status = "EXPIRED";
            } else if (expiry.isBefore(today.plusDays(30))) {
                status = "WARNING";
            }

            insertReservation(snapshotDate, subscriptionId, customerName, "AZURE_APP",
                appName, status, "", expiryDate, "", type, "", "", keyId);

            return ReservationDto.builder()
                .snapshotDate(snapshotDate).projectId(subscriptionId).customerName(customerName).provider("AZURE_APP")
                .reservationName(appName).status(status).startDate("").expiryDate(expiryDate)
                .plan("").type(type).region("").scope("").resourceDetail(keyId)
                .build();
        } catch (Exception e) {
            log.error("Error processing Azure App credential expiration date parsing", e);
            return null;
        }
    }

    private void insertReservation(String snapshotDate, String projectId, String customerName,
            String provider, String reservationName, String status, String startDate, String expiryDate,
            String plan, String type, String region, String scope, String resourceDetail) {
        TableId tableId = TableId.of(datasetName, "daily_reservation_inventory");
        Map<String, Object> rowContent = new HashMap<>();
        rowContent.put("snapshot_date", snapshotDate);
        rowContent.put("project_id", projectId);
        rowContent.put("customer_name", customerName);
        rowContent.put("provider", provider);
        rowContent.put("reservation_name", reservationName);
        rowContent.put("status", status);
        rowContent.put("start_date", startDate);
        rowContent.put("expiry_date", expiryDate);
        rowContent.put("plan", plan);
        rowContent.put("type", type);
        rowContent.put("region", region);
        rowContent.put("scope", scope);
        rowContent.put("resource_detail", resourceDetail);

        try {
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(rowContent).build();
            bigQuery.insertAll(insertRequest);
        } catch (Exception e) {
            log.error("BigQuery reservation insert failed for {}", reservationName, e);
        }
    }

    private void deleteDailyReservations(String snapshotDate, String customerName, String provider) {
        try {
            String query = String.format(
                "DELETE FROM `%s.daily_reservation_inventory` " +
                "WHERE snapshot_date = '%s' AND customer_name = '%s' AND provider = '%s'",
                datasetName, snapshotDate, customerName, provider);
            bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            log.info("Cleared existing daily_reservation_inventory for snapshot: {}, customer: {}, provider: {}", snapshotDate, customerName, provider);
        } catch (Exception e) {
            log.error("Failed to clear existing daily_reservation_inventory for customer: {}", customerName, e);
        }
    }

    private String getStringValue(FieldValueList row, String field) {
        try {
            FieldValue val = row.get(field);
            return val.isNull() ? "" : val.getStringValue();
        } catch (Exception e) {
            return "";
        }
    }
}
