package com.example.infra.repository;

import com.example.infra.entity.InfraAuditDetail;
import com.google.cloud.bigquery.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

@Repository
public class InfraAuditDetailRepository {

    private final BigQuery bigQuery;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String targetProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    private static final String TABLE_NAME = "infra_audit_detail";

    public InfraAuditDetailRepository(BigQuery bigQuery) {
        this.bigQuery = bigQuery;
    }

    public InfraAuditDetail save(InfraAuditDetail detail) {
        if (detail.getId() == null) {
            detail.setId(UUID.randomUUID().toString());
        }

        TableId tableId = TableId.of(targetProjectId, datasetName, TABLE_NAME);

        Map<String, Object> rowContent = new HashMap<>();
        rowContent.put("id", detail.getId());
        rowContent.put("report_id", detail.getReport() != null ? detail.getReport().getId() : null);
        rowContent.put("category", detail.getCategory());
        rowContent.put("item", detail.getItem());
        rowContent.put("status", detail.getStatus());
        rowContent.put("result_text", detail.getResultText());
        rowContent.put("remediation", detail.getRemediation());
        rowContent.put("check_result", detail.getCheckResult());

        // Add customer mapping
        rowContent.put("customer_name", detail.getReport() != null && detail.getReport().getEnvironment() != null && detail.getReport().getEnvironment().getCustomer() != null ? detail.getReport().getEnvironment().getCustomer().getName() : null);
        rowContent.put("audit_date", detail.getReport() != null && detail.getReport().getAuditDate() != null ? detail.getReport().getAuditDate().toString() : null);

        InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                .addRow(detail.getId(), rowContent)
                .build();

        InsertAllResponse response = bigQuery.insertAll(insertRequest);

        if (response.hasErrors()) {
            throw new RuntimeException("BigQuery Insert Failed for Detail: " + response.getInsertErrors());
        }
        return detail;
    }

    public void saveAll(List<InfraAuditDetail> details) {
        if (details == null || details.isEmpty()) return;

        TableId tableId = TableId.of(targetProjectId, datasetName, TABLE_NAME);
        InsertAllRequest.Builder builder = InsertAllRequest.newBuilder(tableId);

        for (InfraAuditDetail detail : details) {
            if (detail.getId() == null) {
                detail.setId(UUID.randomUUID().toString());
            }

            Map<String, Object> rowContent = new HashMap<>();
            rowContent.put("id", detail.getId());
            rowContent.put("report_id", detail.getReport() != null ? detail.getReport().getId() : null);
            rowContent.put("category", detail.getCategory());
            rowContent.put("item", detail.getItem());
            rowContent.put("status", detail.getStatus());
            rowContent.put("result_text", detail.getResultText());
            rowContent.put("remediation", detail.getRemediation());
            rowContent.put("check_result", detail.getCheckResult());

            rowContent.put("customer_name", detail.getReport() != null && detail.getReport().getEnvironment() != null && detail.getReport().getEnvironment().getCustomer() != null ? detail.getReport().getEnvironment().getCustomer().getName() : null);
            rowContent.put("audit_date", detail.getReport() != null && detail.getReport().getAuditDate() != null ? detail.getReport().getAuditDate().toString() : null);
            rowContent.put("project_id", detail.getProjectId());

            builder.addRow(detail.getId(), rowContent);
        }

        InsertAllResponse response = bigQuery.insertAll(builder.build());

        if (response.hasErrors()) {
            throw new RuntimeException("BigQuery Batch Insert Failed for Details: " + response.getInsertErrors());
        }
    }

    public List<InfraAuditDetail> findByReportId(String reportId) {
        String query = String.format(
            "SELECT * FROM `%s.%s.%s` WHERE report_id = @reportId",
            targetProjectId, datasetName, TABLE_NAME
        );

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("reportId", QueryParameterValue.string(reportId))
                .build();

        List<InfraAuditDetail> details = new java.util.ArrayList<>();
        try {
            TableResult results = bigQuery.query(queryConfig);
            for (FieldValueList row : results.iterateAll()) {
                InfraAuditDetail detail = new InfraAuditDetail();
                detail.setId(row.get("id").getStringValue());
                // We don't necessarily need to populate the report object fully here 
                // since this is usually called FROM the report, but we can set it up if needed later.
                detail.setCategory(row.get("category").isNull() ? null : row.get("category").getStringValue());
                detail.setItem(row.get("item").isNull() ? null : row.get("item").getStringValue());
                detail.setStatus(row.get("status").isNull() ? null : row.get("status").getStringValue());
                detail.setResultText(row.get("result_text").isNull() ? null : row.get("result_text").getStringValue());
                detail.setRemediation(row.get("remediation").isNull() ? null : row.get("remediation").getStringValue());
                detail.setCheckResult(row.get("check_result").isNull() ? null : row.get("check_result").getStringValue());
                details.add(detail);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Query interrupted", e);
        }

        return details;
    }
}
