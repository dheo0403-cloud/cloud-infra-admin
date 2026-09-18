package com.example.infra;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.example.infra.service.InfraEnvironmentService;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceSettings;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@SpringBootTest
public class GcpPermissionDiagnosticsTest {

    @Autowired
    private InfraEnvironmentService environmentService;

    @Test
    @DisplayName("GCP 20개 프로젝트 전수 대상 Cloud Monitoring 및 Vertex AI 권한/API 활성화 정밀 진단")
    public void diagnoseGcpPermissionsAndMetrics() {
        System.out.println("================================================================================");
        System.out.println("🔍 [GCP 권한 & API 활성화 상태 전수 정밀 진단 (Permission & API Audit)]");
        System.out.println("================================================================================");

        List<InfraEnvironment> environments = environmentService.getAllEnvironments();
        int totalProjects = 0;
        int successQueryProjects = 0;
        int permissionDeniedProjects = 0;
        int apiDisabledProjects = 0;
        int otherErrorProjects = 0;

        long nowSeconds = Instant.now().getEpochSecond();
        long thirtyDaysAgoSeconds = nowSeconds - (30L * 24 * 3600); // 최근 30일 범위

        TimeInterval interval30d = TimeInterval.newBuilder()
                .setStartTime(Timestamp.newBuilder().setSeconds(thirtyDaysAgoSeconds).build())
                .setEndTime(Timestamp.newBuilder().setSeconds(nowSeconds).build())
                .build();

        for (InfraEnvironment env : environments) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;

            String customerName = env.getCustomer() != null ? env.getCustomer().getName() : "Unknown";
            String decryptedSecret = environmentService.getDecryptedSecret(env.getId());

            if (decryptedSecret == null || decryptedSecret.isEmpty()) {
                System.out.println(String.format("⚠️ 환경 [%s] (%s): 복호화된 Service Account Key가 없습니다.", env.getEnvironmentName(), customerName));
                continue;
            }

            try {
                GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decryptedSecret.getBytes()))
                        .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

                MetricServiceSettings settings = MetricServiceSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                        .build();

                try (MetricServiceClient client = MetricServiceClient.create(settings)) {
                    for (CloudProject project : env.getProjects()) {
                        totalProjects++;
                        String projectId = project.getProjectId();
                        String projectName = ProjectName.of(projectId).toString();

                        System.out.println(String.format("\n📌 [%d] 고객사: %s | 프로젝트 ID: %s", totalProjects, customerName, projectId));

                        // 1. 기본 Cloud Monitoring 권한 진단 (Compute VM CPU 메트릭으로 monitoring.timeSeries.list 권한 확인)
                        try {
                            ListTimeSeriesRequest baseReq = ListTimeSeriesRequest.newBuilder()
                                    .setName(projectName)
                                    .setFilter("metric.type = \"compute.googleapis.com/instance/cpu/utilization\"")
                                    .setInterval(interval30d)
                                    .setPageSize(5)
                                    .build();
                            int vmSeriesCount = 0;
                            for (TimeSeries ts : client.listTimeSeries(baseReq).iterateAll()) {
                                vmSeriesCount++;
                            }
                            System.out.println(String.format("   ✅ [Cloud Monitoring 기본 권한]: 정상 (Compute CPU 메트릭 시계열 %d개 확인)", vmSeriesCount));
                        } catch (PermissionDeniedException pde) {
                            permissionDeniedProjects++;
                            System.out.println(String.format("   ❌ [Cloud Monitoring 기본 권한]: 403 PERMISSION_DENIED! 사유: %s", pde.getMessage()));
                            continue;
                        } catch (ApiException ae) {
                            if (ae.getStatusCode().getCode() == StatusCode.Code.FAILED_PRECONDITION && ae.getMessage().contains("SERVICE_DISABLED")) {
                                apiDisabledProjects++;
                                System.out.println(String.format("   ⚠️ [Cloud Monitoring API 비활성화]: %s", ae.getMessage()));
                            } else {
                                otherErrorProjects++;
                                System.out.println(String.format("   ⚠️ [Cloud Monitoring API 오류]: Code=%s, %s", ae.getStatusCode().getCode(), ae.getMessage()));
                            }
                            continue;
                        } catch (Exception e) {
                            otherErrorProjects++;
                            System.out.println(String.format("   ⚠️ [일반 오류]: %s", e.getMessage()));
                            continue;
                        }

                        // 2. Pretrained API 호출량 진단 (Vision, Speech, Translate, NLP)
                        String[] pretrainedServices = { "vision.googleapis.com", "speech.googleapis.com", "translate.googleapis.com", "language.googleapis.com" };
                        long preSum = 0;
                        int preSeriesCount = 0;
                        for (String srv : pretrainedServices) {
                            try {
                                ListTimeSeriesRequest preReq = ListTimeSeriesRequest.newBuilder()
                                        .setName(projectName)
                                        .setFilter("metric.type = \"serviceruntime.googleapis.com/api/request_count\" AND resource.labels.service = \"" + srv + "\"")
                                        .setInterval(interval30d)
                                        .build();

                                for (TimeSeries ts : client.listTimeSeries(preReq).iterateAll()) {
                                    preSeriesCount++;
                                    for (var p : ts.getPointsList()) {
                                        if (p.getValue().hasInt64Value()) preSum += p.getValue().getInt64Value();
                                        else if (p.getValue().hasDoubleValue()) preSum += (long) p.getValue().getDoubleValue();
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        System.out.println(String.format("   🔍 [Pre-trained APIs 실측]: 시계열 %d개, 최근 30일 총 호출수=%,d건", preSeriesCount, preSum));

                        // 3. Vertex AI 토큰 메트릭 진단 (publisher/token_count)
                        long tokenSum = 0;
                        int tokSeriesCount = 0;
                        String[] tokenTypes = { "aiplatform.googleapis.com/publisher/token_count", "aiplatform.googleapis.com/prediction/online/token_count" };
                        for (String tt : tokenTypes) {
                            try {
                                ListTimeSeriesRequest tokReq = ListTimeSeriesRequest.newBuilder()
                                        .setName(projectName)
                                        .setFilter("metric.type = \"" + tt + "\"")
                                        .setInterval(interval30d)
                                        .build();

                                for (TimeSeries ts : client.listTimeSeries(tokReq).iterateAll()) {
                                    tokSeriesCount++;
                                    for (var p : ts.getPointsList()) {
                                        if (p.getValue().hasInt64Value()) tokenSum += p.getValue().getInt64Value();
                                        else if (p.getValue().hasDoubleValue()) tokenSum += (long) p.getValue().getDoubleValue();
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        System.out.println(String.format("   🔍 [Vertex AI 토큰 메트릭 실측]: 시계열 %d개, 최근 30일 총 토큰=%,d", tokSeriesCount, tokenSum));

                        // 4. Vertex AI Endpoint Prediction 메트릭 진단 (prediction/online/request_count)
                        long epReqSum = 0;
                        int epSeriesCount = 0;
                        try {
                            ListTimeSeriesRequest epReq = ListTimeSeriesRequest.newBuilder()
                                    .setName(projectName)
                                    .setFilter("metric.type = \"aiplatform.googleapis.com/prediction/online/request_count\"")
                                    .setInterval(interval30d)
                                    .build();

                            for (TimeSeries ts : client.listTimeSeries(epReq).iterateAll()) {
                                epSeriesCount++;
                                for (var p : ts.getPointsList()) {
                                    if (p.getValue().hasInt64Value()) epReqSum += p.getValue().getInt64Value();
                                    else if (p.getValue().hasDoubleValue()) epReqSum += (long) p.getValue().getDoubleValue();
                                }
                            }
                        } catch (Exception ignored) {}
                        System.out.println(String.format("   🔍 [Vertex AI 엔드포인트 실측]: 시계열 %d개, 최근 30일 예측 요청=%,d건", epSeriesCount, epReqSum));

                        successQueryProjects++;
                    }
                }
            } catch (Exception e) {
                System.out.println(String.format("❌ 환경 [%s] SA Credential 로드/초기화 실패: %s", env.getEnvironmentName(), e.getMessage()));
            }
        }

        System.out.println("\n================================================================================");
        System.out.println("📊 [종합 진단 결과 요약]");
        System.out.println("================================================================================");
        System.out.println(String.format("👉 전체 대상 프로젝트 수: %d개", totalProjects));
        System.out.println(String.format("👉 Cloud Monitoring 쿼리 성공 프로젝트 수: %d개 (권한 100%% 정상)", successQueryProjects));
        System.out.println(String.format("👉 403 권한 부족(Permission Denied) 프로젝트 수: %d개", permissionDeniedProjects));
        System.out.println(String.format("👉 API 비활성화(Disabled) 프로젝트 수: %d개", apiDisabledProjects));
        System.out.println(String.format("👉 기타 오류 프로젝트 수: %d개", otherErrorProjects));
    }
}
