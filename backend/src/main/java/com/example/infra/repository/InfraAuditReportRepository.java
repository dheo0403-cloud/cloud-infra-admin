package com.example.infra.repository;

import com.example.infra.entity.InfraAuditReport;
import com.google.cloud.bigquery.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class InfraAuditReportRepository {

    private final BigQuery bigQuery;
    private final InfraEnvironmentRepository environmentRepository;
    private final InfraAuditDetailRepository auditDetailRepository;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String targetProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    private static final String TABLE_NAME = "infra_audit_report";

    public InfraAuditReportRepository(BigQuery bigQuery, InfraEnvironmentRepository environmentRepository, InfraAuditDetailRepository auditDetailRepository) {
        this.bigQuery = bigQuery;
        this.environmentRepository = environmentRepository;
        this.auditDetailRepository = auditDetailRepository;
    }

    public InfraAuditReport save(InfraAuditReport report) {
        if (report.getId() == null) {
            // BigQuery는 Auto-Increment가 없으므로 UUID 생성
            report.setId(UUID.randomUUID().toString());
        }

        TableId tableId = TableId.of(targetProjectId, datasetName, TABLE_NAME);

        Map<String, Object> rowContent = new HashMap<>();
        rowContent.put("id", report.getId());
        rowContent.put("environment_id", report.getEnvironment() != null ? report.getEnvironment().getId() : null);
        rowContent.put("audit_date", report.getAuditDate() != null ? report.getAuditDate().toString() : null);
        rowContent.put("auditor_name", report.getAuditorName());
        rowContent.put("summary", report.getSummary());

        InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                .addRow(report.getId(), rowContent)
                .build();

        InsertAllResponse response = bigQuery.insertAll(insertRequest);

        if (response.hasErrors()) {
            throw new RuntimeException("BigQuery Insert Failed: " + response.getInsertErrors());
        }
        return report;
    }

    public List<InfraAuditReport> findByEnvironmentIdOrderByAuditDateDesc(String environmentId) {
        String query = String.format(
            "SELECT * FROM `%s.%s.%s` WHERE environment_id = @envId ORDER BY audit_date DESC",
            targetProjectId, datasetName, TABLE_NAME
        );

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("envId", QueryParameterValue.string(environmentId))
                .build();

        List<InfraAuditReport> reports = new ArrayList<>();
        try {
            TableResult results = bigQuery.query(queryConfig);
            for (FieldValueList row : results.iterateAll()) {
                InfraAuditReport report = new InfraAuditReport();
                report.setId(row.get("id").getStringValue());
                if (!row.get("environment_id").isNull()) {
                    String envId = row.get("environment_id").getStringValue();
                    environmentRepository.findById(envId).ifPresent(report::setEnvironment);
                }
                report.setAuditorName(row.get("auditor_name").isNull() ? null : row.get("auditor_name").getStringValue());
                report.setSummary(row.get("summary").isNull() ? null : row.get("summary").getStringValue());

                // Hydrate details
                List<com.example.infra.entity.InfraAuditDetail> details = auditDetailRepository.findByReportId(report.getId());
                report.setDetails(details);

                reports.add(report);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Query interrupted", e);
        }

        return reports;
    }

    public java.util.Optional<InfraAuditReport> findById(String id) {
        String query = String.format(
            "SELECT * FROM `%s.%s.%s` WHERE id = @id LIMIT 1",
            targetProjectId, datasetName, TABLE_NAME
        );
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("id", QueryParameterValue.string(id))
                .build();
        try {
            TableResult results = bigQuery.query(queryConfig);
            for (FieldValueList row : results.iterateAll()) {
                InfraAuditReport report = new InfraAuditReport();
                report.setId(row.get("id").getStringValue());
                if (!row.get("environment_id").isNull()) {
                    String envId = row.get("environment_id").getStringValue();
                    environmentRepository.findById(envId).ifPresent(report::setEnvironment);
                }
                report.setAuditorName(row.get("auditor_name").isNull() ? null : row.get("auditor_name").getStringValue());
                report.setSummary(row.get("summary").isNull() ? null : row.get("summary").getStringValue());
                
                // Hydrate details
                List<com.example.infra.entity.InfraAuditDetail> details = auditDetailRepository.findByReportId(report.getId());
                report.setDetails(details);
                
                return java.util.Optional.of(report);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return java.util.Optional.empty();
    }
}
