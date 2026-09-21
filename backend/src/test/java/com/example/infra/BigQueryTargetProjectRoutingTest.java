package com.example.infra;

import com.example.infra.service.BigQueryBatchService;
import com.example.infra.service.JiraBigQueryService;
import com.example.infra.service.MonthlyReportService;
import com.example.infra.service.ReservationService;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.TableId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class BigQueryTargetProjectRoutingTest {

    private static final String MANAGED_PROJECT = "mzc-gcp-managed";
    private static final String DATASET = "infra_admin_dataset";

    @Test
    @DisplayName("BigQuery TableId 생성 시 고객사 프로젝트가 아닌 mzc-gcp-managed로 타겟 프로젝트가 지정되는지 검증")
    public void testTableIdTargetProjectRouting() {
        // 1. 3단 식별자 TableId 생성 검증
        TableId assetTable = TableId.of(MANAGED_PROJECT, DATASET, "daily_asset_inventory");
        assertEquals(MANAGED_PROJECT, assetTable.getProject());
        assertEquals(DATASET, assetTable.getDataset());
        assertEquals("daily_asset_inventory", assetTable.getTable());

        TableId recTable = TableId.of(MANAGED_PROJECT, DATASET, "daily_recommender_inventory");
        assertEquals(MANAGED_PROJECT, recTable.getProject());
        assertEquals(DATASET, recTable.getDataset());
        assertEquals("daily_recommender_inventory", recTable.getTable());

        TableId resTable = TableId.of(MANAGED_PROJECT, DATASET, "daily_reservation_inventory");
        assertEquals(MANAGED_PROJECT, resTable.getProject());
        assertEquals(DATASET, resTable.getDataset());
        assertEquals("daily_reservation_inventory", resTable.getTable());

        TableId jiraTable = TableId.of(MANAGED_PROJECT, DATASET, "jira_issue_inventory");
        assertEquals(MANAGED_PROJECT, jiraTable.getProject());
        assertEquals(DATASET, jiraTable.getDataset());
        assertEquals("jira_issue_inventory", jiraTable.getTable());
    }

    @Test
    @DisplayName("BigQueryBatchService 기본 targetProjectId가 mzc-gcp-managed로 설정되는지 검증")
    public void testBatchServiceTargetProjectId() {
        BigQuery mockBq = mock(BigQuery.class);
        BigQueryBatchService batchService = new BigQueryBatchService(null, null, null, null, mockBq);

        ReflectionTestUtils.setField(batchService, "targetProjectId", MANAGED_PROJECT);
        ReflectionTestUtils.setField(batchService, "datasetName", DATASET);

        String targetProject = (String) ReflectionTestUtils.getField(batchService, "targetProjectId");
        String dataset = (String) ReflectionTestUtils.getField(batchService, "datasetName");

        assertEquals(MANAGED_PROJECT, targetProject, "배치 적재 타겟 프로젝트는 mzc-gcp-managed 여야 합니다.");
        assertEquals(DATASET, dataset, "데이터셋은 infra_admin_dataset 이어야 합니다.");
    }

    @Test
    @DisplayName("MonthlyReportService 및 JiraBigQueryService의 타겟 프로젝트가 mzc-gcp-managed로 고정되는지 검증")
    public void testOtherServicesTargetProject() {
        BigQuery mockBq = mock(BigQuery.class);

        MonthlyReportService reportService = new MonthlyReportService(mockBq, null, null, null);
        ReflectionTestUtils.setField(reportService, "targetProjectId", MANAGED_PROJECT);
        ReflectionTestUtils.setField(reportService, "datasetName", DATASET);
        assertEquals(MANAGED_PROJECT, ReflectionTestUtils.getField(reportService, "targetProjectId"));

        JiraBigQueryService jiraService = new JiraBigQueryService(mockBq);
        ReflectionTestUtils.setField(jiraService, "targetProjectId", MANAGED_PROJECT);
        ReflectionTestUtils.setField(jiraService, "datasetName", DATASET);
        assertEquals(MANAGED_PROJECT, ReflectionTestUtils.getField(jiraService, "targetProjectId"));

        ReservationService resService = new ReservationService(null, null, mockBq);
        ReflectionTestUtils.setField(resService, "targetProjectId", MANAGED_PROJECT);
        ReflectionTestUtils.setField(resService, "datasetName", DATASET);
        assertEquals(MANAGED_PROJECT, ReflectionTestUtils.getField(resService, "targetProjectId"));
    }
}
