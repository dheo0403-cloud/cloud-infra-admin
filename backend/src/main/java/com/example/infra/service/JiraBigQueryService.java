package com.example.infra.service;

import com.google.cloud.bigquery.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class JiraBigQueryService {

    private final BigQuery bigQuery;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    private static final String TABLE_NAME = "jira_issue_inventory";

    private static final Set<String> ALLOWED_COLUMNS = new HashSet<>(Arrays.asList(
            "snapshot_date", "customer_id", "customer_name", "provider_type",
            "project_key", "issue_key", "issue_id", "summary", "issue_type",
            "status_name", "status_category", "priority",
            "assignee_name", "assignee_email", "reporter_name", "reporter_email",
            "created_at", "updated_at", "resolution_date", "labels", "collected_at",
            "managed_type", "work_type_1", "work_type_2", "work_type_3",
            "customer_info", "customer_service", "work_category", "report_yn"
    ));

    /**
     * jira_asset_mapping 테이블에서 Asset ID -> GCP 프로젝트명 매핑 조회
     */
    public Map<String, String> getAssetMapping() {
        Map<String, String> map = new HashMap<>();
        try {
            String query = String.format("SELECT asset_id, project_name FROM `%s.jira_asset_mapping`", datasetName);
            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            for (FieldValueList row : result.iterateAll()) {
                if (!row.get("asset_id").isNull() && !row.get("project_name").isNull()) {
                    map.put(row.get("asset_id").getStringValue().trim(), row.get("project_name").getStringValue().trim());
                }
            }
        } catch (Exception e) {
            log.warn("[JIRA-BQ] Notice fetching asset mapping: {}", e.getMessage());
        }
        return map;
    }

    /**
     * jira_issue_inventory 테이블 전체 비우기 (최신 1벌 유지를 위한 TRUNCATE)
     */
    public void truncateTable() {
        try {
            String sql = String.format("TRUNCATE TABLE `%s.%s`", datasetName, TABLE_NAME);
            QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql).build();
            bigQuery.query(config);
            log.info("[JIRA-BQ] Successfully truncated table {}.{}", datasetName, TABLE_NAME);
        } catch (Exception e) {
            log.warn("[JIRA-BQ] Error truncating table {}.{}: {}", datasetName, TABLE_NAME, e.getMessage());
        }
    }

    /**
     * 일자별 스냅샷 멱등 적재 (해당 일자 + 프로젝트 키의 기존 데이터 정리 후 Insert)
     */
    public void saveDailyJiraIssues(String customerId, String customerName, String providerType, String projectKey, List<Map<String, Object>> issues, LocalDate snapshotDate) {

        if (issues == null || issues.isEmpty()) {
            log.warn("[JIRA-BQ] No issues to save for customer: {} (Provider: {}, Project: {}) on date: {}",
                    customerName, providerType, projectKey, snapshotDate);
            return;
        }

        String dateStr = snapshotDate.format(DateTimeFormatter.ISO_DATE);
        Map<String, String> assetMap = getAssetMapping();

        // 1. 당일 해당 프로젝트 기존 데이터 삭제 (멱등성 보장)
        String deleteSql = String.format("DELETE FROM `%s.%s` WHERE snapshot_date = '%s' AND project_key = '%s' AND provider_type = '%s'",
                datasetName, TABLE_NAME, dateStr, projectKey, providerType);
        try {
            QueryJobConfiguration deleteConfig = QueryJobConfiguration.newBuilder(deleteSql).build();
            bigQuery.query(deleteConfig);
            log.info("[JIRA-BQ] Cleared existing rows for date {}, provider {}, project {}", dateStr, providerType, projectKey);
        } catch (Exception e) {
            log.warn("[JIRA-BQ] Notice while clearing partition for {} ({}): {}", projectKey, providerType, e.getMessage());
        }

        // 2. RowToInsert 스트리밍 적재
        List<InsertAllRequest.RowToInsert> rowsToInsert = new ArrayList<>();
        String collectedAtIso = java.time.Instant.now().toString();

        for (Map<String, Object> issue : issues) {
            Map<String, Object> rowContent = new HashMap<>();
            for (Map.Entry<String, Object> entry : issue.entrySet()) {
                if (ALLOWED_COLUMNS.contains(entry.getKey()) && entry.getValue() != null) {
                    rowContent.put(entry.getKey(), entry.getValue());
                }
            }

            // Customer Service 내 Asset ID를 실제 프로젝트명으로 치환
            Object csObj = rowContent.get("customer_service");
            if (csObj != null && !csObj.toString().trim().isEmpty()) {
                String[] parts = csObj.toString().split("[,\\s]+");
                List<String> resolved = new ArrayList<>();
                for (String p : parts) {
                    String trimmed = p.trim();
                    if (assetMap.containsKey(trimmed)) {
                        resolved.add(assetMap.get(trimmed));
                    } else if (!trimmed.isEmpty()) {
                        resolved.add(trimmed);
                    }
                }
                if (!resolved.isEmpty()) {
                    rowContent.put("customer_service", String.join(", ", resolved));
                }
            }

            rowContent.put("snapshot_date", dateStr);
            rowContent.put("customer_id", customerId != null ? customerId : "");
            rowContent.put("customer_name", customerName != null ? customerName : "");
            rowContent.put("provider_type", providerType != null ? providerType : "GCP");
            rowContent.put("collected_at", collectedAtIso);

            rowsToInsert.add(InsertAllRequest.RowToInsert.of(rowContent));
        }

        // 500건 단위 배치 청크 분할 Insert
        int batchSize = 500;
        for (int i = 0; i < rowsToInsert.size(); i += batchSize) {
            List<InsertAllRequest.RowToInsert> batch = rowsToInsert.subList(i, Math.min(i + batchSize, rowsToInsert.size()));
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(TableId.of(datasetName, TABLE_NAME), batch).build();
            InsertAllResponse response = bigQuery.insertAll(insertRequest);

            if (response.hasErrors()) {
                response.getInsertErrors().forEach((index, errors) -> {
                    log.error("[JIRA-BQ] Insert Error at index {}: {}", index, errors);
                });
                throw new RuntimeException("BigQuery batch insert failed for Jira issues (" + projectKey + ")");
            }
        }

        log.info("[JIRA-BQ] Successfully loaded {} issues to BigQuery table {}.{} for customer: {} (Project: {})",
                rowsToInsert.size(), datasetName, TABLE_NAME, customerName, projectKey);
    }
}
