package com.example.infra.repository;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraCustomer;
import com.example.infra.entity.InfraEnvironment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class InfraCustomerRepository {

    private final BigQuery bigQuery;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    private static final String TABLE_NAME = "infra_customer";

    public InfraCustomerRepository(BigQuery bigQuery) {
        this.bigQuery = bigQuery;
    }

    public List<InfraCustomer> findAll() {
        String query = String.format(
            "WITH UniqueCustomers AS (" +
            "  SELECT * FROM `%s.%s` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            "), UniqueEnvs AS (" +
            "  SELECT * FROM `%s.infra_environment` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            ") " +
            "SELECT c.id, c.name, c.contact_person, c.contact_email, c.report_frequency, c.msp_grade, c.msp_sales_rep, c.msp_rep, c.jira_project_key_gcp, c.jira_project_key_azure, c.created_at, c.is_deleted, " +
            "  e.id AS env_id, e.provider_type, e.environment_name, e.project_ids, " +
            "  e.azure_tenant_id, e.azure_client_id " +
            "FROM UniqueCustomers c " +
            "LEFT JOIN UniqueEnvs e ON e.customer_id = c.id AND (e.is_deleted IS NULL OR e.is_deleted = FALSE) " +
            "WHERE (c.is_deleted IS NULL OR c.is_deleted = FALSE) " +
            "ORDER BY c.created_at DESC",
            datasetName, TABLE_NAME, datasetName
        );
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();

        Map<String, InfraCustomer> customerMap = new LinkedHashMap<>();
        try {
            TableResult results = bigQuery.query(queryConfig);
            for (FieldValueList row : results.iterateAll()) {
                String customerId = row.get("id").getStringValue();
                InfraCustomer customer = customerMap.computeIfAbsent(customerId, k -> {
                    InfraCustomer c = new InfraCustomer();
                    c.setId(customerId);
                    c.setName(row.get("name").isNull() ? null : row.get("name").getStringValue());
                    c.setContactPerson(row.get("contact_person").isNull() ? null : row.get("contact_person").getStringValue());
                    c.setContactEmail(row.get("contact_email").isNull() ? null : row.get("contact_email").getStringValue());
                    c.setReportFrequency(row.get("report_frequency").isNull() ? null : row.get("report_frequency").getStringValue());
                    c.setMspGrade(row.get("msp_grade").isNull() ? null : row.get("msp_grade").getStringValue());
                    c.setMspSalesRep(row.get("msp_sales_rep").isNull() ? null : row.get("msp_sales_rep").getStringValue());
                    c.setMspRep(row.get("msp_rep").isNull() ? null : row.get("msp_rep").getStringValue());
                    try {
                        c.setJiraProjectKeyGcp(row.get("jira_project_key_gcp").isNull() ? null : row.get("jira_project_key_gcp").getStringValue());
                    } catch (Exception ignored) {}
                    try {
                        c.setJiraProjectKeyAzure(row.get("jira_project_key_azure").isNull() ? null : row.get("jira_project_key_azure").getStringValue());
                    } catch (Exception ignored) {}
                    if (!row.get("created_at").isNull()) {
                        double epochSeconds = row.get("created_at").getDoubleValue();
                        c.setCreatedAt(LocalDateTime.ofEpochSecond((long) epochSeconds, 0, java.time.ZoneOffset.UTC));
                    }
                    c.setEnvironments(new ArrayList<>());
                    return c;
                });
                
                // 연결된 환경 추가
                if (!row.get("env_id").isNull()) {
                    InfraEnvironment env = new InfraEnvironment();
                    env.setId(row.get("env_id").getStringValue());
                    env.setProviderType(row.get("provider_type").isNull() ? null : row.get("provider_type").getStringValue());
                    env.setEnvironmentName(row.get("environment_name").isNull() ? null : row.get("environment_name").getStringValue());
                    env.setProjects(parseProjectIds(row));
                    customer.getEnvironments().add(env);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ArrayList<>(customerMap.values());
    }

    public java.util.Optional<InfraCustomer> findById(String id) {
        String query = String.format(
            "WITH UniqueCustomers AS (" +
            "  SELECT * FROM `%s.%s` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            "), UniqueEnvs AS (" +
            "  SELECT * FROM `%s.infra_environment` " +
            "  QUALIFY ROW_NUMBER() OVER(PARTITION BY id ORDER BY created_at DESC) = 1" +
            ") " +
            "SELECT c.id, c.name, c.contact_person, c.contact_email, c.report_frequency, c.msp_grade, c.msp_sales_rep, c.msp_rep, c.jira_project_key_gcp, c.jira_project_key_azure, c.created_at, c.is_deleted, " +
            "  e.id AS env_id, e.provider_type, e.environment_name, e.encrypted_secret, " +
            "  e.project_ids, e.azure_tenant_id, e.azure_client_id " +
            "FROM UniqueCustomers c " +
            "LEFT JOIN UniqueEnvs e ON e.customer_id = c.id AND (e.is_deleted IS NULL OR e.is_deleted = FALSE) " +
            "WHERE c.id = @id AND (c.is_deleted IS NULL OR c.is_deleted = FALSE)",
            datasetName, TABLE_NAME, datasetName
        );
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("id", QueryParameterValue.string(id))
                .build();
        try {
            TableResult results = bigQuery.query(queryConfig);
            InfraCustomer customer = null;
            for (FieldValueList row : results.iterateAll()) {
                if (customer == null) {
                    customer = new InfraCustomer();
                    customer.setId(row.get("id").getStringValue());
                    customer.setName(row.get("name").isNull() ? null : row.get("name").getStringValue());
                    customer.setContactPerson(row.get("contact_person").isNull() ? null : row.get("contact_person").getStringValue());
                    customer.setContactEmail(row.get("contact_email").isNull() ? null : row.get("contact_email").getStringValue());
                    customer.setReportFrequency(row.get("report_frequency").isNull() ? null : row.get("report_frequency").getStringValue());
                    customer.setMspGrade(row.get("msp_grade").isNull() ? null : row.get("msp_grade").getStringValue());
                    customer.setMspSalesRep(row.get("msp_sales_rep").isNull() ? null : row.get("msp_sales_rep").getStringValue());
                    customer.setMspRep(row.get("msp_rep").isNull() ? null : row.get("msp_rep").getStringValue());
                    try {
                        customer.setJiraProjectKeyGcp(row.get("jira_project_key_gcp").isNull() ? null : row.get("jira_project_key_gcp").getStringValue());
                    } catch (Exception ignored) {}
                    try {
                        customer.setJiraProjectKeyAzure(row.get("jira_project_key_azure").isNull() ? null : row.get("jira_project_key_azure").getStringValue());
                    } catch (Exception ignored) {}
                    if (!row.get("created_at").isNull()) {
                        double epochSeconds = row.get("created_at").getDoubleValue();
                        customer.setCreatedAt(LocalDateTime.ofEpochSecond((long) epochSeconds, 0, java.time.ZoneOffset.UTC));
                    }
                    customer.setEnvironments(new ArrayList<>());
                }
                if (!row.get("env_id").isNull()) {
                    InfraEnvironment env = new InfraEnvironment();
                    env.setId(row.get("env_id").getStringValue());
                    env.setProviderType(row.get("provider_type").isNull() ? null : row.get("provider_type").getStringValue());
                    env.setEnvironmentName(row.get("environment_name").isNull() ? null : row.get("environment_name").getStringValue());
                    env.setEncryptedSecret(row.get("encrypted_secret").isNull() ? null : row.get("encrypted_secret").getStringValue());
                    env.setProjects(parseProjectIds(row));
                    try { env.setAzureTenantId(row.get("azure_tenant_id").isNull() ? null : row.get("azure_tenant_id").getStringValue()); } catch (Exception ignored) {}
                    try { env.setAzureClientId(row.get("azure_client_id").isNull() ? null : row.get("azure_client_id").getStringValue()); } catch (Exception ignored) {}
                    customer.getEnvironments().add(env);
                }
            }
            if (customer != null) return java.util.Optional.of(customer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return java.util.Optional.empty();
    }

    public InfraCustomer save(InfraCustomer customer) {
        boolean isNew = (customer.getId() == null);
        if (isNew) {
            customer.setId(java.util.UUID.randomUUID().toString());
        }
        customer.setCreatedAt(LocalDateTime.now());
        
        String query = String.format("INSERT INTO `%s.%s` (id, name, contact_person, contact_email, report_frequency, msp_grade, msp_sales_rep, msp_rep, jira_project_key_gcp, jira_project_key_azure, created_at, is_deleted) " +
                "VALUES (@id, @name, @contactPerson, @contactEmail, @reportFrequency, @mspGrade, @mspSalesRep, @mspRep, @jiraProjectKeyGcp, @jiraProjectKeyAzure, @createdAt, @isDeleted)", datasetName, TABLE_NAME);
        
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        String createdAtStr = customer.getCreatedAt().format(formatter);
        Boolean isDeleted = customer.getIsDeleted() != null ? customer.getIsDeleted() : false;
        
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("id", QueryParameterValue.string(customer.getId()))
                .addNamedParameter("name", QueryParameterValue.string(customer.getName() == null ? "" : customer.getName()))
                .addNamedParameter("contactPerson", QueryParameterValue.string(customer.getContactPerson() == null ? "" : customer.getContactPerson()))
                .addNamedParameter("contactEmail", QueryParameterValue.string(customer.getContactEmail() == null ? "" : customer.getContactEmail()))
                .addNamedParameter("reportFrequency", QueryParameterValue.string(customer.getReportFrequency() == null ? "" : customer.getReportFrequency()))
                .addNamedParameter("mspGrade", QueryParameterValue.string(customer.getMspGrade() == null ? "" : customer.getMspGrade()))
                .addNamedParameter("mspSalesRep", QueryParameterValue.string(customer.getMspSalesRep() == null ? "" : customer.getMspSalesRep()))
                .addNamedParameter("mspRep", QueryParameterValue.string(customer.getMspRep() == null ? "" : customer.getMspRep()))
                .addNamedParameter("jiraProjectKeyGcp", QueryParameterValue.string(customer.getJiraProjectKeyGcp() == null ? "" : customer.getJiraProjectKeyGcp()))
                .addNamedParameter("jiraProjectKeyAzure", QueryParameterValue.string(customer.getJiraProjectKeyAzure() == null ? "" : customer.getJiraProjectKeyAzure()))
                .addNamedParameter("createdAt", QueryParameterValue.string(createdAtStr))
                .addNamedParameter("isDeleted", QueryParameterValue.bool(isDeleted))
                .build();
        
        try {
            bigQuery.query(queryConfig);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("BigQuery save interrupted", e);
        }
        return customer;
    }

    public void deleteById(String id) {
        java.util.Optional<InfraCustomer> opt = findById(id);
        if (opt.isPresent()) {
            InfraCustomer c = opt.get();
            c.setIsDeleted(true);
            save(c);
        }
    }

    /** project_ids JSON 컬럼을 CloudProject 리스트로 파싱 */
    private List<CloudProject> parseProjectIds(FieldValueList row) {
        List<CloudProject> projects = new ArrayList<>();
        try {
            FieldValue fv = row.get("project_ids");
            if (!fv.isNull()) {
                List<String> ids = objectMapper.readValue(
                    fv.getStringValue(), new TypeReference<List<String>>() {});
                for (String pid : ids) {
                    CloudProject cp = new CloudProject();
                    cp.setProjectId(pid);
                    projects.add(cp);
                }
            }
        } catch (Exception ignored) {}
        return projects;
    }
}
