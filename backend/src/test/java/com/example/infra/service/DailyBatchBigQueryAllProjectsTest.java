package com.example.infra.service;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.google.cloud.bigquery.BigQuery;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// 일 배치가 모든 GCP 고객사 프로젝트의 BigQuery 수집을 시도하는지 (권한이 생기면 다음 배치부터 자동 적재되는 전제)
class DailyBatchBigQueryAllProjectsTest {

    // 형식만 맞춘 테스트용 서비스 계정 키 (실제 호출 없음)
    private static String fakeServiceAccountJson() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";
        return "{\"type\":\"service_account\",\"project_id\":\"test\",\"private_key_id\":\"k1\","
                + "\"private_key\":\"" + pem + "\",\"client_email\":\"sa@test.iam.gserviceaccount.com\","
                + "\"client_id\":\"1\",\"token_uri\":\"https://oauth2.googleapis.com/token\"}";
    }

    private static InfraEnvironment gcpEnv(String id, String... projectIds) {
        InfraEnvironment env = new InfraEnvironment();
        env.setId(id);
        env.setProviderType("GCP");
        env.setProjects(java.util.Arrays.stream(projectIds)
                .map(p -> { CloudProject cp = new CloudProject(); cp.setProjectId(p); return cp; })
                .collect(java.util.stream.Collectors.toList()));
        return env;
    }

    @Test
    void 모든_고객사_프로젝트에_BigQuery_수집을_시도하고_한_곳이_실패해도_계속한다() throws Exception {
        InfraEnvironmentService envService = mock(InfraEnvironmentService.class);
        GcpResourceFetcher fetcher = mock(GcpResourceFetcher.class);
        BigQueryOptimizationService bqOpt = mock(BigQueryOptimizationService.class);
        BigQueryBatchService batch = new BigQueryBatchService(envService, fetcher,
                mock(GcpRecommenderService.class), bqOpt, mock(BigQuery.class));

        InfraEnvironment envA = gcpEnv("A", "cust-a-1", "cust-a-2");
        InfraEnvironment envB = gcpEnv("B", "cust-b-1");
        InfraEnvironment noSecret = gcpEnv("C", "cust-c-1"); // 자격증명 없는 환경은 건너뜀
        when(envService.getAllEnvironments()).thenReturn(List.of(envA, envB, noSecret));
        String json = fakeServiceAccountJson();
        when(envService.getDecryptedSecret("A")).thenReturn(json);
        when(envService.getDecryptedSecret("B")).thenReturn(json);
        when(envService.getDecryptedSecret("C")).thenReturn(null);
        when(fetcher.getDirectAiMetricsData(any(), anyString(), anyLong(), anyLong()))
                .thenReturn(GcpResourceFetcher.DirectAiCollectedData.builder().modelUsages(List.of()).build());

        // 첫 프로젝트는 권한 오류 등으로 수집이 예외를 던져도 다음 프로젝트를 계속 수집해야 함
        doThrow(new RuntimeException("PERMISSION_DENIED: bigquery.jobs.listAll"))
                .when(bqOpt).collectAndUpsertBigQueryOptimizationData(anyString(), eq("cust-a-1"), any(), any());

        batch.runDailySnapshotBatch();

        for (String p : List.of("cust-a-1", "cust-a-2", "cust-b-1")) {
            verify(bqOpt).collectAndUpsertBigQueryOptimizationData(anyString(), eq(p), any(), any());
        }
        verify(bqOpt).collectCompletedMonthTopQueries(anyString(), eq("cust-a-2"), any(), any());
        verify(bqOpt).collectCompletedMonthTopQueries(anyString(), eq("cust-b-1"), any(), any());
        verify(bqOpt, never()).collectAndUpsertBigQueryOptimizationData(anyString(), eq("cust-c-1"), any(), any());
    }
}
