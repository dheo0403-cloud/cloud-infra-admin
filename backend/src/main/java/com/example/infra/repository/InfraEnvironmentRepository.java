package com.example.infra.repository;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class InfraEnvironmentRepository {

    private final BigQuery bigQuery;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String targetProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    private static final String TABLE_NAME = "infra_environment";

    public InfraEnvironmentRepository(BigQuery bigQuery) {
        this.bigQuery = bigQuery;
    }

    public Optional<InfraEnvironment> findById(String id) {
        String query = String.format(
            "WITH UniqueEnvs AS (" +
            "  SELECT * FROM `%s.%s.%s` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            "), UniqueCustomers AS (" +
            "  SELECT * FROM `%s.%s.infra_customer` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            ") " +
            "SELECT e.*, c.name as customer_name, c.report_frequency as customer_report_frequency, c.msp_grade as customer_msp_grade, c.msp_sales_rep as customer_msp_sales_rep, c.msp_rep as customer_msp_rep " +
            "FROM UniqueEnvs e " +
            "LEFT JOIN UniqueCustomers c ON e.customer_id = c.id AND (c.is_deleted IS NULL OR c.is_deleted = FALSE) " +
            "WHERE e.id = @id AND (e.is_deleted IS NULL OR e.is_deleted = FALSE) LIMIT 100",
            targetProjectId, datasetName, TABLE_NAME, targetProjectId, datasetName
        );

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("id", QueryParameterValue.string(id))
                .build();

        try {
            TableResult results = bigQuery.query(queryConfig);
            for (FieldValueList row : results.iterateAll()) {
                return Optional.of(mapRow(row));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    public InfraEnvironment save(InfraEnvironment env) {
        boolean isNew = (env.getId() == null);
        if (isNew) {
            env.setId(java.util.UUID.randomUUID().toString());
        }
        env.setCreatedAt(java.time.LocalDateTime.now());

        String projectsJson = "[]";
        try {
            projectsJson = objectMapper.writeValueAsString(
                env.getProjects() != null ? env.getProjects().stream()
                    .map(p -> p.getProjectId()).toList() : List.of()
            );
        } catch (Exception ex) {}

        String query = String.format("INSERT INTO `%s.%s.%s` (id, customer_id, provider_type, environment_name, encrypted_secret, azure_tenant_id, azure_client_id, project_ids, created_at, is_deleted) " +
                "VALUES (@id, @customerId, @providerType, @environmentName, @encryptedSecret, @azureTenantId, @azureClientId, @projectIds, @createdAt, @isDeleted)", targetProjectId, datasetName, TABLE_NAME);

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        String createdAtStr = env.getCreatedAt().format(formatter);
        Boolean isDeleted = env.getIsDeleted() != null ? env.getIsDeleted() : false;

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("id", QueryParameterValue.string(env.getId()))
                .addNamedParameter("customerId", QueryParameterValue.string(env.getCustomer() != null && env.getCustomer().getId() != null ? env.getCustomer().getId() : ""))
                .addNamedParameter("providerType", QueryParameterValue.string(env.getProviderType() == null ? "" : env.getProviderType()))
                .addNamedParameter("environmentName", QueryParameterValue.string(env.getEnvironmentName() == null ? "" : env.getEnvironmentName()))
                .addNamedParameter("encryptedSecret", QueryParameterValue.string(env.getEncryptedSecret() == null ? "" : env.getEncryptedSecret()))
                .addNamedParameter("azureTenantId", QueryParameterValue.string(env.getAzureTenantId() == null ? "" : env.getAzureTenantId()))
                .addNamedParameter("azureClientId", QueryParameterValue.string(env.getAzureClientId() == null ? "" : env.getAzureClientId()))
                .addNamedParameter("projectIds", QueryParameterValue.string(projectsJson))
                .addNamedParameter("createdAt", QueryParameterValue.string(createdAtStr))
                .addNamedParameter("isDeleted", QueryParameterValue.bool(isDeleted))
                .build();

        try {
            bigQuery.query(queryConfig);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("BigQuery save interrupted for Env", e);
        }
        return env;
    }

    public void deleteById(String id) {
        Optional<InfraEnvironment> opt = findById(id);
        if (opt.isPresent()) {
            InfraEnvironment env = opt.get();
            env.setIsDeleted(true);
            save(env);
        }
    }

    public List<InfraEnvironment> findAllByCustomerId(String customerId) {
        String query = String.format(
            "WITH UniqueEnvs AS (" +
            "  SELECT * FROM `%s.%s.%s` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            "), UniqueCustomers AS (" +
            "  SELECT * FROM `%s.%s.infra_customer` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            ") " +
            "SELECT e.*, c.name as customer_name, c.report_frequency as customer_report_frequency, c.msp_grade as customer_msp_grade, c.msp_sales_rep as customer_msp_sales_rep, c.msp_rep as customer_msp_rep " +
            "FROM UniqueEnvs e " +
            "LEFT JOIN UniqueCustomers c ON e.customer_id = c.id AND (c.is_deleted IS NULL OR c.is_deleted = FALSE) " +
            "WHERE e.customer_id = @customerId AND (e.is_deleted IS NULL OR e.is_deleted = FALSE)",
            targetProjectId, datasetName, TABLE_NAME, targetProjectId, datasetName
        );
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("customerId", QueryParameterValue.string(customerId))
                .build();

        List<InfraEnvironment> list = new ArrayList<>();
        try {
            TableResult results = bigQuery.query(queryConfig);
            for (FieldValueList row : results.iterateAll()) {
                list.add(mapRow(row));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return list;
    }

    public List<InfraEnvironment> findAll() {
        String query = String.format(
            "WITH UniqueEnvs AS (" +
            "  SELECT * FROM `%s.%s.%s` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            "), UniqueCustomers AS (" +
            "  SELECT * FROM `%s.%s.infra_customer` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            ") " +
            "SELECT e.*, c.name as customer_name, c.report_frequency as customer_report_frequency, c.msp_grade as customer_msp_grade, c.msp_sales_rep as customer_msp_sales_rep, c.msp_rep as customer_msp_rep " +
            "FROM UniqueEnvs e " +
            "LEFT JOIN UniqueCustomers c ON e.customer_id = c.id AND (c.is_deleted IS NULL OR c.is_deleted = FALSE) " +
            "WHERE (e.is_deleted IS NULL OR e.is_deleted = FALSE)",
            targetProjectId, datasetName, TABLE_NAME, targetProjectId, datasetName
        );
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
        
        List<InfraEnvironment> list = new ArrayList<>();
        try {
            TableResult results = bigQuery.query(queryConfig);
            for (FieldValueList row : results.iterateAll()) {
                list.add(mapRow(row));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return list;
    }

    private InfraEnvironment mapRow(FieldValueList row) {
        InfraEnvironment env = new InfraEnvironment();
        env.setId(row.get("id").getStringValue());
        env.setEnvironmentName(row.get("environment_name").isNull() ? null : row.get("environment_name").getStringValue());
        env.setProviderType(row.get("provider_type").isNull() ? null : row.get("provider_type").getStringValue());
        env.setEncryptedSecret(row.get("encrypted_secret").isNull() ? null : row.get("encrypted_secret").getStringValue());
        // Azure 전용 필드
        try { env.setAzureTenantId(row.get("azure_tenant_id").isNull() ? null : row.get("azure_tenant_id").getStringValue()); } catch (Exception ignored) {}
        try { env.setAzureClientId(row.get("azure_client_id").isNull() ? null : row.get("azure_client_id").getStringValue()); } catch (Exception ignored) {}
        // project_ids JSON 파싱
        try {
            if (!row.get("project_ids").isNull()) {
                List<String> ids = objectMapper.readValue(
                    row.get("project_ids").getStringValue(),
                    new TypeReference<List<String>>() {}
                );
                List<CloudProject> projects = new ArrayList<>();
                for (String pid : ids) {
                    CloudProject cp = new CloudProject();
                    cp.setProjectId(pid);
                    projects.add(cp);
                }
                env.setProjects(projects);
            }
        } catch (Exception ignored) {
            env.setProjects(new ArrayList<>());
        }
        
        // 고객사 정보 맵핑
        try {
            if (!row.get("customer_id").isNull()) {
                com.example.infra.entity.InfraCustomer customer = new com.example.infra.entity.InfraCustomer();
                customer.setId(row.get("customer_id").getStringValue());
                try {
                    customer.setName(row.get("customer_name").isNull() ? null : row.get("customer_name").getStringValue());
                    customer.setReportFrequency(row.get("customer_report_frequency").isNull() ? null : row.get("customer_report_frequency").getStringValue());
                    customer.setMspGrade(row.get("customer_msp_grade").isNull() ? null : row.get("customer_msp_grade").getStringValue());
                    customer.setMspSalesRep(row.get("customer_msp_sales_rep").isNull() ? null : row.get("customer_msp_sales_rep").getStringValue());
                    customer.setMspRep(row.get("customer_msp_rep").isNull() ? null : row.get("customer_msp_rep").getStringValue());
                } catch (Exception ignored) {}
                env.setCustomer(customer);
            }
        } catch (Exception ignored) {}
        
        return env;
    }
}
