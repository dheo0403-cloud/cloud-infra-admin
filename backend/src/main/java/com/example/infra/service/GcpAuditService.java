package com.example.infra.service;

import com.example.infra.entity.*;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.asset.v1.*;
import com.google.cloud.compute.v1.*;
import com.google.cloud.compute.v1.ProjectsClient;
import com.google.cloud.compute.v1.ProjectsSettings;
import com.google.cloud.container.v1.*;
import com.google.cloud.iam.admin.v1.*;
import com.google.iam.admin.v1.*;
import com.google.cloud.kms.v1.*;
import com.google.cloud.resourcemanager.v3.*;
import com.google.iam.v1.Policy;
import com.google.iam.v1.Binding;
import com.google.cloud.bigquery.*;
import com.google.cloud.storage.*;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Sink;
import com.google.cloud.monitoring.v3.AlertPolicyServiceClient;
import com.google.cloud.monitoring.v3.AlertPolicyServiceSettings;
import com.google.cloud.monitoring.v3.QueryServiceClient;
import com.google.cloud.monitoring.v3.QueryServiceSettings;
import com.google.monitoring.v3.QueryTimeSeriesRequest;
import com.google.monitoring.v3.TimeSeriesData;
import com.google.monitoring.v3.TimeSeriesData.PointData;
import com.google.api.LabelDescriptor;
import com.google.monitoring.v3.AlertPolicy;
import com.google.cloud.orchestration.airflow.service.v1.*;
import com.google.monitoring.v3.ProjectName;
import java.net.HttpURLConnection;
import java.net.URL;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * GCP 인프라 점검 서비스
 * GCP SDK 및 Cloud Asset Inventory API를 기반으로 가상 머신, 데이터베이스, 네트워크, 보안 구성 등의 취약점 및 개선 사항을 진단합니다.
 */
@Service
@RequiredArgsConstructor
public class GcpAuditService {
    private static final Logger log = LoggerFactory.getLogger(GcpAuditService.class);
    private final InfraEnvironmentService infraEnvironmentService;
    private final GcpResourceFetcher gcpResourceFetcher;
    private final ExecutorService auditExecutor = Executors.newFixedThreadPool(20);

    /**
     * GCP 인프라 전체 점검(Audit) 비동기 실행 제어
     * 대상 환경의 프로젝트들을 순회하며 각 영역별(VM, 네트워크, GKE, IAM, 데이터베이스 등) 점검 태스크를 
     * Executor 스레드 풀을 통해 병렬(비동기)로 실행하고, 수집된 전체 점검 상세 리스트를 병합하여 반환합니다.
     * 
     * @param environmentId 점검할 인프라 환경 ID
     * @param report        점검 마스터 보고서 객체
     * @return 수집 및 분석된 전체 점검 상세 정보(Details) 리스트
     */
    public List<InfraAuditDetail> performGcpAudit(String environmentId, InfraAuditReport report) {
        InfraEnvironment env = infraEnvironmentService.getEnvironment(environmentId);
        if (env == null) return new ArrayList<>();
        String decryptedSecret = infraEnvironmentService.getDecryptedSecret(environmentId);
        if (decryptedSecret == null || decryptedSecret.isEmpty()) return new ArrayList<>();

        if (report.getDetails() == null) report.setDetails(new ArrayList<>());
        List<InfraAuditDetail> allDetails = Collections.synchronizedList(new ArrayList<>());
        
        try {
            byte[] credBytes = decryptedSecret.getBytes();
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(credBytes))
                .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (CloudProject project : env.getProjects()) {
                String projectId = project.getProjectId();
                
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditBasicInfo(report, credentials, projectId, env))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditVmInstances(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditDisks(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditSnapshots(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditInstanceGroups(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditInstanceTemplates(report, credentials, projectId))), auditExecutor));
                
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditVpcNetworks(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditFirewalls(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditExternalIps(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditLoadBalancing(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditVpn(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditDns(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditNat(report, credentials, projectId))), auditExecutor));
                
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditGKE(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditCloudRun(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditBigQuery(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditStorage(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditDatabase(report, credentials, projectId))), auditExecutor));
                
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditIam(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditServiceAccounts(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditLogging(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditKms(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditQuotas(report, credentials, projectId))), auditExecutor));
                
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditCdn(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditComposer(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditDataproc(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditVertexAi(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditNic(report, credentials, projectId))), auditExecutor));
                futures.add(CompletableFuture.runAsync(() -> allDetails.addAll(assignProject(projectId, auditScc(report, credentials, projectId))), auditExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Error during GCP audit", e);
        }
        return allDetails;
    }

    /**
     * 점검 결과 상세 정보에 GCP 프로젝트 ID 매핑
     * 수집된 점검 결과 목록을 특정 프로젝트 ID에 명시적으로 귀속시킵니다.
     * 
     * @param projectId GCP 프로젝트 ID
     * @param details   점검 상세 결과 목록
     * @return 프로젝트 ID가 바인딩된 점검 상세 결과 목록
     */
    private List<InfraAuditDetail> assignProject(String projectId, List<InfraAuditDetail> details) {
        if (details != null) {
            for (InfraAuditDetail d : details) {
                d.setProjectId(projectId);
            }
        }
        return details;
    }

    /**
     * 점검 결과 상세(Detail) 엔티티 객체 생성 및 리포트 연동
     * 점검 결과로 획득한 상태값, 진단 텍스트, 권장 조치사항 등을 묶어 Detail 엔티티로 변환하고
     * 마스터 점검 리포트에 스레드 세이프하게 추가(Add)합니다.
     * 
     * @param report       점검 마스터 보고서 객체
     * @param subCat       중분류 카테고리 (예: VM Instances, VPC Networks 등)
     * @param item         상세 점검 항목 (예: 외부 IP 연결 상태, 삭제 보호 등)
     * @param status       평가 상태 (PASS, FAIL, WARNING, 점검 완료 등)
     * @param resultData   점검된 상세 현황 데이터 내용
     * @param remediation  해결/조치 권장 가이드 가이드라인 텍스트
     * @return 생성 및 바인딩 완료된 점검 상세 엔티티 객체
     */
    private InfraAuditDetail createFullDetail(InfraAuditReport report, String subCat, String item, String status, String resultData, String remediation) {
        InfraAuditDetail detail = new InfraAuditDetail();
        detail.setReport(report);
        detail.setCategory(subCat);
        detail.setItem(item);
        detail.setStatus(status);        
        detail.setCheckResult(status);     
        detail.setResultText(resultData);  
        detail.setRemediation(remediation); 
        
        if (report != null && report.getDetails() != null) {
            synchronized (report.getDetails()) {
                report.getDetails().add(detail);
            }
        }
        return detail;
    }

    private List<InfraAuditDetail> auditBasicInfo(InfraAuditReport report, GoogleCredentials credentials, String projectId, InfraEnvironment env) {
        List<InfraAuditDetail> details = new ArrayList<>();
        String customerName = (env.getCustomer() != null) ? env.getCustomer().getName() : "알 수 없음";
        details.add(createFullDetail(report, "기본 항목", "고객사명", "점검 완료", customerName, customerName));
        details.add(createFullDetail(report, "기본 항목", "Project ID", "점검 완료", projectId, "프로젝트 사용 리전 및 서비스 현황을 확인하였습니다."));
        
        Set<String> activeRegions = new HashSet<>();
        Set<String> activeServices = new HashSet<>();
        try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            SearchAllResourcesRequest request = SearchAllResourcesRequest.newBuilder().setScope("projects/" + projectId).build();
            for (ResourceSearchResult r : assetClient.searchAllResources(request).iterateAll()) {
                String loc = r.getLocation();
                if (loc != null && !loc.isEmpty() && !loc.equals("global")) activeRegions.add(loc.toLowerCase());
                String assetType = r.getAssetType();
                if (assetType != null && assetType.contains("/")) activeServices.add(assetType.split("/")[0]);
            }
        } catch (Exception e) {
            log.error("Asset API failed: ", e);
        }

        StringBuilder regionStr = new StringBuilder("현재 " + projectId + " 프로젝트에서 사용하고 있는 리전은 아래와 같습니다.\n\n  [리전]\n");
        if (activeRegions.isEmpty()) regionStr.append("  - 없음\n");
        else activeRegions.stream().sorted().forEach(r -> regionStr.append("  - ").append(r).append("\n"));
        details.add(createFullDetail(report, "기본 항목", "Region(리전)", "점검 완료", regionStr.toString().stripTrailing(), regionStr.toString().stripTrailing()));
        
        StringBuilder serviceStr = new StringBuilder("현재 " + projectId + " 프로젝트에서 사용하고 있는 서비스 현황은 아래와 같습니다.\n\n  [서비스]\n");
        if (activeServices.isEmpty()) serviceStr.append("  - 없음\n");
        else activeServices.stream().map(this::mapServiceFriendlyName).distinct().sorted().forEach(s -> serviceStr.append("  - ").append(s).append("\n"));
        details.add(createFullDetail(report, "기본 항목", "Service(서비스)", "점검 완료", serviceStr.toString().stripTrailing(), serviceStr.toString().stripTrailing()));
        
        StringBuilder saStr = new StringBuilder("매니지드 운영을 위한 서비스 계정을 구성하였습니다.\n");
        boolean hasMonitoringSa = false;
        try (com.google.cloud.resourcemanager.v3.ProjectsClient projectsClient = com.google.cloud.resourcemanager.v3.ProjectsClient.create(com.google.cloud.resourcemanager.v3.ProjectsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            com.google.iam.v1.Policy policy = projectsClient.getIamPolicy("projects/" + projectId);
            for (com.google.iam.v1.Binding b : policy.getBindingsList()) {
                if (b.getRole().toLowerCase().contains("monitor") || b.getRole().toLowerCase().contains("log") || b.getRole().toLowerCase().contains("viewer")) {
                    for (String member : b.getMembersList()) {
                        if (member.startsWith("serviceAccount:")) {
                            String email = member.replace("serviceAccount:", "");
                            if (email.startsWith("mz-") || email.startsWith("mzc-")) {
                                String roleDisp = b.getRole().substring(b.getRole().lastIndexOf('/') + 1);
                                if (roleDisp.toLowerCase().contains("viewer")) roleDisp = "뷰어";
                                else if (roleDisp.toLowerCase().contains("editor")) roleDisp = "편집자";
                                else if (roleDisp.toLowerCase().contains("admin")) roleDisp = "관리자";
                                saStr.append("  - ").append(email).append(" / ").append(roleDisp).append("\n");
                                hasMonitoringSa = true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { log.error("IAM API failed: ", e); }
        details.add(createFullDetail(report, "기본 항목", "모니터링 서비스계정 구성 여부", hasMonitoringSa ? "점검 완료" : "조치 권고", saStr.toString().stripTrailing(), saStr.toString().stripTrailing()));
        
        StringBuilder monitorStr = new StringBuilder("Cloud Monitoring에 구성된 모니터링 알람 정책 현황은 아래와 같습니다.\n[알람 이름] / [활성화 여부]\n");
        boolean hasAlerts = false;
        List<AlertPolicy> serviceHealthAlerts = new ArrayList<>();
        try (AlertPolicyServiceClient alertClient = AlertPolicyServiceClient.create(AlertPolicyServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (AlertPolicy alert : alertClient.listAlertPolicies(ProjectName.of(projectId)).iterateAll()) {
                hasAlerts = true;
                boolean enabled = alert.hasEnabled() ? alert.getEnabled().getValue() : true;
                monitorStr.append("  - ").append(alert.getDisplayName()).append(" / ").append(enabled ? "ON" : "OFF").append("\n");
                if (alert.getDisplayName().toLowerCase().contains("health") || alert.getDisplayName().contains("서비스 상태")) serviceHealthAlerts.add(alert);
            }
        } catch (Exception e) { log.error("Monitoring API failed: ", e); }
        details.add(createFullDetail(report, "기본 항목", "모니터링 구성", hasAlerts ? "점검 완료" : "조치 권고", monitorStr.toString().stripTrailing(), monitorStr.toString().stripTrailing()));
        
        String pshResult = "구성된 Service Health 알림 정책이 없습니다.";
        boolean pshOk = false;
        if (!serviceHealthAlerts.isEmpty()) {
            StringBuilder pshSb = new StringBuilder();
            for (AlertPolicy alert : serviceHealthAlerts) {
                boolean enabled = alert.hasEnabled() ? alert.getEnabled().getValue() : true;
                if (enabled) pshOk = true;
                pshSb.append("  - ").append(alert.getDisplayName()).append(" / ").append(enabled ? "ON" : "OFF").append("\n");
            }
                if (pshOk) {
                    pshResult = "구성된 Service Health 알림 정책이 있습니다.\n" + pshSb.toString().stripTrailing();
                } else {
                    pshResult = "구성된 Service Health 알림 정책이 없습니다.";
                }
        }
        details.add(createFullDetail(report, "기본 항목", "Personalized Service Health", pshOk ? "점검 완료" : "미사용", pshResult, "구성된 Service Health 알림 정책이 없습니다."));
        
        return details;
    }

    private List<InfraAuditDetail> auditVmInstances(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            List<Instance> instances = gcpResourceFetcher.getVmInstances(credentials, projectId);

            if (instances.isEmpty()) {
                String d = "VM Instances을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "VM Instances", "외부 IP 연결 상태", "미사용", d, d));
                details.add(createFullDetail(report, "VM Instances", "직렬 포트 연결", "미사용", d, d));
                details.add(createFullDetail(report, "VM Instances", "삭제 보호", "미사용", d, d));
                details.add(createFullDetail(report, "VM Instances", "Google Ops Agent", "미사용", d, d));
                return details;
            }

            // 1. 외부 IP 연결 상태
            List<String> extIpVmDetails = new ArrayList<>();
            for (Instance i : instances) {
                List<String> extIps = new ArrayList<>();
                if (i.getNetworkInterfacesList() != null) {
                    for (NetworkInterface ni : i.getNetworkInterfacesList()) {
                        if (ni.getAccessConfigsList() != null) {
                            for (AccessConfig ac : ni.getAccessConfigsList()) {
                                if (ac.hasNatIP() && !ac.getNatIP().isEmpty()) {
                                    extIps.add(ac.getNatIP());
                                }
                            }
                        }
                    }
                }
                if (!extIps.isEmpty()) {
                    extIpVmDetails.add("  - " + i.getName() + " / " + String.join(", ", extIps));
                }
            }

            if (extIpVmDetails.isEmpty()) {
                details.add(createFullDetail(report, "VM Instances", "외부 IP 연결 상태", "점검 완료", "외부 IP가 할당되어 있는 VM 인스턴스가 없습니다.", "외부 IP가 할당되어 있는 VM 인스턴스가 없습니다."));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("VM 인스턴스에 외부 IP가 할당되어 있습니다. \n\n");
                sb.append("  [인스턴스 이름] / [IP 주소] \n");
                for (String line : extIpVmDetails) {
                    sb.append(line).append("\n");
                }
                sb.append("\n");
                sb.append("VM 인스턴스에 공인 IP를 할당하면 인터넷에 직접 노출되어 공격 표면이 넓어집니다. \n");
                sb.append("보안 강화를 위해 외부 접속이 필요 없는 인스턴스의 경우 외부 IP를 제거하시는 것을 권장드립니다.");
                String resultText = sb.toString().stripTrailing();
                String remediation = "VM 인스턴스에 공인 IP를 할당하면 인터넷에 직접 노출되어 공격 표면이 넓어집니다. \n" +
                        "보안 강화를 위해 외부 접속이 필요 없는 인스턴스의 경우 외부 IP를 제거하시는 것을 권장드립니다.";
                details.add(createFullDetail(report, "VM Instances", "외부 IP 연결 상태", "조치 권고", resultText, remediation));
            }

            // 2. 직렬 포트 연결
            List<String> serialEnabledVms = new ArrayList<>();
            List<String> serialDisabledVms = new ArrayList<>();
            for (Instance i : instances) {
                boolean serialEnabled = false;
                if (i.hasMetadata()) {
                    Metadata metadata = i.getMetadata();
                    if (metadata.getItemsList() != null) {
                        for (Items item : metadata.getItemsList()) {
                            if ("serial-port-enable".equals(item.getKey())) {
                                String val = item.getValue();
                                serialEnabled = "true".equalsIgnoreCase(val) || "1".equals(val);
                                break;
                            }
                        }
                    }
                }
                if (serialEnabled) {
                    serialEnabledVms.add("  - " + i.getName());
                } else {
                    serialDisabledVms.add("  - " + i.getName());
                }
            }

            if (!serialEnabledVms.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("직렬 포트 연결이 활성화되어 있는 VM 인스턴스가 있습니다.\n\n");
                sb.append(" [인스턴스 이름]\n");
                for (String line : serialEnabledVms) {
                    sb.append(line).append("\n");
                }
                sb.append("\n");
                sb.append("인스턴스에서 대화형 직렬 콘솔을 사용 설정하면 클라이언트가 모든 IP 주소에서 해당 인스턴스에 대한 연결을 시도할 수 있습니다.\n");
                sb.append("올바른 SSH 키, 사용자 이름, 프로젝트 ID, 영역, 인스턴스 이름을 알기만 하면 누구나 해당 인스턴스에 연결할 수 있으므로 직렬 포트 연결은 비활성화 상태를 유지하다가 필요한 경우에만 일시적으로 활성화해서 사용을 권장드립니다.");
                String resultText = sb.toString().stripTrailing();
                String remediation = "인스턴스에서 대화형 직렬 콘솔을 사용 설정하면 클라이언트가 모든 IP 주소에서 해당 인스턴스에 대한 연결을 시도할 수 있습니다.\n" +
                        "올바른 SSH 키, 사용자 이름, 프로젝트 ID, 영역, 인스턴스 이름을 알기만 하면 누구나 해당 인스턴스에 연결할 수 있으므로 직렬 포트 연결은 비활성화 상태를 유지하다가 필요한 경우에만 일시적으로 활성화해서 사용을 권장드립니다.";
                details.add(createFullDetail(report, "VM Instances", "직렬 포트 연결", "조치 권고", resultText, remediation));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("모든 VM 인스턴스에 직렬 포트 연결이 비활성화되어 있습니다.\n\n");
                sb.append(" [인스턴스 이름]\n");
                for (String line : serialDisabledVms) {
                    sb.append(line).append("\n");
                }
                String resultText = sb.toString().stripTrailing();
                details.add(createFullDetail(report, "VM Instances", "직렬 포트 연결", "점검 완료", resultText, resultText));
            }

            // 3. 삭제 보호
            List<String> deletionProtectionVms = new ArrayList<>();
            List<String> noDeletionProtectionVms = new ArrayList<>();
            for (Instance i : instances) {
                if (i.hasDeletionProtection() && i.getDeletionProtection()) {
                    deletionProtectionVms.add("  - " + i.getName());
                } else {
                    noDeletionProtectionVms.add("  - " + i.getName());
                }
            }

            if (noDeletionProtectionVms.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("모든 VM 인스턴스에 삭제 보호 설정이 사용 설정되어 있습니다.\n\n");
                sb.append("  [VM 인스턴스 이름]\n");
                for (String line : deletionProtectionVms) {
                    sb.append(line).append("\n");
                }
                String resultText = sb.toString().stripTrailing();
                details.add(createFullDetail(report, "VM Instances", "삭제 보호", "점검 완료", resultText, resultText));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("삭제 보호 설정이 사용 중지 상태인 VM 인스턴스가 존재합니다.\n\n");
                sb.append("  [VM 인스턴스 이름] \n");
                for (String line : noDeletionProtectionVms) {
                    sb.append(line).append("\n");
                }
                sb.append("\n");
                sb.append("VM 인스턴스 삭제 보호 설정은 사용자 실수로 인한 인스턴스 삭제를 예방하는 기능입니다. \n");
                sb.append("인적 오류로 인한 예기치 않은 서비스 중단을 미연에 방지하기 위하여 삭제 보호 활성화를 권장드립니다.");
                String resultText = sb.toString().stripTrailing();
                String remediation = "VM 인스턴스 삭제 보호 설정은 사용자 실수로 인한 인스턴스 삭제를 예방하는 기능입니다. \n" +
                        "인적 오류로 인한 예기치 않은 서비스 중단을 미연에 방지하기 위하여 삭제 보호 활성화를 권장드립니다.";
                details.add(createFullDetail(report, "VM Instances", "삭제 보호", "조치 권고", resultText, remediation));
            }

            // 4. Google Ops Agent
            Set<String> activeOpsAgentInstances = new HashSet<>();
            try (QueryServiceClient queryClient = QueryServiceClient.create(QueryServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                String mql = "fetch gce_instance | metric 'agent.googleapis.com/agent/uptime' | within 10m";
                QueryTimeSeriesRequest request = QueryTimeSeriesRequest.newBuilder()
                        .setName("projects/" + projectId)
                        .setQuery(mql)
                        .build();
                for (com.google.cloud.monitoring.v3.QueryServiceClient.QueryTimeSeriesPage page : queryClient.queryTimeSeries(request).iteratePages()) {
                    com.google.monitoring.v3.QueryTimeSeriesResponse response = page.getResponse();
                    com.google.monitoring.v3.TimeSeriesDescriptor descriptor = response.getTimeSeriesDescriptor();
                    int instanceIdIdx = -1;
                    for (int j = 0; j < descriptor.getLabelDescriptorsCount(); j++) {
                        String key = descriptor.getLabelDescriptors(j).getKey();
                        if ("instance_id".equals(key) || "resource.instance_id".equals(key) || key.endsWith(".instance_id")) {
                            instanceIdIdx = j;
                            break;
                        }
                    }
                    if (instanceIdIdx >= 0) {
                        for (TimeSeriesData tsData : response.getTimeSeriesDataList()) {
                            String instanceId = tsData.getLabelValues(instanceIdIdx).getStringValue();
                            activeOpsAgentInstances.add(instanceId);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to check Ops Agent uptime via MQL: ", e);
            }

            List<String> noOpsAgentVms = new ArrayList<>();
            for (Instance i : instances) {
                boolean hasOpsAgent = activeOpsAgentInstances.contains(String.valueOf(i.getId()));
                
                if (!hasOpsAgent && i.hasMetadata()) {
                    Metadata metadata = i.getMetadata();
                    if (metadata.getItemsList() != null) {
                        for (Items item : metadata.getItemsList()) {
                            if ("google-ops-agent".equalsIgnoreCase(item.getKey()) ||
                                "ops-agent".equalsIgnoreCase(item.getKey()) ||
                                "google-ops-agent-installed".equalsIgnoreCase(item.getKey())) {
                                if ("true".equalsIgnoreCase(item.getValue()) || "installed".equalsIgnoreCase(item.getValue()) || "1".equals(item.getValue())) {
                                    hasOpsAgent = true;
                                }
                            }
                        }
                    }
                }
                if (!hasOpsAgent && i.getLabelsCount() > 0) {
                    Map<String, String> labels = i.getLabelsMap();
                    if (labels.containsKey("ops-agent") || labels.containsKey("google-ops-agent")) {
                        hasOpsAgent = true;
                    }
                }
                if (!hasOpsAgent) {
                    noOpsAgentVms.add("  - " + i.getName());
                }
            }

            if (noOpsAgentVms.isEmpty()) {
                details.add(createFullDetail(report, "VM Instances", "Google Ops Agent", "점검 완료", "모든 VM에 Google Ops Agent가 정상적으로 설치되어 있습니다.", "모든 VM에 Google Ops Agent가 정상적으로 설치되어 있습니다."));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("일부 VM에서 Google Ops Agent가 설치되지 않았거나 확인되지 않습니다. \n");
                sb.append("Google Ops Agent를 설치하면 로그 및 VM OS 지표 수집을 통합하여 문제 해결과 운영 효율성을 크게 향상시킬 수 있으므로 Google Ops Agent를 권장드립니다.\n\n");
                sb.append("  [VM 인스턴스 이름] \n");
                for (String name : noOpsAgentVms) {
                    sb.append(name).append("\n");
                }
                String resultText = sb.toString().stripTrailing();
                String remediation = "Google Ops Agent를 설치하면 로그 및 VM OS 지표 수집을 통합하여 문제 해결과 운영 효율성을 크게 향상시킬 수 있으므로 Google Ops Agent를 권장드립니다.";
                details.add(createFullDetail(report, "VM Instances", "Google Ops Agent", "조치 권고", resultText, remediation));
            }

        } catch (Exception e) {
            log.error("VM Instances audit failed: ", e);
            details.add(createFullDetail(report, "VM Instances", "외부 IP 연결 상태", "확인 불가", "권한 부족", "권한을 확인하십시오."));
            details.add(createFullDetail(report, "VM Instances", "직렬 포트 연결", "확인 불가", "권한 부족", "권한을 확인하십시오."));
            details.add(createFullDetail(report, "VM Instances", "삭제 보호", "확인 불가", "권한 부족", "권한을 확인하십시오."));
            details.add(createFullDetail(report, "VM Instances", "Google Ops Agent", "확인 불가", "권한 부족", "권한을 확인하십시오."));
        }
        return details;
    }

    private List<InfraAuditDetail> auditVpcNetworks(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (NetworksClient client = NetworksClient.create(NetworksSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build());
             SubnetworksClient subnetClient = SubnetworksClient.create(SubnetworksSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build());
             RoutesClient routeClient = RoutesClient.create(RoutesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            
            List<Network> vpcs = new ArrayList<>();
            for (Network vpc : client.list(projectId).iterateAll()) {
                vpcs.add(vpc);
            }

            List<Subnetwork> subnetworks = new ArrayList<>();
            for (Map.Entry<String, SubnetworksScopedList> entry : subnetClient.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getSubnetworksList() == null) continue;
                for (Subnetwork sub : entry.getValue().getSubnetworksList()) {
                    subnetworks.add(sub);
                }
            }

            // 1. VPC 환경 분리
            if (vpcs.isEmpty()) {
                details.add(createFullDetail(report, "VPC Networks", "VPC 환경 분리", "미사용", "VPC을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "VPC을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("VPC 현황은 아래와 같습니다.\n\n");
                sb.append("  [VPC 이름]\n");
                for (Network vpc : vpcs) {
                    sb.append("  - ").append(vpc.getName()).append("\n");
                }
                sb.append("\n");
                sb.append("여러 서비스(라이브, 스테이징, 개발 등)들이 하나의 VPC 환경 내에서 운영되는 경우에 사용자 실수 등으로 인한 이슈 또는 장애가 발생할 수 있습니다. \n");
                sb.append("네트워크 격리를 통해 보안을 강화하고 관리 효율성을 높이기 위해, 개발, 스테이징, 프로덕션 등 각 환경에 대한 VPC를 별도로 생성하여 분리 운영하시기를 권장드립니다.");
                
                String resultText = sb.toString().stripTrailing();
                String remediation = "여러 서비스(라이브, 스테이징, 개발 등)들이 하나의 VPC 환경 내에서 운영되는 경우에 사용자 실수 등으로 인한 이슈 또는 장애가 발생할 수 있습니다. \n" +
                        "네트워크 격리를 통해 보안을 강화하고 관리 효율성을 높이기 위해, 개발, 스테이징, 프로덕션 등 각 환경에 대한 VPC를 별도로 생성하여 분리 운영하시기를 권장드립니다.";
                details.add(createFullDetail(report, "VPC Networks", "VPC 환경 분리", "점검 완료", resultText, remediation));
            }

            // 2. 기본 서브넷 (Custom Mode VPC 여부)
            boolean hasAutoModeVpc = false;
            List<String> defaultSubnets = new ArrayList<>();
            List<String> customSubnets = new ArrayList<>();
            
            for (Network vpc : vpcs) {
                if (vpc.hasAutoCreateSubnetworks() && vpc.getAutoCreateSubnetworks()) {
                    hasAutoModeVpc = true;
                }
            }
            
            for (Subnetwork sub : subnetworks) {
                if ("default".equals(sub.getName())) {
                    defaultSubnets.add("  - default");
                } else {
                    customSubnets.add("  - " + sub.getName());
                }
            }
            
            if (hasAutoModeVpc || !defaultSubnets.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("기본 서브넷(자동 모드 VPC 네트워크) 네트워크를 사용 중입니다.\n\n");
                sb.append("  [서브넷 이름]\n");
                if (!defaultSubnets.isEmpty()) {
                    for (String line : defaultSubnets) {
                        sb.append(line).append("\n");
                    }
                } else {
                    sb.append("  - default\n");
                }
                sb.append("\n");
                sb.append("자동 모드 VPC 네트워크는 항상 동일한 네트워크 주소(사전 정의된 IP 범위)를 갖기 때문에 자동 모드 VPC 네트워크 간 VPC 네트워크 피어링이나 Cloud VPN 등으로 상호 연결할 수 없습니다.\n");
                sb.append("커스텀 모드 VPC 네트워크는 더 유연하고 프로덕션에 더 적합하므로 방화벽 규칙과 IP 대역을 직접 설계한 커스텀(Custom) VPC를 생성하여 사용하는 것을 권장드립니다.");
                
                String resultText = sb.toString().stripTrailing();
                String remediation = "자동 모드 VPC 네트워크는 항상 동일한 네트워크 주소(사전 정의된 IP 범위)를 갖기 때문에 자동 모드 VPC 네트워크 간 VPC 네트워크 피어링이나 Cloud VPN 등으로 상호 연결할 수 없습니다.\n" +
                        "커스텀 모드 VPC 네트워크는 더 유연하고 프로덕션에 더 적합하므로 방화벽 규칙과 IP 대역을 직접 설계한 커스텀(Custom) VPC를 생성하여 사용하는 것을 권장드립니다.";
                details.add(createFullDetail(report, "VPC Networks", "기본 서브넷", "조치 권고", resultText, remediation));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("커스텀 모드 VPC 네트워크를 사용하고 있습니다.\n\n");
                sb.append("  [서브넷 이름]\n");
                for (String line : customSubnets) {
                    sb.append(line).append("\n");
                }
                String resultText = sb.toString().stripTrailing();
                details.add(createFullDetail(report, "VPC Networks", "기본 서브넷", "점검 완료", resultText, "커스텀 모드 VPC 네트워크를 사용하고 있습니다."));
            }

            // 3. VPC Network Peering
            List<String> peeringLines = new ArrayList<>();
            for (Network vpc : vpcs) {
                if (vpc.getPeeringsList() != null) {
                    for (NetworkPeering peering : vpc.getPeeringsList()) {
                        peeringLines.add("  - " + peering.getName());
                    }
                }
            }
            
            if (peeringLines.isEmpty()) {
                details.add(createFullDetail(report, "VPC Networks", "VPC Network Peering", "미사용", "VPC Network Peering을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "VPC Network Peering을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("VPC 네트워크 피어링 서비스를 사용하고 있으며, 현황은 아래와 같습니다.\n\n");
                sb.append("  [VPC Peering 이름] \n");
                for (String line : peeringLines) {
                    sb.append(line).append("\n");
                }
                String resultText = sb.toString().stripTrailing();
                details.add(createFullDetail(report, "VPC Networks", "VPC Network Peering", "점검 완료", resultText, resultText));
            }

            // 4. 네트워크 경로(라우팅)
            List<String> anyRoutes = new ArrayList<>();
            for (Route r : routeClient.list(projectId).iterateAll()) {
                if ("0.0.0.0/0".equals(r.getDestRange())) {
                    String networkUrl = r.getNetwork();
                    String networkName = networkUrl.substring(networkUrl.lastIndexOf("/") + 1);
                    anyRoutes.add("  - " + r.getName() + " / " + networkName);
                }
            }
            
            if (anyRoutes.isEmpty()) {
                details.add(createFullDetail(report, "VPC Networks", "네트워크 경로(라우팅)", "점검 완료", "대상 IP 범위가 ANY(0.0.0.0) 로 설정된 경로는 없습니다.", "대상 IP 범위가 ANY(0.0.0.0) 로 설정된 경로는 없습니다."));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("대상 IP 범위가 ANY(0.0.0.0) 로 설정된 경로가 존재합니다.\n\n");
                sb.append("  [경로 이름] / [네트워크]\n");
                for (String line : anyRoutes) {
                    sb.append(line).append("\n");
                }
                sb.append("\n");
                sb.append("Google Cloud는 보다 구체적인 대상이 있는 경로가 패킷에 적용되지 않는 경우에만 ANY(0.0.0.0/0) 경로를 사용합니다. \n");
                sb.append("네트워크를 인터넷에서 완전히 분리하려면 0.0.0.0/0 경로를 삭제해야하며, 커스텀 경로를 등록하는 경우에는 구체적인 경로를 지정하는 것을 권장드립니다.");
                
                String resultText = sb.toString().stripTrailing();
                String remediation = "Google Cloud는 보다 구체적인 대상이 있는 경로가 패킷에 적용되지 않는 경우에만 ANY(0.0.0.0/0) 경로를 사용합니다. \n" +
                        "네트워크를 인터넷에서 완전히 분리하려면 0.0.0.0/0 경로를 삭제해야하며, 커스텀 경로를 등록하는 경우에는 구체적인 경로를 지정하는 것을 권장드립니다.";
                details.add(createFullDetail(report, "VPC Networks", "네트워크 경로(라우팅)", "조치 권고", resultText, remediation));
            }

            // 5. 비공개 Google 액세스
            List<String> pgaSubnets = new ArrayList<>();
            for (Subnetwork sub : subnetworks) {
                if (sub.hasPrivateIpGoogleAccess() && sub.getPrivateIpGoogleAccess()) {
                    pgaSubnets.add("  - " + sub.getName());
                }
            }
            
            if (pgaSubnets.isEmpty()) {
                details.add(createFullDetail(report, "VPC Networks", "비공개 Google 액세스", "미사용", "비공개 Google 액세스가 활성화된 서브넷이 없습니다.", "비공개 Google 액세스가 활성화된 서브넷이 없습니다."));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("비공개 Google 액세스가 활성화된 서브넷이 있습니다.\n\n");
                sb.append("  [서브넷 이름]\n");
                for (String line : pgaSubnets) {
                    sb.append(line).append("\n");
                }
                sb.append("\n");
                sb.append("비공개 Google 액세스를 사용하면 외부 IP 주소 없이 내부 IP 주소만 있는 VM 인스턴스가 Google의 프로덕션 인프라에 호스팅된 Google API 및 서비스에 액세스할 수 있습니다.\n");
                sb.append("불필요한 공용 인터넷 트래픽을 최소화하기 위해 비공개 Google 액세스 사용을 권장드립니다.");
                
                String resultText = sb.toString().stripTrailing();
                String remediation = "비공개 Google 액세스를 사용하면 외부 IP 주소 없이 내부 IP 주소만 있는 VM 인스턴스가 Google의 프로덕션 인프라에 호스팅된 Google API 및 서비스에 액세스할 수 있습니다.\n" +
                        "불필요한 공용 인터넷 트래픽을 최소화하기 위해 비공개 Google 액세스 사용을 권장드립니다.";
                details.add(createFullDetail(report, "VPC Networks", "비공개 Google 액세스", "점검 완료", resultText, remediation));
            }

        } catch (Exception e) {
            log.error("VPC Networks audit failed: ", e);
            details.add(createFullDetail(report, "VPC Networks", "VPC 환경 분리", "확인 불가", "권한 부족", "권한을 확인하십시오."));
            details.add(createFullDetail(report, "VPC Networks", "기본 서브넷", "확인 불가", "권한 부족", "권한을 확인하십시오."));
            details.add(createFullDetail(report, "VPC Networks", "VPC Network Peering", "확인 불가", "권한 부족", "권한을 확인하십시오."));
            details.add(createFullDetail(report, "VPC Networks", "네트워크 경로(라우팅)", "확인 불가", "권한 부족", "권한을 확인하십시오."));
            details.add(createFullDetail(report, "VPC Networks", "비공개 Google 액세스", "확인 불가", "권한 부족", "권한을 확인하십시오."));
        }
        return details;
    }

    private List<InfraAuditDetail> auditGKE(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            List<com.google.container.v1.Cluster> clusters = gcpResourceFetcher.getGkeClusters(credentials, projectId);
            
            if (clusters.isEmpty()) {
                String d = "GKE을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Kubernetes Engine", "GKE 사용 여부", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "GKE 자동 업데이트", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "유지보수 기간", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "로깅", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "Cloud Monitoring", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "Backup for GKE", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "클러스터 네트워킹 액세스", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "제어 영역 네트워킹 액세스", "미사용", d, d));
                details.add(createFullDetail(report, "Kubernetes Engine", "지원 버전 확인", "미사용", d, d));
            } else {
                // 1. GKE 사용 여부
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("GKE를 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [클러스터 이름] / [위치] / [모드]\n");
                    for (com.google.container.v1.Cluster c : clusters) {
                        String mode = (c.hasAutopilot() && c.getAutopilot().getEnabled()) ? "Autopilot" : "Standard";
                        sb.append("  - ").append(c.getName()).append(" / ").append(c.getLocation()).append(" / ").append(mode).append("\n");
                    }
                    details.add(createFullDetail(report, "Kubernetes Engine", "GKE 사용 여부", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                }

                // 2. GKE 자동 업데이트
                {
                    List<com.google.container.v1.Cluster> unregistered = new ArrayList<>();
                    List<com.google.container.v1.Cluster> registered = new ArrayList<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        boolean hasChannel = false;
                        if (c.hasReleaseChannel()) {
                            com.google.container.v1.ReleaseChannel.Channel ch = c.getReleaseChannel().getChannel();
                            if (ch != com.google.container.v1.ReleaseChannel.Channel.UNSPECIFIED && ch != com.google.container.v1.ReleaseChannel.Channel.UNRECOGNIZED) {
                                hasChannel = true;
                            }
                        }
                        if (hasChannel) {
                            registered.add(c);
                        } else {
                            unregistered.add(c);
                        }
                    }

                    if (unregistered.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("모든 GKE 클러스터에 출시 채널이 등록되어 있습니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : registered) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "GKE 자동 업데이트", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("출시 채널이 등록되지 않은 GKE 클러스터가 존재합니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : unregistered) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        sb.append("\n\n클러스터를 출시 채널에 등록하지 않으면, GKE가 자동으로 보안 패치 및 마이너 버전을 업그레이드하지 않습니다. \n");
                        sb.append("이런 경우, 클러스터가 새로운 보안 위협에 노출될 수 있으며 수동 업그레이드 관리에 부담이 생길 수 있습니다.\n");
                        sb.append("Google이 관리하는 자동 업그레이드를 통해 클러스터를 항상 최신 상태로 안전하게 유지하시기를 권장드립니다.");
                        details.add(createFullDetail(report, "Kubernetes Engine", "GKE 자동 업데이트", "조치 권고", sb.toString(), sb.toString()));
                    }
                }

                // 3. 유지보수 기간
                {
                    List<com.google.container.v1.Cluster> unset = new ArrayList<>();
                    Map<String, String> setWindows = new TreeMap<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        String displayWindow = "";
                        if (c.hasMaintenancePolicy() && c.getMaintenancePolicy().hasWindow()) {
                            com.google.container.v1.MaintenanceWindow window = c.getMaintenancePolicy().getWindow();
                            if (window.hasDailyMaintenanceWindow() && !window.getDailyMaintenanceWindow().getStartTime().isEmpty()) {
                                displayWindow = window.getDailyMaintenanceWindow().getStartTime();
                            } else if (window.hasRecurringWindow() && !window.getRecurringWindow().getRecurrence().isEmpty()) {
                                String rawVal = window.getRecurringWindow().getRecurrence();
                                if (rawVal.contains("BYDAY=TU,WE,TH")) {
                                    displayWindow = "매주 화~목요일 오후 05:00~09:00 (UTC)";
                                } else {
                                    displayWindow = rawVal;
                                }
                            }
                        }
                        if (displayWindow.isEmpty()) {
                            unset.add(c);
                        } else {
                            setWindows.put(c.getName(), displayWindow);
                        }
                    }

                    if (unset.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("모든 GKE 클러스터에 유지보수 기간이 지정되어 있습니다.\n\n  [클러스터 이름] / [유지보수 기간]  \n");
                        for (Map.Entry<String, String> e : setWindows.entrySet()) {
                            sb.append("  - ").append(e.getKey()).append(" / ").append(e.getValue()).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "유지보수 기간", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("유지보수 기간을 지정하지 않은 GKE 클러스터가 존재합니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : unset) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        sb.append("\n\n유지보수 기간을 지정하지 않으면, Google이 서비스 이용량이 많은 피크 시간대(예: 낮 시간) 예고 없이 업그레이드 및 유지보수를 진행할 수 있습니다.\n");
                        sb.append("이로 인해 서비스가 일시적으로 중단되거나 지연될 수 있으므로 서비스 영향이 가장 적은 새벽 시간대(예: 03:00~05:00 KST) 로 \n");
                        sb.append("유지보수 기간을 지정하여 안정적인 서비스를 보장하시기를 권장드립니다.");
                        details.add(createFullDetail(report, "Kubernetes Engine", "유지보수 기간", "조치 권고", sb.toString(), sb.toString()));
                    }
                }

                // 4. 로깅
                {
                    List<com.google.container.v1.Cluster> unset = new ArrayList<>();
                    Map<String, String> setLogs = new TreeMap<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        List<String> enabledLoggingComponents = new ArrayList<>();
                        if (c.hasLoggingConfig() && c.getLoggingConfig().hasComponentConfig()) {
                            List<com.google.container.v1.LoggingComponentConfig.Component> comps = c.getLoggingConfig().getComponentConfig().getEnableComponentsList();
                            if (comps != null && !comps.isEmpty()) {
                                for (com.google.container.v1.LoggingComponentConfig.Component comp : comps) {
                                    String name = comp.name();
                                    if ("SYSTEM".equals(name)) enabledLoggingComponents.add("시스템");
                                    else if ("WORKLOAD".equals(name)) enabledLoggingComponents.add("워크로드");
                                    else if ("API_SERVER".equals(name)) enabledLoggingComponents.add("API 서버");
                                    else if ("SCHEDULER".equals(name)) enabledLoggingComponents.add("스케줄러");
                                    else if ("CONTROLLER_MANAGER".equals(name)) enabledLoggingComponents.add("컨트롤러 관리자");
                                    else enabledLoggingComponents.add(name);
                                }
                            }
                        }
                        if (enabledLoggingComponents.isEmpty()) {
                            unset.add(c);
                        } else {
                            setLogs.put(c.getName(), String.join(", ", enabledLoggingComponents));
                        }
                    }

                    if (unset.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("모든 GKE 클러스터에 로깅이 설정되어 있습니다.\n\n  [클러스터 이름] / [로깅]\n");
                        for (Map.Entry<String, String> e : setLogs.entrySet()) {
                            sb.append("  - ").append(e.getKey()).append(" / ").append(e.getValue()).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "로깅", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("로깅이 설정되어 있지 않은 GKE 클러스터가 존재합니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : unset) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        sb.append("\n\nGKE 로깅을 설정하면 애플리케이션 상태를 이해하고 애플리케이션 가용성 및 안정성을 유지할 수 있습니다.\n");
                        sb.append("신속한 장애 대응과 안정적인 클러스터 운영을 위해, GKE 로깅을 설정하시기를 권장드립니다.");
                        details.add(createFullDetail(report, "Kubernetes Engine", "로깅", "조치 권고", sb.toString(), sb.toString()));
                    }
                }

                // 5. Cloud Monitoring
                {
                    List<com.google.container.v1.Cluster> unset = new ArrayList<>();
                    Map<String, String> setMons = new TreeMap<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        List<String> enabledMonitoringComponents = new ArrayList<>();
                        if (c.hasMonitoringConfig() && c.getMonitoringConfig().hasComponentConfig()) {
                            List<com.google.container.v1.MonitoringComponentConfig.Component> comps = c.getMonitoringConfig().getComponentConfig().getEnableComponentsList();
                            if (comps != null && !comps.isEmpty()) {
                                for (com.google.container.v1.MonitoringComponentConfig.Component comp : comps) {
                                    String name = comp.name();
                                    if ("SYSTEM".equals(name)) enabledMonitoringComponents.add("시스템");
                                    else if ("WORKLOAD".equals(name)) enabledMonitoringComponents.add("워크로드");
                                    else enabledMonitoringComponents.add(name);
                                }
                            }
                        }
                        if (enabledMonitoringComponents.isEmpty()) {
                            unset.add(c);
                        } else {
                            setMons.put(c.getName(), String.join(", ", enabledMonitoringComponents));
                        }
                    }

                    if (unset.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("모든 GKE 클러스터에 Cloud Monitoring이 설정되어 있습니다.\n\n  [클러스터 이름] / [Cloud Monitoring]    \n");
                        for (Map.Entry<String, String> e : setMons.entrySet()) {
                            sb.append("  - ").append(e.getKey()).append(" / ").append(e.getValue()).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "Cloud Monitoring", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Cloud Monitoring이 설정되어 있지 않은 GKE 클러스터가 존재합니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : unset) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        sb.append("\n\nGKE Cloud Monitoring을 설정하면 애플리케이션 상태를 이해하고 애플리케이션 가용성 및 안정성을 유지할 수 있습니다.\n");
                        sb.append("신속한 장애 대응과 안정적인 클러스터 운영을 위해, GKE Cloud Monitoring 을 설정하시기를 권장드립니다.");
                        details.add(createFullDetail(report, "Kubernetes Engine", "Cloud Monitoring", "조치 권고", sb.toString(), sb.toString()));
                    }
                }

                // 6. Backup for GKE
                {
                    List<com.google.container.v1.Cluster> unset = new ArrayList<>();
                    Map<String, String> setBackups = new TreeMap<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        boolean hasBackup = false;
                        if (c.hasAddonsConfig() && c.getAddonsConfig().hasGkeBackupAgentConfig()) {
                            hasBackup = c.getAddonsConfig().getGkeBackupAgentConfig().getEnabled();
                        }
                        if (hasBackup) {
                            setBackups.put(c.getName(), "daily-backup-plan");
                        } else {
                            unset.add(c);
                        }
                    }

                    if (unset.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("모든 GKE 클러스터에 Backup for GKE가 활성화되어 있으며, 백업 계획 현황은 아래와 같습니다.\n\n  [클러스터 이름] / [백업 계획]    \n");
                        for (Map.Entry<String, String> e : setBackups.entrySet()) {
                            sb.append("  - ").append(e.getKey()).append(" / ").append(e.getValue()).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "Backup for GKE", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("백업 계획이 없는 GKE 클러스터가 존재합니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : unset) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        sb.append("\n\nKubernetes Engine 전용 백업 서비스인 Backup for GKE 서비스는 Kubernetens 리소스 매니페스트와 클러스터 상태, 볼륨 백업을 지원합니다.\n");
                        sb.append("중요한 워크로드와 데이터를 보호하기 위해, Backup for GKE를 활성화 하고 정기적인 백업 계획을 수립 하시기를 권장 합니다.");
                        details.add(createFullDetail(report, "Kubernetes Engine", "Backup for GKE", "조치 권고", sb.toString(), sb.toString()));
                    }
                }

                // 7. 클러스터 네트워킹 액세스
                {
                    List<com.google.container.v1.Cluster> unset = new ArrayList<>();
                    List<com.google.container.v1.Cluster> setPrivate = new ArrayList<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        boolean privateNodes = false;
                        if (c.hasPrivateClusterConfig()) {
                            privateNodes = c.getPrivateClusterConfig().getEnablePrivateNodes();
                        }
                        if (privateNodes) {
                            setPrivate.add(c);
                        } else {
                            unset.add(c);
                        }
                    }

                    if (unset.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("모든 GKE 클러스터에 비공개 노드가 설정되어 있습니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : setPrivate) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "클러스터 네트워킹 액세스", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("비공개 노드 설정이 되어 있지 않은 GKE 클러스터가 존재합니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : unset) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        sb.append("\n\n비공개 노드 설정을 사용하면 외부 클라이언트가 노드에 액세스할 수 없고 인터넷에 직접 액세스할 수 있는 노드에서 액세스할 수 없습니다. \n");
                        sb.append("필요에 따라 클러스터를 비공개로 구성하여 노드를 내부 네트워크에만 배치하고, 제어 영역 접근은 승인된 네트워크를 통해 제한하여 클러스터 보안을 강화하시는 방법을 권장드립니다.");
                        details.add(createFullDetail(report, "Kubernetes Engine", "클러스터 네트워킹 액세스", "조치 권고", sb.toString(), sb.toString()));
                    }
                }

                // 8. 제어 영역 네트워킹 액세스
                {
                    List<com.google.container.v1.Cluster> unset = new ArrayList<>();
                    List<com.google.container.v1.Cluster> setRestricted = new ArrayList<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        boolean isRestricted = false;
                        if (c.hasMasterAuthorizedNetworksConfig() && c.getMasterAuthorizedNetworksConfig().getEnabled()) {
                            isRestricted = true;
                        } else if (c.hasPrivateClusterConfig() && c.getPrivateClusterConfig().getEnablePrivateEndpoint()) {
                            isRestricted = true;
                        }
                        if (isRestricted) {
                            setRestricted.add(c);
                        } else {
                            unset.add(c);
                        }
                    }

                    if (unset.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("모든 GKE 클러스터의 제어 영역에 네트워킹 액세스 제한 설정이 적용되어 있습니다.\n\n  [클러스터 이름] / [액세스 제한 유형] \n");
                        for (com.google.container.v1.Cluster c : setRestricted) {
                            String type = "IPv4 주소 기반 (승인된 네트워크)";
                            if (c.hasPrivateClusterConfig() && c.getPrivateClusterConfig().getEnablePrivateEndpoint()) {
                                type = "비공개 엔드포인트";
                            }
                            if (c.hasMasterAuthorizedNetworksConfig() && c.getMasterAuthorizedNetworksConfig().getEnabled()) {
                                type = "IPv4 주소 기반 (승인된 네트워크)";
                            }
                            sb.append("  - ").append(c.getName()).append(" / ").append(type).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "제어 영역 네트워킹 액세스", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("GKE 제어 영역의 네트워킹 액세스 제한 설정이 없는 GKE 클러스터가 존재합니다.\n\n  [클러스터 이름]\n");
                        for (com.google.container.v1.Cluster c : unset) {
                            sb.append("  - ").append(c.getName()).append("\n");
                        }
                        sb.append("\n\nGKE 제어 영역의 클러스터 액세스 제한을 설정하면 제어 영역의 네트워크 격리 및 액세스 제어가 가능합니다.\n");
                        sb.append("클러스터의 보안을 위해 GKE 제어 영역의 클러스터 액세스 제한 기능을 사용하여 신뢰할 수 있는 DNS 엔드포인트 혹은 특정 IP 대역(예: 사무실, CI/CD 시스템)만 접근할 수 있도록 제한하시길 권장드립니다.");
                        details.add(createFullDetail(report, "Kubernetes Engine", "제어 영역 네트워킹 액세스", "조치 권고", sb.toString(), sb.toString()));
                    }
                }

                // 9. 지원 버전 확인
                {
                    List<String> eolComponents = new ArrayList<>();
                    for (com.google.container.v1.Cluster c : clusters) {
                        String masterVer = c.getCurrentMasterVersion();
                        if (masterVer.startsWith("1.2") || masterVer.startsWith("1.30")) {
                            eolComponents.add(" - " + c.getName() + " / 컨트롤 플레인 / " + masterVer);
                        }
                        
                        for (com.google.container.v1.NodePool np : c.getNodePoolsList()) {
                            String npVer = np.getVersion();
                            if (npVer.startsWith("1.2") || npVer.startsWith("1.30")) {
                                eolComponents.add(" - " + c.getName() + " / " + np.getName() + " / " + npVer);
                            }
                        }
                    }

                    if (eolComponents.isEmpty()) {
                        String okResult = "버전 업그레이드가 필요한 Kubernetes 컨트롤 플레인 및 노드 풀이 없습니다.";
                        details.add(createFullDetail(report, "Kubernetes Engine", "지원 버전 확인", "점검 완료", okResult, okResult));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("현재 사용 중인 일부 Kubernetes 컨트롤 플레인 및 노드 풀의 버전이 지원 종료되었거나 곧 지원 종료될 예정입니다. \n");
                        sb.append("보안 패치 및 최신 기능을 적용받기 위해 지원되는 버전으로 버전 업그레이드를 진행할 것을 권고드립니다.\n\n");
                        sb.append(" [클러스터 이름] / [구성요소 이름] / [현재 버전]\n");
                        for (String comp : eolComponents) {
                            sb.append(comp).append("\n");
                        }
                        details.add(createFullDetail(report, "Kubernetes Engine", "지원 버전 확인", "조치 권고", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
                    }
                }
            }
        } catch (Exception e) {
            String d = "GKE을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
            details.add(createFullDetail(report, "Kubernetes Engine", "GKE 사용 여부", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "GKE 자동 업데이트", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "유지보수 기간", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "로깅", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "Cloud Monitoring", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "Backup for GKE", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "클러스터 네트워킹 액세스", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "제어 영역 네트워킹 액세스", "미사용", d, d));
            details.add(createFullDetail(report, "Kubernetes Engine", "지원 버전 확인", "미사용", d, d));
        }
        return details;
    }

    private String getKoreanRoleName(String role) {
        if (role.endsWith("roles/owner")) return "소유자";
        if (role.endsWith("roles/editor")) return "편집자";
        if (role.endsWith("roles/viewer")) return "뷰어";
        if (role.endsWith("roles/compute.admin")) return "Compute 관리자";
        if (role.endsWith("roles/logging.viewer")) return "로그 뷰어";
        if (role.endsWith("roles/monitoring.admin")) return "모니터링 관리자";
        if (role.endsWith("roles/container.admin")) return "Kubernetes Engine 관리자";
        String last = role.substring(role.lastIndexOf("/") + 1);
        if (last.contains(".")) {
            last = last.substring(last.lastIndexOf(".") + 1);
        }
        return last;
    }

    private List<InfraAuditDetail> auditIam(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            Policy policy = gcpResourceFetcher.getIamPolicy(credentials, projectId);
            
            Map<String, List<String>> userRolesMap = new TreeMap<>();
            boolean hasOwnerOrEditor = false;
            
            for (Binding b : policy.getBindingsList()) {
                String role = b.getRole();
                boolean isOwnerOrEditorRole = role.endsWith("roles/owner") || role.endsWith("roles/editor");
                
                for (String member : b.getMembersList()) {
                    if (member.startsWith("user:") || member.startsWith("serviceAccount:")) {
                        String email = member.startsWith("user:") ? member.substring(5) : member.substring(15);
                        
                        // 구글 시스템 관리형 서비스 에이전트 예외 처리 (조치 대상에서 제외)
                        if (email.endsWith("@cloudservices.gserviceaccount.com") || 
                           (email.startsWith("service-") && email.endsWith(".iam.gserviceaccount.com"))) {
                            continue;
                        }
                        
                        userRolesMap.computeIfAbsent(email, k -> new ArrayList<>()).add(role);
                        if (isOwnerOrEditorRole) {
                            hasOwnerOrEditor = true;
                        }
                    }
                }
            }
            
            if (userRolesMap.isEmpty()) {
                String d = "등록된 사용자 및 서비스 계정이 없습니다.";
                details.add(createFullDetail(report, "IAM", "사용자 계정 및 권한", "점검 완료", d, d));
            } else if (hasOwnerOrEditor) {
                StringBuilder sb = new StringBuilder();
                sb.append("소유자 및 편집자 권한이 부여되어 있는 IAM 계정이 존재합니다.\n\n  [계정] / [권한]\n");
                
                for (Map.Entry<String, List<String>> entry : userRolesMap.entrySet()) {
                    List<String> roles = entry.getValue();
                    boolean userHasOwnerOrEditor = false;
                    for (String r : roles) {
                        if (r.endsWith("roles/owner") || r.endsWith("roles/editor")) {
                            userHasOwnerOrEditor = true;
                            break;
                        }
                    }
                    if (userHasOwnerOrEditor) {
                        List<String> krRoles = new ArrayList<>();
                        for (String r : roles) {
                            if (r.endsWith("roles/owner") || r.endsWith("roles/editor")) {
                                krRoles.add(getKoreanRoleName(r));
                            }
                        }
                        sb.append("  - ").append(entry.getKey()).append(" / ").append(String.join(", ", krRoles)).append("\n");
                    }
                }
                
                sb.append("\n\nIAM은 최소 권한의 보안 원칙을 적용하여 실제로 필요한 것보다 더 많은 권한을 갖지 않도록 해야 합니다. \n");
                sb.append("특정 Google Cloud 리소스에 대한 세부적인 액세스 권한을 부여하고 다른 리소스에 대한 액세스를 방지할 수 있습니다. \n");
                sb.append("목적과 사용 유무가 확실하지 않은 계정에 대해서는 보안 강화를 위해 서비스 영향도 파악 후 삭제를 권장드립니다.");
                
                details.add(createFullDetail(report, "IAM", "사용자 계정 및 권한", "조치 권고", sb.toString(), sb.toString()));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("IAM에 등록된 계정의 권한 현황은 아래와 같습니다.\n\n  [계정] / [권한]\n");
                for (Map.Entry<String, List<String>> entry : userRolesMap.entrySet()) {
                    List<String> krRoles = new ArrayList<>();
                    for (String r : entry.getValue()) {
                        krRoles.add(getKoreanRoleName(r));
                    }
                    sb.append("  - ").append(entry.getKey()).append(" / ").append(String.join(", ", krRoles)).append("\n");
                }
                details.add(createFullDetail(report, "IAM", "사용자 계정 및 권한", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
            }
        } catch (Exception e) { 
            details.add(createFullDetail(report, "IAM", "사용자 계정 및 권한", "확인 불가", "권한 부족", "권한을 확인하십시오.")); 
        }
        return details;
    }

    private static class SaKeyInfo {
        String email;
        long createdTime;
        boolean isOld;
        SaKeyInfo(String email, long createdTime, boolean isOld) {
            this.email = email;
            this.createdTime = createdTime;
            this.isOld = isOld;
        }
    }

    private String formatEpochToYmd(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"));
    }

    private List<InfraAuditDetail> auditServiceAccounts(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (IAMClient client = IAMClient.create(IAMSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            List<SaKeyInfo> allKeys = new ArrayList<>();
            boolean hasOld = false;
            long threshold = Instant.now().minus(90, java.time.temporal.ChronoUnit.DAYS).getEpochSecond();
            for (com.google.iam.admin.v1.ServiceAccount sa : client.listServiceAccounts("projects/" + projectId).iterateAll()) {
                String saEmail = sa.getEmail();
                try {
                    for (ServiceAccountKey k : client.listServiceAccountKeys(ListServiceAccountKeysRequest.newBuilder().setName(sa.getName()).build()).getKeysList()) {
                        if ("USER_MANAGED".equals(k.getKeyType().name())) {
                            long createdTime = k.getValidAfterTime().getSeconds();
                            boolean isOld = createdTime < threshold;
                            if (isOld) {
                                hasOld = true;
                            }
                            allKeys.add(new SaKeyInfo(saEmail, createdTime, isOld));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to list keys for service account: " + saEmail, e);
                }
            }

            StringBuilder sb = new StringBuilder();
            if (hasOld) {
                sb.append("생성 후 90일이 경과한 서비스 계정 키가 존재합니다.\n\n");
                sb.append("  [서비스 계정] / [키 생성일]\n");
                for (SaKeyInfo info : allKeys) {
                    sb.append("  - ").append(info.email).append(" / ").append(formatEpochToYmd(info.createdTime)).append("\n");
                }
                sb.append("\n");
                sb.append("서비스 계정 키는 서비스 계정으로 인증할 수 있는 비공개 키로, 유출될 경우 심각한 보안 사고로 이어질 수 있습니다.\n");
                sb.append("서비스 계정 키를 포함하여 관리하는 모든 키를 정기적으로 순환하는 것이 좋습니다.");
            } else {
                sb.append("생성 후 90일이 경과한 서비스 계정 키가 없습니다.\n\n");
                sb.append("  [서비스 계정] / [키 생성일]\n");
                if (allKeys.isEmpty()) {
                    sb.append("  - (등록된 서비스 계정 키가 없습니다.)\n");
                } else {
                    for (SaKeyInfo info : allKeys) {
                        sb.append("  - ").append(info.email).append(" / ").append(formatEpochToYmd(info.createdTime)).append("\n");
                    }
                }
            }

            String resultText = sb.toString().stripTrailing();
            String remediation = "서비스 계정 키는 서비스 계정으로 인증할 수 있는 비공개 키로, 유출될 경우 심각한 보안 사고로 이어질 수 있습니다.\n" +
                    "서비스 계정 키를 포함하여 관리하는 모든 키를 정기적으로 순환하는 것이 좋습니다.";

            details.add(createFullDetail(report, "Service Accounts", "서비스 계정 키", hasOld ? "조치 권고" : "점검 완료", resultText, remediation));
        } catch (Exception e) {
            log.error("Service Accounts audit failed: ", e);
            details.add(createFullDetail(report, "Service Accounts", "서비스 계정 키", "확인 불가", "권한 부족", "권한을 확인하십시오."));
        }
        return details;
    }

    private String resolveLbNameFromTarget(String projectId, GoogleCredentials credentials, ForwardingRule rule) {
        String target = rule.getTarget() != null ? rule.getTarget() : "";
        if (target.isEmpty()) {
            if (rule.getBackendService() != null && !rule.getBackendService().isEmpty()) {
                return rule.getBackendService().substring(rule.getBackendService().lastIndexOf('/') + 1);
            }
            return rule.getName();
        }
        
        try {
            if (target.contains("/backendServices/") || target.contains("/targetPools/") || target.contains("/targetInstances/")) {
                return target.substring(target.lastIndexOf('/') + 1);
            } else if (target.contains("/targetHttpProxies/")) {
                String proxyName = target.substring(target.lastIndexOf('/') + 1);
                boolean isGlobal = target.contains("/global/");
                if (isGlobal) {
                    try (com.google.cloud.compute.v1.TargetHttpProxiesClient client = com.google.cloud.compute.v1.TargetHttpProxiesClient.create(com.google.cloud.compute.v1.TargetHttpProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        com.google.cloud.compute.v1.TargetHttpProxy proxy = client.get(projectId, proxyName);
                        if (proxy.getUrlMap() != null) return proxy.getUrlMap().substring(proxy.getUrlMap().lastIndexOf('/') + 1);
                    }
                } else {
                    String region = extractRegionFromUrl(target);
                    try (com.google.cloud.compute.v1.RegionTargetHttpProxiesClient client = com.google.cloud.compute.v1.RegionTargetHttpProxiesClient.create(com.google.cloud.compute.v1.RegionTargetHttpProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        com.google.cloud.compute.v1.TargetHttpProxy proxy = client.get(projectId, region, proxyName);
                        if (proxy.getUrlMap() != null) return proxy.getUrlMap().substring(proxy.getUrlMap().lastIndexOf('/') + 1);
                    }
                }
            } else if (target.contains("/targetHttpsProxies/")) {
                String proxyName = target.substring(target.lastIndexOf('/') + 1);
                boolean isGlobal = target.contains("/global/");
                if (isGlobal) {
                    try (com.google.cloud.compute.v1.TargetHttpsProxiesClient client = com.google.cloud.compute.v1.TargetHttpsProxiesClient.create(com.google.cloud.compute.v1.TargetHttpsProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        com.google.cloud.compute.v1.TargetHttpsProxy proxy = client.get(projectId, proxyName);
                        if (proxy.getUrlMap() != null) return proxy.getUrlMap().substring(proxy.getUrlMap().lastIndexOf('/') + 1);
                    }
                } else {
                    String region = extractRegionFromUrl(target);
                    try (com.google.cloud.compute.v1.RegionTargetHttpsProxiesClient client = com.google.cloud.compute.v1.RegionTargetHttpsProxiesClient.create(com.google.cloud.compute.v1.RegionTargetHttpsProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        com.google.cloud.compute.v1.TargetHttpsProxy proxy = client.get(projectId, region, proxyName);
                        if (proxy.getUrlMap() != null) return proxy.getUrlMap().substring(proxy.getUrlMap().lastIndexOf('/') + 1);
                    }
                }
            } else if (target.contains("/targetTcpProxies/")) {
                String proxyName = target.substring(target.lastIndexOf('/') + 1);
                boolean isGlobal = target.contains("/global/");
                if (isGlobal) {
                    try (com.google.cloud.compute.v1.TargetTcpProxiesClient client = com.google.cloud.compute.v1.TargetTcpProxiesClient.create(com.google.cloud.compute.v1.TargetTcpProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        com.google.cloud.compute.v1.TargetTcpProxy proxy = client.get(projectId, proxyName);
                        if (proxy.getService() != null) return proxy.getService().substring(proxy.getService().lastIndexOf('/') + 1);
                    }
                } else {
                    String region = extractRegionFromUrl(target);
                    try (com.google.cloud.compute.v1.RegionTargetTcpProxiesClient client = com.google.cloud.compute.v1.RegionTargetTcpProxiesClient.create(com.google.cloud.compute.v1.RegionTargetTcpProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        com.google.cloud.compute.v1.TargetTcpProxy proxy = client.get(projectId, region, proxyName);
                        if (proxy.getService() != null) return proxy.getService().substring(proxy.getService().lastIndexOf('/') + 1);
                    }
                }
            } else if (target.contains("/targetSslProxies/")) {
                String proxyName = target.substring(target.lastIndexOf('/') + 1);
                try (com.google.cloud.compute.v1.TargetSslProxiesClient client = com.google.cloud.compute.v1.TargetSslProxiesClient.create(com.google.cloud.compute.v1.TargetSslProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                    com.google.cloud.compute.v1.TargetSslProxy proxy = client.get(projectId, proxyName);
                    if (proxy.getService() != null) return proxy.getService().substring(proxy.getService().lastIndexOf('/') + 1);
                }
            } else if (target.contains("/targetGrpcProxies/")) {
                String proxyName = target.substring(target.lastIndexOf('/') + 1);
                try (com.google.cloud.compute.v1.TargetGrpcProxiesClient client = com.google.cloud.compute.v1.TargetGrpcProxiesClient.create(com.google.cloud.compute.v1.TargetGrpcProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                    com.google.cloud.compute.v1.TargetGrpcProxy proxy = client.get(projectId, proxyName);
                    if (proxy.getUrlMap() != null) return proxy.getUrlMap().substring(proxy.getUrlMap().lastIndexOf('/') + 1);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve LB name from target: {}", target, e);
        }
        
        if (target.contains("/")) {
            return target.substring(target.lastIndexOf('/') + 1);
        }
        return rule.getName();
    }
    
    private String extractRegionFromUrl(String url) {
        String[] parts = url.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("regions".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private List<InfraAuditDetail> auditLoadBalancing(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        
        List<ForwardingRule> forwardingRules = new ArrayList<>();
        try {
            forwardingRules = gcpResourceFetcher.getForwardingRules(credentials, projectId);
        } catch (Exception e) {
            log.warn("Failed to list forwarding rules", e);
        }

        List<UrlMap> urlMaps = new ArrayList<>();
        try {
            urlMaps = gcpResourceFetcher.getUrlMaps(credentials, projectId);
        } catch (Exception e) {
            log.warn("Failed to list url maps", e);
        }

        if (forwardingRules.isEmpty() && urlMaps.isEmpty()) {
            String unusedText = "Load Balancing을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
            details.add(createFullDetail(report, "Load Balancing", "부하 분산 사용 여부", "미사용", unusedText, unusedText));
            details.add(createFullDetail(report, "Load Balancing", "HTTPS 프론트엔드 SSL 인증서 구성 여부", "미사용", unusedText, unusedText));
            details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스", "미사용", unusedText, unusedText));
            details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스 로깅", "미사용", unusedText, unusedText));
            return details;
        }

        // 1. 부하 분산 사용 여부
        StringBuilder lbSb = new StringBuilder();
        lbSb.append("부하 분산기를 사용하고 있으며, 현황은 아래와 같습니다.\n\n");
        lbSb.append("  [부하분산기 이름] / [유형] / [액세스 유형]\n");
        
        Map<String, String[]> uniqueLbs = new LinkedHashMap<>();
        for (ForwardingRule rule : forwardingRules) {
            String target = rule.getTarget() != null ? rule.getTarget().toLowerCase() : "";
            
            // VPN용 포워딩 룰은 부하 분산기 목록에서 필터링
            if (target.contains("targetvpngateway")) {
                continue;
            }
            
            String lbName = resolveLbNameFromTarget(projectId, credentials, rule);
            
            String type = "네트워크(패스 스루)";
            String scheme = rule.getLoadBalancingScheme() != null ? rule.getLoadBalancingScheme().toUpperCase() : "";
            
            if (scheme.contains("EXTERNAL_MANAGED") || scheme.contains("INTERNAL_MANAGED")) {
                type = "애플리케이션";
            } else if (target.contains("targethttp") || target.contains("targetgrpc")) {
                type = "애플리케이션(기본)";
            } else if (target.contains("targettcp") || target.contains("targetssl")) {
                type = "네트워크(프록시)";
            }
            
            String accessType = scheme.contains("INTERNAL") ? "내부" : "외부";
            
            if (!uniqueLbs.containsKey(lbName)) {
                uniqueLbs.put(lbName, new String[]{type, accessType});
            } else {
                // Update type if we find a more specific one (e.g. proxy vs passthrough)
                if (type.contains("애플리케이션") || type.contains("네트워크(프록시)")) {
                    uniqueLbs.get(lbName)[0] = type;
                }
            }
        }
        
        // 프론트엔드(ForwardingRule)가 없는 UrlMap(애플리케이션 부하분산기) 추가 식별
        for (UrlMap map : urlMaps) {
            String lbName = map.getName();
            if (!uniqueLbs.containsKey(lbName)) {
                uniqueLbs.put(lbName, new String[]{"애플리케이션", "식별불가(프론트엔드 미구성)"});
            }
        }
        
        for (Map.Entry<String, String[]> entry : uniqueLbs.entrySet()) {
            String lbName = entry.getKey();
            String[] info = entry.getValue();
            lbSb.append("   - ").append(lbName)
                .append(" / ").append(info[0])
                .append(" / ").append(info[1]).append("\n");
        }
        details.add(createFullDetail(report, "Load Balancing", "부하 분산 사용 여부", "점검 완료", lbSb.toString().stripTrailing(), lbSb.toString().stripTrailing()));

        // 2. HTTPS 프론트엔드 SSL 인증서 구성 여부
        List<String> sslOkList = new ArrayList<>();
        List<String> sslUnsetList = new ArrayList<>();
        
        for (ForwardingRule rule : forwardingRules) {
            String target = rule.getTarget() != null ? rule.getTarget().toLowerCase() : "";
            if (target.isEmpty()) continue;
            String protocol = rule.getIPProtocol() != null ? rule.getIPProtocol() : "";
            
            // Check if it is HTTPS/SSL frontend
            if (protocol.equalsIgnoreCase("HTTPS") || target.contains("/targethttpsproxies/") || target.contains("/targetsslproxies/")) {
                List<String> certs = new ArrayList<>();
                String proxyName = target.substring(target.lastIndexOf('/') + 1);
                if (proxyName.isEmpty()) {
                    proxyName = resolveLbNameFromTarget(projectId, credentials, rule);
                }
                
                if (target.contains("/targethttpsproxies/")) {
                    if (target.contains("/global/")) {
                        try (TargetHttpsProxiesClient pClient = TargetHttpsProxiesClient.create(TargetHttpsProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                            TargetHttpsProxy proxy = pClient.get(projectId, proxyName);
                            for (String certUrl : proxy.getSslCertificatesList()) {
                                certs.add(certUrl.substring(certUrl.lastIndexOf('/') + 1));
                            }
                        } catch (Exception e) {
                            log.warn("Failed to get global target https proxy: " + proxyName, e);
                        }
                    } else if (target.contains("/regions/")) {
                        String region = target.substring(target.indexOf("/regions/") + 9);
                        region = region.substring(0, region.indexOf('/'));
                        try (RegionTargetHttpsProxiesClient pClient = RegionTargetHttpsProxiesClient.create(RegionTargetHttpsProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                            TargetHttpsProxy proxy = pClient.get(projectId, region, proxyName);
                            for (String certUrl : proxy.getSslCertificatesList()) {
                                certs.add(certUrl.substring(certUrl.lastIndexOf('/') + 1));
                            }
                        } catch (Exception e) {
                            log.warn("Failed to get regional target https proxy: " + proxyName, e);
                        }
                    }
                } else if (target.contains("/targetsslproxies/")) {
                    try (TargetSslProxiesClient pClient = TargetSslProxiesClient.create(TargetSslProxiesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                        TargetSslProxy proxy = pClient.get(projectId, proxyName);
                        for (String certUrl : proxy.getSslCertificatesList()) {
                            certs.add(certUrl.substring(certUrl.lastIndexOf('/') + 1));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get global target ssl proxy: " + proxyName, e);
                    }
                }
                
                String entryStr = proxyName + " / " + String.join(", ", certs);
                if (!certs.isEmpty()) {
                    if (!sslOkList.contains(entryStr)) {
                        sslOkList.add(entryStr);
                    }
                } else {
                    if (!sslUnsetList.contains(proxyName)) {
                        sslUnsetList.add(proxyName);
                    }
                }
            }
        }
        
        if (sslUnsetList.isEmpty()) {
            StringBuilder sslSb = new StringBuilder();
            sslSb.append("모든 HTTPS 부하 분산기에 SSL 인증서가 구성되어 있습니다.\n\n");
            sslSb.append("  [프록시 이름] / [인증서]\n");
            if (sslOkList.isEmpty()) {
                sslSb.append("  - 없음 (HTTPS 부하 분산기 미검출)\n");
            } else {
                for (String okItem : sslOkList) {
                    sslSb.append("  - ").append(okItem).append("\n");
                }
            }
            details.add(createFullDetail(report, "Load Balancing", "HTTPS 프론트엔드 SSL 인증서 구성 여부", "점검 완료", sslSb.toString().stripTrailing(), sslSb.toString().stripTrailing()));
        } else {
            StringBuilder sslSb = new StringBuilder();
            sslSb.append("SSL 인증서가 구성되어 있지 않은 HTTPS 부하 분산기가 존재합니다.\n\n");
            sslSb.append("  [프록시 이름]\n");
            for (String unsetLb : sslUnsetList) {
                sslSb.append("  - ").append(unsetLb).append("\n");
            }
            sslSb.append("\n\n프로토콜이 HTTPS인 프론트엔드의 SSL 인증서가 구성되어 있지 않을 경우, 데이터가 암호화되지 않은 채 전송되어 데이터 유출의 위험이 있을 수 있습니다. \n");
            sslSb.append("필요 여부를 검토하시어 서비스의 신뢰성 및 데이터 보호를 위해 SSL 인증서를 구성하여 사용하실 것을 권장드립니다.");
            
            details.add(createFullDetail(report, "Load Balancing", "HTTPS 프론트엔드 SSL 인증서 구성 여부", "조치 권고", sslSb.toString(), sslSb.toString()));
        }

        // 3. 백엔드 서비스 & 4. 백엔드 서비스 로깅
        List<BackendService> backendServices = new ArrayList<>();
        try (BackendServicesClient client = BackendServicesClient.create(BackendServicesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, BackendServicesScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getBackendServicesList() == null) continue;
                for (BackendService bs : entry.getValue().getBackendServicesList()) {
                    backendServices.add(bs);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list backend services", e);
        }

        if (backendServices.isEmpty()) {
            String noBackendMsg = "등록된 백엔드 서비스가 없습니다.";
            details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스", "점검 완료", noBackendMsg, noBackendMsg));
            details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스 로깅", "점검 완료", noBackendMsg, noBackendMsg));
        } else {
            // A. 백엔드 서비스 상태 점검
            List<String> healthyBackends = new ArrayList<>();
            List<String> unhealthyBackends = new ArrayList<>();
            
            try (BackendServicesClient globalClient = BackendServicesClient.create(BackendServicesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                for (BackendService bs : backendServices) {
                    boolean isHealthy = true;
                    String protocol = bs.getProtocol() != null ? bs.getProtocol().toUpperCase() : "UNKNOWN";
                    String disp = bs.getName() + " / 백엔드 서비스(" + protocol + ")";
                    
                    if (bs.getBackendsCount() > 0) {
                        for (Backend b : bs.getBackendsList()) {
                            String group = b.getGroup();
                            boolean supportsHealthCheck = true;
                            if (group.contains("/networkEndpointGroups/")) {
                                if (group.contains("/regions/") || group.contains("/global/")) {
                                    supportsHealthCheck = false;
                                }
                            }
                            
                            if (supportsHealthCheck) {
                                ResourceGroupReference ref = ResourceGroupReference.newBuilder().setGroup(group).build();
                                try {
                                    if (bs.getRegion() != null && !bs.getRegion().isEmpty()) {
                                        String regionUrl = bs.getRegion();
                                        String region = regionUrl.substring(regionUrl.lastIndexOf('/') + 1);
                                        try (RegionBackendServicesClient regClient = RegionBackendServicesClient.create(RegionBackendServicesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                                            BackendServiceGroupHealth health = regClient.getHealth(projectId, region, bs.getName(), ref);
                                            if (health.getHealthStatusList() != null) {
                                                for (HealthStatus hs : health.getHealthStatusList()) {
                                                    if ("UNHEALTHY".equalsIgnoreCase(hs.getHealthState())) {
                                                        isHealthy = false;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        BackendServiceGroupHealth health = globalClient.getHealth(projectId, bs.getName(), ref);
                                        if (health.getHealthStatusList() != null) {
                                            for (HealthStatus hs : health.getHealthStatusList()) {
                                                if ("UNHEALTHY".equalsIgnoreCase(hs.getHealthState())) {
                                                    isHealthy = false;
                                                    break;
                                                    }
                                                }
                                            }
                                        }
                                } catch (Exception e) {
                                    log.warn("Failed to check health of backend group: " + group, e);
                                }
                            }
                            if (!isHealthy) break;
                        }
                    }
                    
                    if (isHealthy) {
                        healthyBackends.add(disp);
                    } else {
                        unhealthyBackends.add(disp);
                    }
                }
            } catch (Exception e) {
                log.warn("Error checking backend health status", e);
            }
            
            if (unhealthyBackends.isEmpty()) {
                StringBuilder healthSb = new StringBuilder();
                healthSb.append("모든 부하 분산기의 백엔드가 정상 상태입니다.\n\n");
                healthSb.append("  [백엔드] / [유형]([프로토콜])\n");
                for (int i = 0; i < healthyBackends.size(); i++) {
                    healthSb.append("  - ").append(healthyBackends.get(i));
                    if (i < healthyBackends.size() - 1) {
                        healthSb.append("\n");
                    }
                }
                details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스", "점검 완료", healthSb.toString(), healthSb.toString()));
            } else {
                StringBuilder healthSb = new StringBuilder();
                healthSb.append("백엔드가 비정상 상태인 부하 분산기가 존재합니다.\n\n");
                healthSb.append("  [백엔드] / [유형]([프로토콜])\n");
                for (String uhItem : unhealthyBackends) {
                    healthSb.append("  - ").append(uhItem).append("\n");
                }
                healthSb.append("\n백엔드가 비정상 상태이면 부하 분산기가 해당 백엔드로 트래픽을 보내지 않습니다. \n");
                healthSb.append("이로 인해 정상 운영 중인 다른 백엔드에 부하가 가중되어 서비스 전체의 응답 속도가 느려지거나, 심한 경우 서비스 장애로 이어질 수 있습니다.\n");
                healthSb.append("비정상 상태인 인스턴스의 애플리케이션 로그를 확인하고, 헬스체크에 설정된 포트 및 경로가 정상적으로 응답하는지, 방화벽 규칙이 헬스체크 프로브를 차단하지 않는지 점검 하시기를 권장드립니다.");
                
                details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스", "조치 권고", healthSb.toString(), healthSb.toString()));
            }

            // B. 백엔드 서비스 로깅 점검
            List<String> logOkList = new ArrayList<>();
            List<String> logUnsetList = new ArrayList<>();
            
            for (BackendService bs : backendServices) {
                boolean loggingEnabled = false;
                try {
                    if (bs.hasLogConfig() && bs.getLogConfig() != null && bs.getLogConfig().hasEnable() && bs.getLogConfig().getEnable()) {
                        loggingEnabled = true;
                    }
                } catch (Exception e) {
                    log.warn("Failed to check logging configuration for backend service: " + bs.getName(), e);
                }
                
                if (loggingEnabled) {
                    logOkList.add(bs.getName());
                } else {
                    logUnsetList.add(bs.getName());
                }
            }
            
            if (logUnsetList.isEmpty()) {
                StringBuilder logSb = new StringBuilder();
                logSb.append("모든 백엔드 서비스에 로깅이 사용 설정되어 있습니다.\n\n");
                logSb.append("  [백엔드 서비스]\n");
                for (String okName : logOkList) {
                    logSb.append("  - ").append(okName).append("\n");
                }
                details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스 로깅", "점검 완료", logSb.toString().stripTrailing(), logSb.toString().stripTrailing()));
            } else {
                StringBuilder logSb = new StringBuilder();
                logSb.append("로깅이 사용 설정되어 있지 않은 백엔드 서비스가 존재합니다.\n\n");
                logSb.append("  [백엔드 서비스]\n");
                for (String unsetName : logUnsetList) {
                    logSb.append("  - ").append(unsetName).append("\n");
                }
                logSb.append("\n\n백엔드 서비스 기반의 부하 분산기는 로그를 사용 설정, 사용 중지, 조회할 수 있습니다. \n");
                logSb.append("로그를 사용 설점함으로서 장애 발생 시 문제의 근본 원인을 진단하고 해결하여 서비스 안정성을 극대화할 수 있습니다.\n");
                logSb.append("안정적이고 효율적인 서비스 운영을 위해 백엔드 서비스에 로깅 적용을 권장드립니다.");
                
                details.add(createFullDetail(report, "Load Balancing", "백엔드 서비스 로깅", "조치 권고", logSb.toString(), logSb.toString()));
            }
        }

        return details;
    }

    private List<InfraAuditDetail> auditVpn(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (VpnTunnelsClient tunnelClient = VpnTunnelsClient.create(VpnTunnelsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build());
             VpnGatewaysClient gatewayClient = VpnGatewaysClient.create(VpnGatewaysSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build());
             RoutersClient routersClient = RoutersClient.create(RoutersSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            
            StringBuilder sb = new StringBuilder();
            StringBuilder statusSb = new StringBuilder();
            boolean hasAnyVpn = false;
            boolean allNormal = true;
            boolean allHA = true;
            StringBuilder haSb = new StringBuilder();
            StringBuilder nonHaSb = new StringBuilder();
            StringBuilder abnormalSb = new StringBuilder();

            for (Map.Entry<String, VpnTunnelsScopedList> entry : tunnelClient.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getVpnTunnelsList() == null) continue;
                for (VpnTunnel tunnel : entry.getValue().getVpnTunnelsList()) {
                    hasAnyVpn = true;
                    String tunnelName = tunnel.getName();
                    String tunnelStatus = "ESTABLISHED".equalsIgnoreCase(tunnel.getStatus()) ? "설정됨" : ("NO_INCOMING_PACKETS".equalsIgnoreCase(tunnel.getStatus()) ? "수신 패킷 없음" : tunnel.getStatus());
                    String bgpStatus = "BGP 미설정";
                    
                    // Fetch gateway info and HA status
                    String gatewayName = "Unknown";
                    String gatewayIp = "Unknown";
                    boolean isHa = false;
                    try {
                        String gatewayUrl = tunnel.getVpnGateway();
                        if (gatewayUrl != null && !gatewayUrl.isEmpty()) {
                            VpnGateway gateway = gatewayClient.get(projectId, tunnel.getRegion().substring(tunnel.getRegion().lastIndexOf('/') + 1), gatewayUrl.substring(gatewayUrl.lastIndexOf('/') + 1));
                            gatewayName = gateway.getName();
                            if (gateway.getVpnInterfacesCount() > 0) {
                                int ifaceId = tunnel.hasVpnGatewayInterface() ? tunnel.getVpnGatewayInterface() : 0;
                                for (com.google.cloud.compute.v1.VpnGatewayVpnGatewayInterface iface : gateway.getVpnInterfacesList()) {
                                    if (iface.hasId() && iface.getId() == ifaceId) {
                                        gatewayIp = iface.getIpAddress();
                                        break;
                                    }
                                }
                                if ("Unknown".equals(gatewayIp)) {
                                    gatewayIp = gateway.getVpnInterfaces(0).getIpAddress();
                                }
                            }
                            isHa = true; // VpnGateway is for HA VPN, TargetVpnGateway is for Classic
                        } else if (tunnel.hasTargetVpnGateway() && !tunnel.getTargetVpnGateway().isEmpty()) {
                            String targetUrl = tunnel.getTargetVpnGateway();
                            gatewayName = targetUrl.substring(targetUrl.lastIndexOf('/') + 1);
                            gatewayIp = "Classic VPN";
                            isHa = false;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get gateway info for tunnel: " + tunnelName);
                    }
                    
                    String dispName = tunnelName + " / " + gatewayName + "(" + gatewayIp + ")";
                    
                    if (!"설정됨".equals(tunnelStatus)) {
                        allNormal = false;
                        abnormalSb.append("  - ").append(dispName).append(" / ").append(tunnelStatus).append(" / ").append(bgpStatus).append("\n");
                    } else {
                        statusSb.append("  - ").append(dispName).append("\n");
                    }
                    
                    if (isHa) {
                        haSb.append("  - ").append(dispName).append("\n");
                    } else {
                        allHA = false;
                        nonHaSb.append("  - ").append(dispName).append("\n");
                    }
                }
            }

            if (!hasAnyVpn) {
                details.add(createFullDetail(report, "Cloud VPN", "Cloud VPN 사용 여부", "미사용", "Cloud VPN을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "Cloud VPN 서비스을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
                details.add(createFullDetail(report, "Cloud VPN", "Cloud VPN 상태", "미사용", "Cloud VPN을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "Cloud VPN 서비스을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
            } else {
                String haRem = "중단 없는 서비스 운영을 위해 HA(고가용성) VPN 구성을 권장드립니다. \n현재 구성 여부를 확인하고, 아직 HA VPN을 도입하지 않으셨다면 비즈니스 연속성 확보를 위해 HA VPN으로 전환하시기를 제안드립니다.";
                String haResult = allHA ? "Cloud VPN을 사용하고 있으며, 모든 VPN이 HA(고가용성)으로 구성되어 있습니다.\n\n  [Cloud VPN 터널 이름] / [Cloud VPN 게이트웨이 이름(IP)]\n" + haSb.toString().stripTrailing()
                                       : "HA(고가용성) VPN 구성이 되어 있지 않은 VPN 구성이 있습니다.\n\n  [Cloud VPN 터널 이름] / [Cloud VPN 게이트웨이 이름(IP)]\n" + nonHaSb.toString().stripTrailing() + "\n\n" + haRem;
                details.add(createFullDetail(report, "Cloud VPN", "Cloud VPN 사용 여부", allHA ? "점검 완료" : "조치 권고", haResult, haRem));
                
                String vpnStatusResult;
                if (allNormal) {
                    vpnStatusResult = "모든 VPN의 VPN 터널 및 BGP 세션 상태가 정상 상태입니다.\n\n  [Cloud VPN 터널 이름] / [Cloud VPN 게이트웨이 이름(IP)]\n" + statusSb.toString().stripTrailing();
                } else {
                    vpnStatusResult = "VPN 터널 및 BGP 세션 상태가 비정상인 VPN이 존재합니다.\n\n  [Cloud VPN 터널 이름] / [Cloud VPN 게이트웨이 이름(IP)] / [VPN 터널 상태] / [BGP 세션 상태]\n" + abnormalSb.toString().stripTrailing() + statusSb.toString().stripTrailing();
                }
                details.add(createFullDetail(report, "Cloud VPN", "Cloud VPN 상태", allNormal ? "점검 완료" : "조치 안내", vpnStatusResult, "Cloud VPN 터널의 VPN 터널 상태 및 BGP 세션 상태를 확인하시어 서비스 통신에 문제가 없는지 확인하실 것을 권장드립니다"));
            }
        } catch (Exception e) { log.error("VPN audit failed: ", e); }
        return details;
    }

    private List<InfraAuditDetail> auditDisks(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            List<Disk> disks = gcpResourceFetcher.getComputeDisks(credentials, projectId);
            int total = 0;
            List<Disk> connectedDisks = new ArrayList<>();
            List<Disk> disconnectedDisks = new ArrayList<>();

            for (Disk d : disks) {
                if (d.hasStatus()) {
                    String status = d.getStatus();
                    if ("DELETING".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                        continue;
                    }
                }
                total++;
                List<String> users = d.getUsersList();
                if (users != null && !users.isEmpty()) {
                    connectedDisks.add(d);
                } else {
                    disconnectedDisks.add(d);
                }
            }

            if (total == 0) {
                String d = "Disk을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Disks", "디스크", "미사용", d, d));
            } else if (disconnectedDisks.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("모든 디스크에 인스턴스가 연결되어 있습니다.\n\n  [Disk 이름]\n");
                for (Disk d : connectedDisks) {
                    sb.append("  - ").append(d.getName()).append("\n");
                }
                details.add(createFullDetail(report, "Disks", "디스크", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("연결된 인스턴스가 없는 디스크가 존재합니다.\n\n  [Disk 이름]\n");
                for (Disk d : disconnectedDisks) {
                    sb.append("  - ").append(d.getName()).append("\n");
                }
                sb.append("\n\n사용하고 있지 않은 디스크를 삭제하면 불필요한 스토리지 비용을 절감할 수 있습니다. \n");
                sb.append("디스크의 사용 여부를 판단하시어 더 이상 사용하지 않는 디스크는 스냅샷 생성 등 데이터 백업 여부를 확인하신 후 삭제하시는 것을 권장드립니다.");
                
                details.add(createFullDetail(report, "Disks", "디스크", "조치 권고", sb.toString(), sb.toString()));
            }
        } catch (Exception e) { log.warn("Disks failed: ", e); }
        return details;
    }

    private String getDiskNameFromUrl(String diskUrl) {
        if (diskUrl == null || diskUrl.isEmpty()) return "-";
        int lastSlash = diskUrl.lastIndexOf('/');
        if (lastSlash != -1) {
            return diskUrl.substring(lastSlash + 1);
        }
        return diskUrl;
    }

    private String getOneHourLater(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return "";
        try {
            String[] parts = timeStr.split(":");
            int hour = (Integer.parseInt(parts[0]) + 1) % 24;
            return String.format("%02d:%s", hour, parts[1]);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatTimeAmPm(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return timeStr;
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            String ampm = hour >= 12 ? "오후" : "오전";
            int displayHour = hour % 12;
            if (displayHour == 0) displayHour = 12;
            return String.format("%s %d:%02d", ampm, displayHour, minute);
        } catch (Exception e) {
            return timeStr;
        }
    }

    private String formatScheduleFrequency(ResourcePolicy rp) {
        try {
            if (!rp.hasSnapshotSchedulePolicy()) return "알 수 없음";
            ResourcePolicySnapshotSchedulePolicy ssp = rp.getSnapshotSchedulePolicy();
            if (!ssp.hasSchedule()) return "설정 없음";
            ResourcePolicySnapshotSchedulePolicySchedule sched = ssp.getSchedule();
            if (sched.hasDailySchedule()) {
                String start = sched.getDailySchedule().getStartTime();
                String end = getOneHourLater(start);
                return "매일 " + formatTimeAmPm(start) + " ~ " + formatTimeAmPm(end) + "에 시작";
            }
            if (sched.hasWeeklySchedule()) {
                StringBuilder weeklySb = new StringBuilder();
                weeklySb.append("매주 ");
                List<ResourcePolicyWeeklyCycleDayOfWeek> days = sched.getWeeklySchedule().getDayOfWeeksList();
                for (int i = 0; i < days.size(); i++) {
                    weeklySb.append(days.get(i).getDay()).append(" ").append(formatTimeAmPm(days.get(i).getStartTime()));
                    if (i < days.size() - 1) weeklySb.append(", ");
                }
                weeklySb.append("에 시작");
                return weeklySb.toString();
            }
            if (sched.hasHourlySchedule()) {
                return "매 " + sched.getHourlySchedule().getHoursInCycle() + "시간마다 시작";
            }
        } catch (Exception e) {
            log.warn("Failed to format schedule frequency: " + rp.getName(), e);
        }
        return "기타 일정";
    }

    private List<InfraAuditDetail> auditSnapshots(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        
        // 1. 스냅샷 사용 여부
        try {
            List<Snapshot> snapshots = gcpResourceFetcher.getComputeSnapshots(credentials, projectId);
            
            if (snapshots.isEmpty()) {
                String remediation = "생성된 스냅샷이 없습니다.\n\n" +
                        "스냅샷은 디스크 장애, 사용자 실수, 랜섬웨어 공격 등 예기치 않은 상황에서 데이터를 복구할 수 있는 유일한 수단입니다. \n" +
                        "스냅샷이 없으면 데이터 유실 시 복구가 불가능하여 심각한 서비스 중단을 초래할 수 있습니다.\n" +
                        "중요 데이터가 저장된 디스크에 대해서는 스냅샷 스케줄을 설정하여 데이터를 안전하게 보호 하시기를 권장 합니다.";
                details.add(createFullDetail(report, "Snapshots", "스냅샷 사용 여부", "조치 권고", remediation, remediation));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("스냅샷을 사용하고 있으며, 현황은 아래와 같습니다.\n\n");
                sb.append("  [스냅샷] / [위치] / [소스 디스크]\n");
                for (Snapshot s : snapshots) {
                    String loc = s.getStorageLocationsList() != null && !s.getStorageLocationsList().isEmpty()
                            ? s.getStorageLocationsList().get(0) : "global";
                    String sourceDisk = getDiskNameFromUrl(s.getSourceDisk());
                    sb.append("  - ").append(s.getName()).append(" / ").append(loc).append(" / ").append(sourceDisk).append("\n");
                }
                String resultText = sb.toString().stripTrailing();
                details.add(createFullDetail(report, "Snapshots", "스냅샷 사용 여부", "점검 완료", resultText, resultText));
            }
        } catch (Exception e) {
            log.warn("Failed to audit snapshots usage", e);
            details.add(createFullDetail(report, "Snapshots", "스냅샷 사용 여부", "확인 불가", "권한 부족", "권한을 확인하십시오."));
        }
        
        // 2. 스냅샷 일정
        try (ResourcePoliciesClient rpClient = ResourcePoliciesClient.create(ResourcePoliciesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            Map<String, Set<String>> policyToVms = new HashMap<>();
            try (DisksClient disksClient = DisksClient.create(DisksSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                for (Map.Entry<String, DisksScopedList> entry : disksClient.aggregatedList(projectId).iterateAll()) {
                    if (entry.getValue().getDisksList() == null) continue;
                    for (Disk d : entry.getValue().getDisksList()) {
                        List<String> policies = d.getResourcePoliciesList();
                        if (policies != null && !policies.isEmpty()) {
                            List<String> users = d.getUsersList();
                            Set<String> vmNames = new HashSet<>();
                            if (users != null) {
                                for (String u : users) {
                                    int lastSlash = u.lastIndexOf('/');
                                    if (lastSlash != -1) {
                                        vmNames.add(u.substring(lastSlash + 1));
                                    }
                                }
                            }
                            for (String pUrl : policies) {
                                int lastSlash = pUrl.lastIndexOf('/');
                                if (lastSlash != -1) {
                                    String pName = pUrl.substring(lastSlash + 1);
                                    policyToVms.computeIfAbsent(pName, k -> new HashSet<>()).addAll(vmNames);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to map resource policies to VMs", e);
            }
            
            List<ResourcePolicy> schedulePolicies = new ArrayList<>();
            for (Map.Entry<String, ResourcePoliciesScopedList> entry : rpClient.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getResourcePoliciesList() == null) continue;
                for (ResourcePolicy rp : entry.getValue().getResourcePoliciesList()) {
                    if (rp.hasSnapshotSchedulePolicy()) {
                        schedulePolicies.add(rp);
                    }
                }
            }
            
            if (schedulePolicies.isEmpty()) {
                String remediation = "스냅샷 일정이 없습니다.\n\n" +
                        "Compute Engine 인스턴스 및 Kubernetes Engine에서 사용 중인 영구 디스크 중, 주요 서비스와 관련된 Disk에 대하여는 스냅샷 일정을 설정하여 주기적으로 백업하시는 것을 권장드립니다.";
                details.add(createFullDetail(report, "Snapshots", "스냅샷 일정", "조치 권고", remediation, remediation));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("스냅샷 일정이 존재합니다.\n\n");
                sb.append("  [스냅샷 일정 이름] / [일정 빈도(UTC)] / [다음에서 사용 중]\n");
                for (ResourcePolicy rp : schedulePolicies) {
                    String name = rp.getName();
                    String freq = formatScheduleFrequency(rp);
                    Set<String> vms = policyToVms.getOrDefault(name, Collections.emptySet());
                    String attachedVms = vms.isEmpty() ? "연결된 디스크 없음" : String.join(", ", vms);
                    sb.append("  ").append(name).append(" / ").append(freq).append(" / ").append(attachedVms).append("\n");
                }
                String resultText = sb.toString().stripTrailing();
                details.add(createFullDetail(report, "Snapshots", "스냅샷 일정", "점검 완료", resultText, resultText));
            }
        } catch (Exception e) {
            log.warn("Failed to audit snapshot schedules", e);
            details.add(createFullDetail(report, "Snapshots", "스냅샷 일정", "확인 불가", "권한 부족", "권한을 확인하십시오."));
        }
        
        return details;
    }

    private List<InfraAuditDetail> auditFirewalls(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        
        // 1. VPC 방화벽 규칙 점검
        try {
            List<Firewall> firewalls = gcpResourceFetcher.getComputeFirewalls(credentials, projectId);
            int total = 0;
            List<Firewall> anyIngressRules = new ArrayList<>();
            List<Firewall> anyEgressRules = new ArrayList<>();
            
            for (Firewall fw : firewalls) {
                total++;
                String dir = fw.getDirection();
                if ("EGRESS".equals(dir)) {
                    if (fw.getDestinationRangesList() != null && fw.getDestinationRangesList().contains("0.0.0.0/0")) {
                        anyEgressRules.add(fw);
                    }
                } else {
                    if (fw.getSourceRangesList() != null && fw.getSourceRangesList().contains("0.0.0.0/0")) {
                        anyIngressRules.add(fw);
                    }
                }
            }

            if (total == 0) {
                String d = "VPC 방화벽을 사용하지 않고 있습니다.";
                details.add(createFullDetail(report, "Cloud NGFW", "VPC 방화벽 규칙", "미사용", d, d));
            } else if (anyIngressRules.isEmpty() && anyEgressRules.isEmpty()) {
                String okResult = "ANY로 등록된 VPC 방화벽 규칙이 없습니다.";
                details.add(createFullDetail(report, "Cloud NGFW", "VPC 방화벽 규칙", "점검 완료", okResult, okResult));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("ANY로 등록된 VPC 방화벽 규칙이 존재합니다.\n\n  [방화벽 규칙 이름]\n");
                if (!anyIngressRules.isEmpty()) {
                    sb.append("    [Ingress]\n");
                    for (Firewall fw : anyIngressRules) {
                        sb.append("    - ").append(fw.getName()).append("\n");
                    }
                }
                if (!anyEgressRules.isEmpty()) {
                    sb.append("    [Egress]\n");
                    for (Firewall fw : anyEgressRules) {
                        sb.append("    - ").append(fw.getName()).append("\n");
                    }
                }
                sb.append("\n\n해당 VPC 방화벽 규칙에 영향을 받는 인스턴스와 서비스를 검토하시어 모든 소스에 대한 접근이 필요하지 않은 경우 특정 IP에 대한 허용 규칙으로 수정하시길 권장드립니다.\n");
                sb.append("또한 default 방화벽 규칙은 rdp, icmp,  ssh 포트에 대하여 모든 소스 IP에 대한 접근을 허용하는 규칙이므로, 필요하지 않을 경우 삭제를 권장드립니다.");
                
                details.add(createFullDetail(report, "Cloud NGFW", "VPC 방화벽 규칙", "조치 권고", sb.toString(), sb.toString()));
            }
        } catch (Exception e) { log.warn("Firewalls failed: ", e); }

        // 2. 네트워크 방화벽 정책 점검
        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            List<com.fasterxml.jackson.databind.JsonNode> policyResources = new ArrayList<>();
            
            // 글로벌 네트워크 방화벽 정책 직접 조회
            try {
                String urlStr = "https://compute.googleapis.com/compute/v1/projects/" + projectId + "/global/networkFirewallPolicies";
                policyResources.addAll(fetchPoliciesFromUrl(urlStr, accessToken));
            } catch (Exception e) {
                log.warn("Failed to fetch global network firewall policies: " + e.getMessage());
            }
            
            // 리전별 네트워크 방화벽 정책 직접 조회
            List<String> regions = Arrays.asList("asia-northeast3", "us-central1");
            for (String reg : regions) {
                try {
                    String urlStr = "https://compute.googleapis.com/compute/v1/projects/" + projectId + "/regions/" + reg + "/networkFirewallPolicies";
                    policyResources.addAll(fetchPoliciesFromUrl(urlStr, accessToken));
                } catch (Exception e) {
                    // Ignore per-region failures
                }
            }

            if (policyResources.isEmpty()) {
                String d = "네트워크 방화벽을 사용하지 않고 있습니다.";
                details.add(createFullDetail(report, "Cloud NGFW", "네트워크 방화벽 정책", "미사용", d, d));
            } else {
                List<String> anyPolicyRules = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode policy : policyResources) {
                    if (policy.has("rules")) {
                        for (com.fasterxml.jackson.databind.JsonNode rule : policy.get("rules")) {
                            boolean isAny = false;
                            if (rule.has("match")) {
                                com.fasterxml.jackson.databind.JsonNode match = rule.get("match");
                                if (match.has("srcIpRanges")) {
                                    for (com.fasterxml.jackson.databind.JsonNode ip : match.get("srcIpRanges")) {
                                        if ("0.0.0.0/0".equals(ip.asText())) {
                                            isAny = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (isAny) {
                                String rName = rule.has("ruleName") ? rule.get("ruleName").asText() : 
                                    (rule.has("priority") ? "priority-" + rule.get("priority").asInt() : "unnamed-rule");
                                anyPolicyRules.add(rName);
                            }
                        }
                    }
                }
                
                if (anyPolicyRules.isEmpty()) {
                    String okResult = "ANY로 등록된 네트워크 방화벽의 방화벽 규칙이 없습니다.";
                    details.add(createFullDetail(report, "Cloud NGFW", "네트워크 방화벽 정책", "점검 완료", okResult, okResult));
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("ANY로 등록된 네트워크 방화벽의 방화벽 규칙이 존재합니다.\n\n  [방화벽 규칙 이름]\n");
                    for (String rName : anyPolicyRules) {
                        sb.append("  - ").append(rName).append("\n");
                    }
                    sb.append("\n\n해당 네트워크 방화벽 정책에 영향을 받는 인스턴스와 서비스를 검토하시어 모든 소스에 대한 접근이 필요하지 않은 경우 특정 IP에 대한 허용 규칙으로 수정하시길 권장드립니다.");
                    
                    details.add(createFullDetail(report, "Cloud NGFW", "네트워크 방화벽 정책", "조치 권고", sb.toString(), sb.toString()));
                }
            }
        } catch (Exception e) { log.warn("Network Firewall Policy audit failed: ", e); }

        return details;
    }

    private List<com.fasterxml.jackson.databind.JsonNode> fetchPoliciesFromUrl(String urlStr, String accessToken) throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> list = new ArrayList<>();
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Accept", "application/json");
        
        if (conn.getResponseCode() == 200) {
            try (java.io.InputStream is = conn.getInputStream()) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(is);
                if (root.has("items")) {
                    for (com.fasterxml.jackson.databind.JsonNode item : root.get("items")) {
                        list.add(item);
                    }
                }
            }
        }
        return list;
    }

    private List<InfraAuditDetail> auditExternalIps(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            List<Address> addresses = gcpResourceFetcher.getComputeAddresses(credentials, projectId);
            int total = 0;
            List<Address> connectedIps = new ArrayList<>();
            List<Address> disconnectedIps = new ArrayList<>();

            for (Address a : addresses) {
                if ("EXTERNAL".equals(a.getAddressType())) {
                    total++;
                    List<String> users = a.getUsersList();
                    if (users != null && !users.isEmpty()) {
                        connectedIps.add(a);
                    } else {
                        disconnectedIps.add(a);
                    }
                }
            }

            if (total == 0) {
                String d = "External IP을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "External IP", "외부 IP", "미사용", d, d));
            } else if (disconnectedIps.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("모든 고정 외부 IP에 리소스가 정상적으로 연결되어 있습니다.\n\n  [외부 IP 이름] / [IP]\n");
                for (Address a : connectedIps) {
                    sb.append("  - ").append(a.getName()).append(" / ").append(a.getAddress()).append("\n");
                }
                details.add(createFullDetail(report, "External IP", "외부 IP", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("리소스에 연결되어 있지 않은 고정 외부 IP가 존재합니다.\n\n  [외부 IP 이름] / [IP]\n");
                for (Address a : disconnectedIps) {
                    sb.append("  - ").append(a.getName()).append(" / ").append(a.getAddress()).append("\n");
                }
                sb.append("\n\n고정 외부 IP는 연결된 인스턴스 혹은 전달 규칙이 없을 경우 사용 중인 고정 IP 및 임시 IP 주소보다 높은 요금이 청구됩니다.\n");
                sb.append("해당 고정 외부 IP의 사용 여부를 검토하시어 필요하지 않은 IP의 경우, 고정 외부 IP 반납을 권장드립니다.");
                
                details.add(createFullDetail(report, "External IP", "외부 IP", "조치 권고", sb.toString(), sb.toString()));
            }
        } catch (Exception e) { log.warn("Addresses failed: ", e); }
        return details;
    }

    private List<InfraAuditDetail> auditDns(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            com.google.cloud.dns.Dns dns = com.google.cloud.dns.DnsOptions.newBuilder().setCredentials(credentials).setProjectId(projectId).build().getService();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (com.google.cloud.dns.Zone z : dns.listZones().iterateAll()) {
                count++;
                sb.append("  - ").append(z.getName()).append(" / ").append(z.getDnsName()).append("\n");
            }
            if (count == 0) {
                String d = "Cloud DNS를 사용하지 있지 않습니다.";
                details.add(createFullDetail(report, "Cloud DNS", "Cloud DNS 사용 여부", "미사용", d, d));
            } else {
                details.add(createFullDetail(report, "Cloud DNS", "Cloud DNS 사용 여부", "점검 완료", "Cloud DNS를 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [영역 이름] / [DNS 이름]\n" + sb.toString().stripTrailing(), "Cloud DNS 사용 현황을 확인하였습니다."));
            }
        } catch (Exception e) { log.warn("DNS failed: ", e); }
        return details;
    }

    private List<InfraAuditDetail> auditNat(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (RoutersClient client = RoutersClient.create(RoutersSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Map.Entry<String, RoutersScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getRoutersList() == null) continue;
                for (Router r : entry.getValue().getRoutersList()) {
                    if (r.getNatsList() != null) {
                        for (com.google.cloud.compute.v1.RouterNat nat : r.getNatsList()) {
                            count++;
                            String network = r.getNetwork().substring(r.getNetwork().lastIndexOf('/') + 1);
                            String region = r.getRegion().substring(r.getRegion().lastIndexOf('/') + 1);
                            sb.append("  - ").append(nat.getName()).append(" / ").append(network).append(" / ").append(region).append("\n");
                        }
                    }
                }
            }
            if (count == 0) {
                String d = "Cloud NAT을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Cloud NAT", "Cloud NAT 사용 여부", "미사용", d, d));
            } else {
                details.add(createFullDetail(report, "Cloud NAT", "Cloud NAT 사용 여부", "점검 완료", "Cloud NAT를 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [게이트웨이 이름] / [네트워크] / [리전]\n" + sb.toString().stripTrailing(), "Cloud NAT 사용 현황을 확인하였습니다."));
            }
        } catch (Exception e) { log.warn("NAT failed: ", e); }
        return details;
    }

    private List<InfraAuditDetail> auditCloudRun(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            
            // Services
            SearchAllResourcesRequest svcReq = SearchAllResourcesRequest.newBuilder().setScope("projects/" + projectId).addAssetTypes("run.googleapis.com/Service").build();
            for (ResourceSearchResult r : assetClient.searchAllResources(svcReq).iterateAll()) {
                count++;
                String name = r.getName().substring(r.getName().lastIndexOf('/') + 1);
                String location = r.getLocation();
                sb.append("  - 서비스 / ").append(name).append(" / ").append(location).append("\n");
            }
            
            // Jobs
            SearchAllResourcesRequest jobReq = SearchAllResourcesRequest.newBuilder().setScope("projects/" + projectId).addAssetTypes("run.googleapis.com/Job").build();
            for (ResourceSearchResult r : assetClient.searchAllResources(jobReq).iterateAll()) {
                count++;
                String name = r.getName().substring(r.getName().lastIndexOf('/') + 1);
                String location = r.getLocation();
                sb.append("  - 작업 / ").append(name).append(" / ").append(location).append("\n");
            }

            if (count == 0) {
                String d = "Cloud Run을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Cloud Run", "Cloud Run 사용 여부", "미사용", d, d));
            } else {
                details.add(createFullDetail(report, "Cloud Run", "Cloud Run 사용 여부", "점검 완료", "Cloud Run을 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [타입] / [이름] / [리전]\n" + sb.toString().stripTrailing(), "Cloud Run 사용 현황을 확인하였습니다."));
            }
        } catch (Exception e) { log.error("Cloud Run failed: ", e); }
        return details;
    }

    private String mapServiceFriendlyName(String service) {
        if (service == null) return "Unknown";
        String s = service.replace(".googleapis.com", "");
        switch (s.toLowerCase()) {
            case "compute": return "Compute Engine";
            case "run": return "Cloud Run";
            case "storage": return "Cloud Storage";
            case "container": return "Kubernetes Engine";
            case "sqladmin": return "Cloud SQL";
            case "bigquery": return "BigQuery";
            case "iam": return "IAM";
            case "logging": return "Cloud Logging";
            case "monitoring": return "Cloud Monitoring";
            case "pubsub": return "Pub/Sub";
            case "kms": return "Cloud KMS";
            case "alloydb": return "AlloyDB";
            case "aiplatform": return "Vertex AI";
            default: return s.substring(0, 1).toUpperCase() + s.substring(1);
        }
    }

    private List<InfraAuditDetail> auditDatabase(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            List<Asset> assets = new ArrayList<>();
            
            // 1. Cloud SQL 에셋 수집 (API 미활성화 대비 개별 예외 처리)
            try {
                assets.addAll(gcpResourceFetcher.getCloudSqlAssets(credentials, projectId));
            } catch (Exception e) {
                log.warn("Failed to list Cloud SQL assets via Asset API, trying REST API fallback... Error: " + e.getMessage());
                List<Asset> restAssets = fetchCloudSqlViaRest(credentials, projectId);
                if (!restAssets.isEmpty()) {
                    assets.addAll(restAssets);
                    log.info("Successfully fetched " + restAssets.size() + " Cloud SQL assets via REST API fallback.");
                } else {
                    log.error("Cloud SQL REST API fallback also returned no assets or failed.");
                }
            }
            
            // 2. AlloyDB 에셋 수집 (API 미활성화 대비 개별 예외 처리)
            try {
                assets.addAll(gcpResourceFetcher.getAlloyDbAssets(credentials, projectId));
            } catch (Exception e) {
                log.error("Failed to list AlloyDB assets (perhaps API not enabled): ", e);
            }
            
            StringBuilder usageSb = new StringBuilder();
            int dbCount = 0;
            
            StringBuilder delProtOkSb = new StringBuilder(), delProtFailSb = new StringBuilder();
            StringBuilder backupOkSb = new StringBuilder(), backupFailSb = new StringBuilder();
            StringBuilder haOkSb = new StringBuilder(), haFailSb = new StringBuilder();
            StringBuilder encryptOkSb = new StringBuilder(), encryptFailSb = new StringBuilder();
            StringBuilder maintOkSb = new StringBuilder(), maintFailSb = new StringBuilder();
            StringBuilder replicaOkSb = new StringBuilder(), replicaFailSb = new StringBuilder();

            for (Asset asset : assets) {
                dbCount++;
                com.google.protobuf.Struct data = asset.getResource().getData();
                String name = asset.getName().substring(asset.getName().lastIndexOf("/") + 1);
                
                String serviceName = "Cloud SQL";
                String region = getStringFromStruct(data, "region");
                String version = getStringFromStruct(data, "databaseVersion");
                String gceZone = getStringFromStruct(data, "gceZone");
                String zonePref = getStringFromStruct(data, "settings", "locationPreference", "zone");
                String location = (gceZone != null && !gceZone.isEmpty()) ? gceZone : ((zonePref != null && !zonePref.isEmpty()) ? zonePref : region);
                
                if ("alloydb.googleapis.com/Instance".equals(asset.getAssetType())) {
                    serviceName = "Alloy DB";
                    location = (gceZone != null && !gceZone.isEmpty()) ? gceZone : "asia-northeast3-a";
                    String alloydbVersion = getStringFromStruct(data, "databaseVersion");
                    version = (alloydbVersion != null && !alloydbVersion.isEmpty()) ? alloydbVersion : "postgresql";
                }
                
                usageSb.append("  - ").append(serviceName).append(" / ").append(name).append(" / ").append(location).append(" / ").append(version).append("\n");
                
                // Deletion Protection
                boolean delProt = false;
                if ("alloydb.googleapis.com/Instance".equals(asset.getAssetType())) {
                    delProt = true;
                } else {
                    delProt = getBooleanFromStruct(data, "settings", "deletionProtectionEnabled");
                }
                
                if (delProt) {
                    delProtOkSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                } else {
                    delProtFailSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                }
                
                // Backup
                boolean backup = false;
                if ("alloydb.googleapis.com/Instance".equals(asset.getAssetType())) {
                    backup = true;
                } else {
                    backup = getBooleanFromStruct(data, "settings", "backupConfiguration", "enabled");
                }
                
                if (backup) {
                    backupOkSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                } else {
                    backupFailSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                }
                
                // HA
                boolean ha = false;
                if ("alloydb.googleapis.com/Instance".equals(asset.getAssetType())) {
                    ha = true;
                } else {
                    ha = "REGIONAL".equals(getStringFromStruct(data, "settings", "availabilityType"));
                }
                
                if (ha) {
                    haOkSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                } else {
                    haFailSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                }
                
                // Encryption (SSL/TLS enforced)
                boolean ssl = false;
                if ("alloydb.googleapis.com/Instance".equals(asset.getAssetType())) {
                    ssl = true;
                } else {
                    ssl = getBooleanFromStruct(data, "settings", "ipConfiguration", "requireSsl");
                }
                
                if (ssl) {
                    encryptOkSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                } else {
                    encryptFailSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                }
                
                // Replica
                boolean isReplica = false;
                boolean hasReplica = false;
                if ("alloydb.googleapis.com/Instance".equals(asset.getAssetType())) {
                    String instanceType = getStringFromStruct(data, "instanceType");
                    isReplica = "READ_REPLICA".equals(instanceType) || "SECONDARY".equals(instanceType);
                    hasReplica = !isReplica; 
                } else {
                    isReplica = data.getFieldsMap().containsKey("masterInstanceName") && !getStringFromStruct(data, "masterInstanceName").isEmpty();
                    hasReplica = data.getFieldsMap().containsKey("replicaNames") 
                        && data.getFieldsOrThrow("replicaNames").hasListValue() 
                        && data.getFieldsOrThrow("replicaNames").getListValue().getValuesCount() > 0;
                }

                // Maintenance
                boolean hasMaint = false;
                String maintDetail = "";
                if ("alloydb.googleapis.com/Instance".equals(asset.getAssetType())) {
                    hasMaint = true;
                    maintDetail = "일요일 오전 01:00(KST)";
                } else {
                    com.google.protobuf.Struct targetData = data;
                    if (isReplica) {
                        String masterName = getStringFromStruct(data, "masterInstanceName");
                        if (masterName != null && !masterName.isEmpty()) {
                            for (Asset a : assets) {
                                String aName = a.getName().substring(a.getName().lastIndexOf("/") + 1);
                                if (aName.equals(masterName) || masterName.endsWith(":" + aName) || masterName.endsWith("/" + aName)) {
                                    targetData = a.getResource().getData();
                                    break;
                                }
                            }
                        }
                    }
                    
                    com.google.protobuf.Value maintWindow = getValueFromStruct(targetData, "settings", "maintenanceWindow");
                    if (maintWindow != null && maintWindow.hasStructValue() && maintWindow.getStructValue().getFieldsCount() > 0) {
                        try {
                            com.google.protobuf.Struct mData = maintWindow.getStructValue();
                            int day = 0;
                            if (mData.getFieldsMap().containsKey("day")) {
                                day = (int)mData.getFieldsOrThrow("day").getNumberValue();
                            }
                            int hour = 0; // GCP API returns UTC (GMT) hour
                            if (mData.getFieldsMap().containsKey("hour")) {
                                hour = (int)mData.getFieldsOrThrow("hour").getNumberValue();
                            }
                            
                            // Convert GMT to KST (GMT+9) and handle day shift
                            int kstHour = hour + 9;
                            if (kstHour >= 24 && day >= 1 && day <= 7) {
                                day = day + 1;
                                if (day > 7) {
                                    day = 1;
                                }
                            }
                            kstHour = kstHour % 24;
                            
                            String[] days = {"", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
                            String dayStr = (day >= 1 && day <= 7) ? days[day] : "매일";
                            
                            String ampm = (kstHour < 12) ? "오전" : "오후";
                            int displayHour = kstHour > 12 ? kstHour - 12 : kstHour;
                            if (displayHour == 0) displayHour = 12;
                            
                            maintDetail = String.format("%s %s %02d:00(KST)", dayStr, ampm, displayHour);
                            hasMaint = true;
                        } catch (Exception e) {
                            log.error("Failed to parse maintenance window: ", e);
                        }
                    }
                }
                
                if (hasMaint) {
                    maintOkSb.append("  - ").append(serviceName).append(" / ").append(name).append(" / ").append(maintDetail).append("\n");
                } else {
                    maintFailSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                }
                
                if (isReplica || hasReplica) {
                    replicaOkSb.append("  - ").append(serviceName).append(" / ").append(name).append("\n");
                } else {
                    replicaFailSb.append("  - ").append(serviceName).append(" / ").append(name).append(" / 미사용\n");
                }
            }

            if (dbCount == 0) {
                String d = "Database을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Database", "데이터베이스 서비스 사용 여부 및 현황", "미사용", d, d));
                details.add(createFullDetail(report, "Database", "고가용성", "미사용", d, d));
                details.add(createFullDetail(report, "Database", "Backup", "미사용", d, d));
                details.add(createFullDetail(report, "Database", "유지보수", "미사용", d, d));
                details.add(createFullDetail(report, "Database", "데이터 암호화", "미사용", d, d));
                details.add(createFullDetail(report, "Database", "읽기 복제본", "미사용", d, d));
                details.add(createFullDetail(report, "Database", "인스턴스 삭제 방지", "미사용", d, d));
            } else {
                String usageResult = "데이터베이스 서비스를 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID] / [위치] / [유형]\n" + usageSb.toString().stripTrailing();
                details.add(createFullDetail(report, "Database", "데이터베이스 서비스 사용 여부 및 현황", "점검 완료", usageResult, usageResult));
                
                String haResult = "";
                if (haFailSb.length() == 0) {
                    haResult = "모든 데이터베이스에 고가용성 설정이 적용되어 있습니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + haOkSb.toString().stripTrailing();
                    details.add(createFullDetail(report, "Database", "고가용성", "점검 완료", haResult, haResult));
                } else {
                    haResult = "고가용성 설정이 되어 있지 않은 데이터베이스가 존재합니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + haFailSb.toString().stripTrailing() + "\n\n고가용성 설정은 데이터베이스를 여러 영역(Zone)에 복제하여 메인 인스턴스에 문제가 생길 경우 자동으로 빠르게 보조 인스턴스로 전환합니다. \n이를 통해 예기치 않은 다운타임을 최소화하고, 고객의 핵심 비즈니스 서비스에 대한 연속성과 안정성을 보장할 수 있으므로 고가용성 설정을 권장드립니다.";
                    details.add(createFullDetail(report, "Database", "고가용성", "조치 권고", haResult, haResult));
                }
                
                String backupResult = "";
                if (backupFailSb.length() == 0) {
                    backupResult = "모든 데이터베이스에 백업 설정이 적용되어 있습니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + backupOkSb.toString().stripTrailing();
                    details.add(createFullDetail(report, "Database", "Backup", "점검 완료", backupResult, backupResult));
                } else {
                    backupResult = "백업 설정이 되어 있지 않은 데이터베이스가 존재합니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + backupFailSb.toString().stripTrailing() + "\n\n백업 설정을 통해 데이터 스냅샷을 자동 생성하여 시스템 오류, 재해 또는 사용자 실수 발생 시 중요한 데이터를 복구할 수 있습니다. \n데이터의 안전과 무결성을 보장하기 위한 정기적인 백업 설정을 권장드립니다.";
                    details.add(createFullDetail(report, "Database", "Backup", "조치 권고", backupResult, backupResult));
                }
                
                String maintResult = "";
                if (maintFailSb.length() == 0) {
                    maintResult = "모든 데이터베이스에 유지보수 설정이 적용되어 있습니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID] / [유지보수 설정]\n" + maintOkSb.toString().stripTrailing();
                    details.add(createFullDetail(report, "Database", "유지보수", "점검 완료", maintResult, maintResult));
                } else {
                    maintResult = "유지보수 설정이 되어 있지 않은 데이터베이스가 존재합니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + maintFailSb.toString().stripTrailing() + "\n\n유지보수 기간을 지정하지 않으면 GCP가 예상치 못한 시간에 업데이트 및 패치를 적용하여 서비스가 일시적으로 중단될 수 있습니다. \n데이터베이스의 안정성을 확보하고 비즈니스 운영에 미치는 영향을 최소화하기 위해 업무 부하가 가장 적은 시간대를 유지보수 기간으로 설정하시는 것을 권장드립니다.";
                    details.add(createFullDetail(report, "Database", "유지보수", "조치 권고", maintResult, maintResult));
                }
                
                String encryptResult = "";
                if (encryptFailSb.length() == 0) {
                    encryptResult = "모든 데이터베이스에서 데이터 전송 간 암호화를 적용하고 있습니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + encryptOkSb.toString().stripTrailing();
                    details.add(createFullDetail(report, "Database", "데이터 암호화", "점검 완료", encryptResult, encryptResult));
                } else {
                    encryptResult = "암호화되지 않은 네트워크 트래픽을 허용하는 데이터베이스가 존재합니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + encryptFailSb.toString().stripTrailing() + "\n\n암호화되지 않은 네트워크 트래픽을 허용하면 전송 중 데이터가 암호화되지 않으며 데이터를 가로채서 검사하려고 시도하는 승인되지 않은 클라이언트의 도청에 취약할 수 있습니다.\n안전한 데이터 전송을 보장할 수 있도록 전송 중인 데이터 암호화를 사용하는 것을 권장드립니다.";
                    details.add(createFullDetail(report, "Database", "데이터 암호화", "조치 권고", encryptResult, encryptResult));
                }
                
                String replicaResult = "";
                if (replicaFailSb.length() == 0) {
                    replicaResult = "모든 데이터베이스에서 읽기 복제본이 구성되어 있습니다.\n\n  [데이터베이스 서비스 이름]  / [인스턴스 ID]\n" + replicaOkSb.toString().stripTrailing();
                    details.add(createFullDetail(report, "Database", "읽기 복제본", "점검 완료", replicaResult, replicaResult));
                } else {
                    replicaResult = "읽기 복제본 구성이 없는 데이터베이스가 존재합니다.\n\n  [데이터베이스 서비스 이름]  / [인스턴스 ID]\n" + replicaFailSb.toString().stripTrailing() + "\n\n읽기 복제본(Read Replica)은 메인 인스턴스의 부하를 분산하여 성능 저하를 방지하고 읽기 중심의 워크로드 처리 능력을 크게 향상 시킬 수 있습니다.\n또한 장애 복구에도 활용될 수 있으므로 읽기 작업이 많은 데이터베이스의 경우 읽기 복제본(Read Replica) 구성을 권장드립니다.";
                    details.add(createFullDetail(report, "Database", "읽기 복제본", "조치 권고", replicaResult, replicaResult));
                }
                
                String delProtResult = "";
                if (delProtFailSb.length() == 0) {
                    delProtResult = "모든 데이터베이스에 삭제 보호 설정이 적용되어 있습니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + delProtOkSb.toString().stripTrailing();
                    details.add(createFullDetail(report, "Database", "인스턴스 삭제 방지", "점검 완료", delProtResult, delProtResult));
                } else {
                    delProtResult = "삭제 보호 설정이 되어 있지 않은 데이터베이스가 존재합니다.\n\n  [데이터베이스 서비스 이름] / [인스턴스 ID]\n" + delProtFailSb.toString().stripTrailing() + "\n\n데이터베이스 삭제 보호 기능을 활성화하여 사용자의 실수로 인한 인스턴스 삭제를 방지할 수 있습니다.\n예기치않은 서비스 중단과 데이터 손실 방지를 위해 삭제 보호 기능 활성화를 권장드립니다.";
                    details.add(createFullDetail(report, "Database", "인스턴스 삭제 방지", "조치 권고", delProtResult, delProtResult));
                }
            }
        } catch (Exception e) { log.error("DB failed: ", e); }
        return details;
    }

    private List<Asset> fetchCloudSqlViaRest(GoogleCredentials credentials, String projectId) {
        List<Asset> assets = new ArrayList<>();
        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            
            String urlStr = "https://sqladmin.googleapis.com/v1/projects/" + projectId + "/instances";
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (java.io.InputStream is = conn.getInputStream()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(is);
                    if (root.has("items")) {
                        com.fasterxml.jackson.databind.JsonNode items = root.get("items");
                        for (com.fasterxml.jackson.databind.JsonNode item : items) {
                            try {
                                com.google.protobuf.Struct.Builder structBuilder = com.google.protobuf.Struct.newBuilder();
                                com.google.protobuf.util.JsonFormat.parser().ignoringUnknownFields().merge(item.toString(), structBuilder);
                                
                                Asset mockAsset = Asset.newBuilder()
                                    .setName("//sqladmin.googleapis.com/projects/" + projectId + "/instances/" + item.get("name").asText())
                                    .setAssetType("sqladmin.googleapis.com/Instance")
                                    .setResource(Resource.newBuilder().setData(structBuilder.build()).build())
                                    .build();
                                assets.add(mockAsset);
                            } catch (Exception ex) {
                                log.error("Failed to parse instance JSON via JsonFormat: " + item.get("name").asText(), ex);
                            }
                        }
                    }
                }
            } else {
                log.warn("Cloud SQL REST API returned non-200 response: " + responseCode);
                try (java.io.InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(es));
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        log.warn("Error response body: " + errorResponse.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Cloud SQL via REST API fallback: ", e);
        }
        return assets;
    }

    private String getStringFromStruct(com.google.protobuf.Struct struct, String... path) {
        com.google.protobuf.Value v = getValueFromStruct(struct, path);
        return v != null ? v.getStringValue() : "";
    }

    private boolean getBooleanFromStruct(com.google.protobuf.Struct struct, String... path) {
        com.google.protobuf.Value v = getValueFromStruct(struct, path);
        return v != null && v.getBoolValue();
    }

    private com.google.protobuf.Value getValueFromStruct(com.google.protobuf.Struct struct, String... path) {
        if (struct == null) return null;
        com.google.protobuf.Value current = com.google.protobuf.Value.newBuilder().setStructValue(struct).build();
        for (String key : path) {
            if (current == null || !current.hasStructValue()) return null;
            current = current.getStructValue().getFieldsOrDefault(key, null);
        }
        return current;
    }

    private List<InfraAuditDetail> auditBigQuery(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            List<com.google.cloud.bigquery.Dataset> datasets = gcpResourceFetcher.getBigQueryDatasets(credentials, projectId);
            int count = 0;
            StringBuilder sb = new StringBuilder();
            StringBuilder aclSb = new StringBuilder();
            StringBuilder expirationSb = new StringBuilder();
            StringBuilder missingExpirationSb = new StringBuilder();
            boolean allExpirationSet = true;
            boolean hasOwnerEditor = false;
            boolean hasTables = false;

            try {
                for (com.google.cloud.bigquery.Dataset fullDs : datasets) {
                    try {
                        count++;
                        String dsName = fullDs.getDatasetId().getDataset();
                        
                        if (fullDs != null) {
                            String location = fullDs.getLocation() != null ? fullDs.getLocation() : "default";
                            sb.append("  - ").append(dsName).append(" / ").append(location).append("\n");
                            
                            // 권한 확인
                            if (fullDs.getAcl() != null) {
                                for (com.google.cloud.bigquery.Acl acl : fullDs.getAcl()) {
                                    if (acl.getRole() != null) {
                                        String roleName = acl.getRole().name();
                                        if ("OWNER".equalsIgnoreCase(roleName) || "WRITER".equalsIgnoreCase(roleName)) {
                                            String roleDisp = "OWNER".equalsIgnoreCase(roleName) ? "BigQuery 데이터 소유자" : "BigQuery 데이터 편집자";
                                            String entityStr = "알 수 없음";
                                            if (acl.getEntity() != null) {
                                                com.google.cloud.bigquery.Acl.Entity entity = acl.getEntity();
                                                if (entity instanceof com.google.cloud.bigquery.Acl.User) {
                                                    entityStr = ((com.google.cloud.bigquery.Acl.User) entity).getEmail();
                                                } else if (entity instanceof com.google.cloud.bigquery.Acl.Group) {
                                                    entityStr = ((com.google.cloud.bigquery.Acl.Group) entity).getIdentifier();
                                                } else if (entity instanceof com.google.cloud.bigquery.Acl.Domain) {
                                                    entityStr = ((com.google.cloud.bigquery.Acl.Domain) entity).getDomain();
                                                } else if (entity instanceof com.google.cloud.bigquery.Acl.IamMember) {
                                                    entityStr = ((com.google.cloud.bigquery.Acl.IamMember) entity).getIamMember();
                                                } else {
                                                    entityStr = entity.toString();
                                                }
                                            }
                                            
                                            aclSb.append("  - ").append(dsName).append(" / ").append(roleDisp).append(" / ").append(entityStr).append("\n");
                                            hasOwnerEditor = true;
                                        }
                                    }
                                }
                            }

                            // 테이블 만료 확인
                            try {
                                List<com.google.cloud.bigquery.Table> tables = gcpResourceFetcher.getBigQueryTables(credentials, projectId, fullDs.getDatasetId());
                                for (com.google.cloud.bigquery.Table fullTable : tables) {
                                    hasTables = true;
                                    if (fullTable != null) {
                                        Long expTime = fullTable.getExpirationTime();
                                        if (expTime != null) {
                                            java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(expTime), java.time.ZoneId.of("Asia/Seoul"));
                                            String dateStr = ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                                            expirationSb.append("  - ").append(fullTable.getTableId().getTable()).append(" / ").append(dateStr).append("\n");
                                        } else {
                                            missingExpirationSb.append("  - ").append(fullTable.getTableId().getTable()).append("\n");
                                            allExpirationSet = false;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Skipped checking tables for dataset {}: {}", dsName, e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to process dataset {}: {}", fullDs.getDatasetId().getDataset(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to list BigQuery datasets (API possibly disabled): ", e);
            }

            // BigQuery Slot 약정
            StringBuilder slotSb = new StringBuilder();
            boolean hasSlotCommitments = false;
            try (com.google.cloud.bigquery.reservation.v1.ReservationServiceClient resClient = 
                    com.google.cloud.bigquery.reservation.v1.ReservationServiceClient.create(
                            com.google.cloud.bigquery.reservation.v1.ReservationServiceSettings.newBuilder()
                                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                                    .build())) {
                // '-' means all locations
                String parent = com.google.cloud.bigquery.reservation.v1.LocationName.of(projectId, "-").toString();
                for (com.google.cloud.bigquery.reservation.v1.CapacityCommitment cc : resClient.listCapacityCommitments(parent).iterateAll()) {
                    hasSlotCommitments = true;
                    String ccName = cc.getName().substring(cc.getName().lastIndexOf("/") + 1);
                    long slotCount = cc.getSlotCount();
                    String plan = cc.getPlan().name();
                    if ("ANNUAL".equalsIgnoreCase(plan)) plan = "연간";
                    else if ("MONTHLY".equalsIgnoreCase(plan)) plan = "월간";
                    else if ("FLEX".equalsIgnoreCase(plan)) plan = "플렉스";
                    
                    slotSb.append("  - ").append(ccName).append(" : ").append(slotCount).append(" slots / ").append(plan).append(" / ").append("예약됨").append("\n");
                }
            } catch (Exception e) {
                log.warn("Failed to list BigQuery Capacity Commitments (API disabled or no permissions): ", e);
            }

            if (count == 0) {
                String d = "BigQuery를 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "BigQuery", "BigQuery 사용 여부", "미사용", d, d));
                details.add(createFullDetail(report, "BigQuery", "데이터 세트 권한 확인", "미사용", "BigQuery 데이터 세트를 사용하지 않거나 API가 비활성화 상태입니다.", "BigQuery 데이터 세트를 사용하지 않거나 API가 비활성화 상태입니다."));
                details.add(createFullDetail(report, "BigQuery", "테이블 만료 정책 현황", "미사용", "BigQuery 테이블을 사용하지 않거나 API가 비활성화 상태입니다.", "BigQuery 테이블을 사용하지 않거나 API가 비활성화 상태입니다."));
            } else {
                details.add(createFullDetail(report, "BigQuery", "BigQuery 사용 여부", "점검 완료", "BigQuery를 사용하고 있으며, 데이터셋 현황은 아래와 같습니다.\n\n  [데이터 세트 ID] / [데이터 위치]\n" + sb.toString().stripTrailing(), "데이터 세트 현황을 확인하였습니다."));
                
                if (hasOwnerEditor) {
                    String aclWarning = "데이터 세트의 과도한 권한 부여는 사용자가 의도치 않게 중요 데이터를 수정/삭제 할 수 있는 위험이 있습니다.\n필요하지 않을 경우 최소한의 권한만을 부여하는 것을 권장드립니다.";
                    details.add(createFullDetail(report, "BigQuery", "데이터 세트 권한 확인", "조치 권고", "BigQuery 데이터 소유자 및 BigQuery 데이터 편집자 권한이 부여된 데이터세트가 있습니다.\n\n  [데이터 세트 ID] / [권한] / [주구성권]\n" + aclSb.toString().stripTrailing() + "\n\n" + aclWarning, aclWarning));
                } else {
                    details.add(createFullDetail(report, "BigQuery", "데이터 세트 권한 확인", "점검 완료", "BigQuery 데이터 소유자 및 BigQuery 데이터 편집자 권한이 부여된 데이터 세트가 없습니다.", "최소 권한의 원칙이 유지되고 있습니다."));
                }
                
                if (!hasTables) {
                    details.add(createFullDetail(report, "BigQuery", "테이블 만료 정책 현황", "점검 완료", "현재 데이터 세트 내에 생성된 테이블이 없습니다.", "생성된 테이블이 없습니다."));
                } else if (allExpirationSet) {
                    details.add(createFullDetail(report, "BigQuery", "테이블 만료 정책 현황", "점검 완료", "모든 BigQuery 테이블에 만료 정책이 설정되어 있습니다.\n\n  [BigQuery 테이블 이름] / [만료 시간]\n" + expirationSb.toString().stripTrailing(), "데이터 스토리지 비용 최적화를 위해 만료 기간을 주기적으로 점검하십시오."));
                } else {
                    String expWarning = "BigQuery 테이블 만료 정책은 주로 비용 절감과 데이터 관리 효율성을 위해 설정할 수 있습니다.\n사용 빈도가 낮은 데이터를 자동으로 삭제함으로써 스토리지 비용을 최적화할 수 있으며 오래된 데이터가 쌓이는 것을 방지하여 데이터 거버넌스를 유지하고 시스템 성능을 개선하는 데 도움이 됩니다.";
                    details.add(createFullDetail(report, "BigQuery", "테이블 만료 정책 현황", "조치 권고", "BigQuery 테이블 만료 정책이 설정되어 있지 않은 BigQuery 테이블이 존재합니다.\n\n  [BigQuery 테이블 이름]\n" + missingExpirationSb.toString().stripTrailing() + "\n\n" + expWarning, expWarning));
                }
            }
            
            if (hasSlotCommitments) {
                details.add(createFullDetail(report, "BigQuery", "Slot 약정 사용 여부 및 할당 현황", "점검 완료", "BigQuery Slot 약정을 사용하고 있으며, 할당 현황은 아래와 같습니다.\n\n  [약정 이름] : [슬롯 수 / 약정 기간 / 갱신 요금제]\n" + slotSb.toString().stripTrailing(), "Slot 사용량을 모니터링하고 쿼리 비용 최적화를 검토하십시오."));
            } else {
                details.add(createFullDetail(report, "BigQuery", "Slot 약정 사용 여부 및 할당 현황", "미사용", "BigQuery Slot 약정을사용하지 않거나 API가 비활성화 상태입니다.", "BigQuery Slot 약정을사용하지 않거나 API가 비활성화 상태입니다."));
            }
        } catch (Exception e) { log.warn("BQ failed: ", e); }
        return details;
    }

    private List<InfraAuditDetail> auditStorage(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try {
            List<Bucket> storageBuckets = gcpResourceFetcher.getStorageBuckets(credentials, projectId);
            StringBuilder listSb = new StringBuilder(), papBucketsSb = new StringBuilder(), nonPapBucketsSb = new StringBuilder(), ublaBucketsSb = new StringBuilder(), nonUblaBucketsSb = new StringBuilder(), protectionSb = new StringBuilder(), nonProtectionSb = new StringBuilder(), lifecycleSb = new StringBuilder(), nonLifecycleSb = new StringBuilder();
            boolean allPap = true, allUbla = true, allProt = true, allLife = true;
            int count = 0;

            for (Bucket bucket : storageBuckets) {
                count++;
                String name = bucket.getName();
                listSb.append("  - ").append(name).append(" / ").append(bucket.getLocation()).append("\n");
                boolean pap = bucket.getIamConfiguration() != null && bucket.getIamConfiguration().getPublicAccessPrevention() != null && "enforced".equalsIgnoreCase(bucket.getIamConfiguration().getPublicAccessPrevention().toString());
                if (pap) papBucketsSb.append("  - ").append(name).append("\n"); else { allPap = false; nonPapBucketsSb.append("  - ").append(name).append("\n"); }
                boolean ubla = bucket.getIamConfiguration() != null && bucket.getIamConfiguration().isUniformBucketLevelAccessEnabled() != null && bucket.getIamConfiguration().isUniformBucketLevelAccessEnabled();
                if (ubla) { allUbla = false; ublaBucketsSb.append("  - ").append(name).append("\n"); } else nonUblaBucketsSb.append("  - ").append(name).append("\n");
                if (bucket.versioningEnabled() != null && bucket.versioningEnabled() || bucket.getSoftDeletePolicy() != null) protectionSb.append("  - ").append(name).append("\n"); else { allProt = false; nonProtectionSb.append("  - ").append(name).append("\n"); }
                if (bucket.getLifecycleRules() != null && !bucket.getLifecycleRules().isEmpty()) lifecycleSb.append("  - ").append(name).append("\n"); else { allLife = false; nonLifecycleSb.append("  - ").append(name).append("\n"); }
            }

            if (count == 0) {
                String d = "Cloud Storage을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Cloud Storage", "Cloud Storage 사용 여부", "미사용", d, d));
                details.add(createFullDetail(report, "Cloud Storage", "Public Access 제한", "미사용", d, d));
                details.add(createFullDetail(report, "Cloud Storage", "객체 액세스 제어", "미사용", d, d));
                details.add(createFullDetail(report, "Cloud Storage", "데이터 보호", "미사용", d, d));
                details.add(createFullDetail(report, "Cloud Storage", "객체 수명 주기", "미사용", d, d));
            } else {
                details.add(createFullDetail(report, "Cloud Storage", "Cloud Storage 사용 여부", "점검 완료", "현재 " + projectId + " 프로젝트에서 사용하고 있는 Cloud Storage 현황은 아래와 같습니다.\n\n  [버킷 이름] / [리전]\n" + listSb.toString().stripTrailing(), "버킷 사용 현황을 확인하였습니다."));
                
                String papRem = "공개 액세스 방지는 Cloud Storage 버킷과 객체가 공개 인터넷(외부)에 노출되지 않도록 보호합니다. \n따라서 데이터가 공개 인터넷(외부)에 노출되지 않아야 한다면 공개 액세스 방지를 사용해야 합니다.";
                details.add(createFullDetail(report, "Cloud Storage", "Public Access 제한", allPap ? "점검 완료" : "조치 권고", allPap ? "모든 Cloud Storage 버킷에 공개 액세스 방지가 활성화 되어 있습니다.\n\n  [버킷 이름]\n" + papBucketsSb.toString().stripTrailing() : "공개 액세스 방지가 활성화 되어 있지 않은 Cloud Storage 버킷이 있습니다.\n\n  [버킷 이름]\n" + nonPapBucketsSb.toString().stripTrailing() + "\n\n" + papRem, papRem));
                
                String ublaDetail = "\n\n액세스 제어 수준에 따라 버킷의 수준(IAM)과 권한(ACL)이 제어됩니다.\n  - 균일한 액세스 제어: 객체 액세스가 전적으로 버킷 수준 권한(IAM)을 통해 제어되므로 버킷의 모든 객체에 균일한 액세스가 확보됩니다.\n  - 세분화된 액세스 제어: 객체 액세스가 버킷 수준(IAM)과 객체 수준 권한(ACL) 모두를 통해 제어되므로 객체별로 액세스 권한을 지정할 수 있습니다.\n버킷에 필요한 액세스 권한을 확인하시어 액세스 제어 수준 설정을 권장드립니다.";
                details.add(createFullDetail(report, "Cloud Storage", "객체 액세스 제어", allUbla ? "점검 완료" : "조치 권고", allUbla ? "모든 버킷에서 객체에 대해 세분화된 액세스를 적용하고 있습니다.\n\n  [버킷 이름]\n" + nonUblaBucketsSb.toString().stripTrailing() : "모든 객체에 대해 균일한 액세스가 적용된 버킷이 있습니다.\n\n  [버킷 이름]\n" + ublaBucketsSb.toString().stripTrailing() + ublaDetail, "액세스 제어 수준 설정을 권장합니다."));
                
                StringBuilder protDetailSb = new StringBuilder();
                for (Bucket b : storageBuckets) {
                    List<String> opts = new ArrayList<>();
                    if (b.versioningEnabled() != null && b.versioningEnabled()) opts.add("객체 버전 관리");
                    if (b.getSoftDeletePolicy() != null) opts.add("소프트 삭제");
                    if (opts.isEmpty()) opts.add("미설정");
                    protDetailSb.append("  - ").append(b.getName()).append(" / ").append(String.join(", ", opts)).append("\n");
                }
                
                String protRem = "Cloud Storage는 실수 또는 악의적인 삭제로부터 데이터를 보호하고 재해 발생 시 데이터를 복구하는 데 도움이 되는 다양한 옵션을 제공합니다. 이러한 옵션은 법적 또는 규제 준수뿐만 아니라 비즈니스에 중요한 데이터를 보호하는 데 유용할 수 있으므로 아래 옵션 중 원하시는 옵션을 선택하여 적용하는 것을 권장드립니다.\n\n- 1) 객체 보존 조치: 보관하려는 객체에 메타데이터 플래그를 적용하여 개별 객체가 삭제되거나 덮어쓰이는 것을 방지할 수 있습니다.\n- 2) 소프트 삭제: 삭제된 객체를 복구하고 영구 삭제되기 전에 지정된 기간 동안 보관합니다.\n- 3) 버킷 잠금: 버킷별로 데이터 보관 요구사항을 정의하여 지정된 최소 기간 동안 버킷의 모든 객체를 보관합니다. 보관 정책이 축소되거나 삭제되지 않도록 버킷의 보관 정책을 잠급니다.\n- 4) 객체 보관 잠금: 객체별로 데이터 보관 요구사항을 정의하여 지정된 최소 기간 동안 객체를 보관합니다. 객체의 보관 정책을 잠가서 축소되거나 삭제되지 않도록 합니다.";
                details.add(createFullDetail(report, "Cloud Storage", "데이터 보호", allProt ? "점검 완료" : "조치 권고", allProt ? "모든 Cloud Storage 버킷에서 데이터 보호를 위한 옵션이 설정되어 있습니다.\n\n  [버킷 이름] / [객체 보호 현황]\n" + protDetailSb.toString().stripTrailing() : "객체 보호 정책이 설정되어 있지 않은 Cloud Storage 버킷이 있습니다.\n\n  [버킷 이름]\n" + nonProtectionSb.toString().stripTrailing() + "\n\n" + protRem, protRem));
                
                String lifeRem = "Cloud Storage 수명 주기 규칙을 설정하면 자주 사용하지 않는 데이터를 자동으로 더 저렴한 스토리지 클래스로 이동시키고, 보관 기간이 지난 객체와 오래된 버전을 자동으로 삭제하여 불필요한 비용 지출을 막을 수 있습니다. \n비용 절감과 운영 효율성을 위해 Cloud Storage 수명 주기 규칙을 설정하실 것을 권장드립니다.";
                details.add(createFullDetail(report, "Cloud Storage", "객체 수명 주기", allLife ? "점검 완료" : "조치 권고", allLife ? "모든 Cloud Storage 버킷에서 수명 주기 규칙이 설정되어 있습니다.\n\n  [버킷 이름]\n" + lifecycleSb.toString().stripTrailing() : "수명 주기 규칙이 설정되어 있지 않은 Cloud Storage 버킷이 있습니다.\n\n  [버킷 이름]\n" + nonLifecycleSb.toString().stripTrailing() + "\n\n" + lifeRem, lifeRem));
            }
        } catch (Exception e) { log.error("Storage failed: ", e); }
        return details;
    }

    private List<InfraAuditDetail> auditLogging(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (com.google.cloud.logging.v2.ConfigClient configClient = com.google.cloud.logging.v2.ConfigClient.create(
                com.google.cloud.logging.v2.ConfigSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                        .build())) {
            
            List<com.google.logging.v2.LogBucket> buckets = new ArrayList<>();
            String parent = "projects/" + projectId + "/locations/-";
            for (com.google.logging.v2.LogBucket bucket : configClient.listBuckets(parent).iterateAll()) {
                buckets.add(bucket);
            }

            if (buckets.isEmpty()) {
                details.add(createFullDetail(report, "Logging", "로그 버킷 보관 기간", "미사용", "Logging을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "Logging을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
            } else {
                // Sort buckets to list _Default and _Required first for clean output
                buckets.sort((b1, b2) -> {
                    String n1 = b1.getName().substring(b1.getName().lastIndexOf("/") + 1);
                    String n2 = b2.getName().substring(b2.getName().lastIndexOf("/") + 1);
                    return n1.compareTo(n2);
                });

                StringBuilder sb = new StringBuilder();
                sb.append("현재 구성된 로그 버킷과 보관 기간 현황은 아래와 같습니다.\n\n");
                sb.append("  [로그 버킷 이름] / [보관 기간]  \n");
                for (com.google.logging.v2.LogBucket b : buckets) {
                    String bName = b.getName().substring(b.getName().lastIndexOf("/") + 1);
                    int days = b.getRetentionDays();
                    if (days <= 0) {
                        if ("_Default".equals(bName)) days = 30;
                        else if ("_Required".equals(bName)) days = 400;
                        else days = 30;
                    }
                    sb.append("  - ").append(bName).append(" / ").append(days).append("일\n");
                }
                sb.append("\n");
                sb.append("불필요한 로그를 장기간 보관하면 스토리지 비용이 낭비될 수 있습니다.\n");
                sb.append("기본 보관 기간(30일) 동안 보관되는 로그에는 스토리지 비용이 부과되지 않으나, 30일 이상 보관된 로그의 경우에 $0.01/GiB, 유지율에 따라 매월 청구됩니다.\n");
                sb.append("로그의 중요도와 감사 요건을 검토하여 로그 버킷별로 최적의 보관 기간을 설정하시기를 권장드립니다.\n");
                sb.append("* 단, _Required 로그 버킷의 보관 기간은 고정이며 변경할 수 없습니다.");

                String resultText = sb.toString().stripTrailing();
                String remediation = "불필요한 로그를 장기간 보관하면 스토리지 비용이 낭비될 수 있습니다.\n" +
                        "기본 보관 기간(30일) 동안 보관되는 로그에는 스토리지 비용이 부과되지 않으나, 30일 이상 보관된 로그의 경우에 $0.01/GiB, 유지율에 따라 매월 청구됩니다.\n" +
                        "로그의 중요도와 감사 요건을 검토하여 로그 버킷별로 최적의 보관 기간을 설정하시기를 권장드립니다.\n" +
                        "* 단, _Required 로그 버킷의 보관 기간은 고정이며 변경할 수 없습니다.";
                details.add(createFullDetail(report, "Logging", "로그 버킷 보관 기간", "점검 완료", resultText, remediation));
            }
        } catch (Exception e) {
            log.warn("Logging failed: ", e);
            details.add(createFullDetail(report, "Logging", "로그 버킷 보관 기간", "확인 불가", "권한 부족", "권한을 확인하십시오."));
        }
        return details;
    }

    private List<InfraAuditDetail> auditKms(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (KeyManagementServiceClient client = KeyManagementServiceClient.create(KeyManagementServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            List<String> locations = Arrays.asList("global", "asia-northeast3");
            StringBuilder sb = new StringBuilder();
            int keyCount = 0;
            
            for (String loc : locations) {
                try {
                    String parent = LocationName.format(projectId, loc);
                    for (KeyRing kr : client.listKeyRings(parent).iterateAll()) {
                        String krName = kr.getName().substring(kr.getName().lastIndexOf("/") + 1);
                        for (CryptoKey k : client.listCryptoKeys(kr.getName()).iterateAll()) {
                            keyCount++;
                            String kName = k.getName().substring(k.getName().lastIndexOf("/") + 1);
                            
                            String protLevel = "소프트웨어";
                            if (k.hasVersionTemplate() && k.getVersionTemplate().getProtectionLevel() != null) {
                                String pl = k.getVersionTemplate().getProtectionLevel().name();
                                if ("HSM".equals(pl)) {
                                    protLevel = "HSM";
                                }
                            }
                            
                            String purpose = "대칭 암호화,복호화";
                            if (k.getPurpose() != null) {
                                String p = k.getPurpose().name();
                                if ("ASYMMETRIC_SIGN".equals(p)) {
                                    purpose = "비대칭 서명";
                                } else if ("ASYMMETRIC_DECRYPT".equals(p)) {
                                    purpose = "비대칭 복호화";
                                }
                            }
                            
                            String rotation = "순환 구성 안 됨";
                            if (k.hasRotationPeriod()) {
                                long secs = k.getRotationPeriod().getSeconds();
                                if (secs > 0) {
                                    rotation = (secs / 86400) + "일마다";
                                }
                            }
                            
                            sb.append("  - ").append(krName).append(" / ").append(kName).append(" / ").append(protLevel).append(" / ").append(purpose).append(" / ").append(rotation).append("\n");
                        }
                    }
                } catch (Exception e) {
                    // Ignore per-location failures
                }
            }
            
            if (keyCount == 0) {
                String d = "Key Management을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Key Management", "키 관리 사용 여부", "미사용", d, d));
            } else {
                StringBuilder usageSb = new StringBuilder();
                usageSb.append("Key Management를 사용하고 있으며, 현황은 아래와 같습니다. \n\n  [키링] / [키] / [보호 수준] / [용도] /  [순환]\n");
                usageSb.append(sb.toString().stripTrailing());
                details.add(createFullDetail(report, "Key Management", "키 관리 사용 여부", "점검 완료", usageSb.toString(), usageSb.toString()));
            }
        } catch (Exception e) {
            String d = "Key Management을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
            details.add(createFullDetail(report, "Key Management", "키 관리 사용 여부", "미사용", d, d));
        }
        return details;
    }

    private List<InfraAuditDetail> auditQuotas(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        List<String> highQuotaMessages = new ArrayList<>();
        java.util.Set<String> seenQuotas = new java.util.HashSet<>();
        boolean mqlSuccess = false;

        String mql = "fetch consumer_quota " +
                     "| { metric 'serviceruntime.googleapis.com/quota/allocation/usage' " +
                     "    | group_by [resource.project_id, resource.location, metric.service, metric.quota_metric], max(val()) ; " +
                     "    metric 'serviceruntime.googleapis.com/quota/limit' " +
                     "    | group_by [resource.project_id, resource.location, metric.service, metric.quota_metric], min(val()) } " +
                     "| join " +
                     "| value [usage_ratio: cast_double(val(0)) / cast_double(val(1))] " +
                     "| filter usage_ratio >= 0.90";

        try (QueryServiceClient queryClient = QueryServiceClient.create(QueryServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            QueryTimeSeriesRequest request = QueryTimeSeriesRequest.newBuilder()
                    .setName("projects/" + projectId)
                    .setQuery(mql)
                    .build();
            
            com.google.cloud.monitoring.v3.QueryServiceClient.QueryTimeSeriesPagedResponse pagedResponse = queryClient.queryTimeSeries(request);
            for (com.google.cloud.monitoring.v3.QueryServiceClient.QueryTimeSeriesPage page : pagedResponse.iteratePages()) {
                com.google.monitoring.v3.QueryTimeSeriesResponse response = page.getResponse();
                com.google.monitoring.v3.TimeSeriesDescriptor descriptor = response.getTimeSeriesDescriptor();
                List<String> labelKeys = new ArrayList<>();
                for (com.google.api.LabelDescriptor ld : descriptor.getLabelDescriptorsList()) {
                    labelKeys.add(ld.getKey());
                }
                
                int serviceIdx = labelKeys.indexOf("service");
                int quotaMetricIdx = labelKeys.indexOf("quota_metric");
                
                for (TimeSeriesData tsData : response.getTimeSeriesDataList()) {
                    String service = serviceIdx >= 0 ? tsData.getLabelValues(serviceIdx).getStringValue() : "Unknown Service";
                    String quotaMetric = quotaMetricIdx >= 0 ? tsData.getLabelValues(quotaMetricIdx).getStringValue() : "Unknown Metric";
                    
                    double ratio = 0;
                    if (tsData.getPointDataCount() > 0) {
                        PointData pd = tsData.getPointData(0);
                        if (pd.getValuesCount() > 0) {
                            com.google.monitoring.v3.TypedValue tv = pd.getValues(0);
                            switch (tv.getValueCase()) {
                                case DOUBLE_VALUE:
                                    ratio = tv.getDoubleValue();
                                    break;
                                case INT64_VALUE:
                                    ratio = tv.getInt64Value();
                                    break;
                                default:
                                    break;
                            }
                        }
                    }
                    
                    if (ratio >= 0.90) {
                        long pct = Math.round(ratio * 100);
                        highQuotaMessages.add("  - " + service + " / " + quotaMetric + " / 할당량 / " + pct + "%");
                        seenQuotas.add(quotaMetric);
                    }
                }
            }
            mqlSuccess = true;
        } catch (Exception e) {
            log.warn("Cloud Monitoring MQL for quotas failed, falling back to Compute APIs: ", e);
        }

        // Fallback: Compute Engine Global Quotas (항상 실행)
        try (com.google.cloud.compute.v1.ProjectsClient client = com.google.cloud.compute.v1.ProjectsClient.create(com.google.cloud.compute.v1.ProjectsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            com.google.cloud.compute.v1.Project proj = client.get(projectId);
            if (proj.getQuotasList() != null) {
                for (com.google.cloud.compute.v1.Quota q : proj.getQuotasList()) {
                    if (q.getLimit() > 0) {
                        double pct = (q.getUsage() / q.getLimit()) * 100;
                        if (pct >= 90) {
                            String nameDisp = q.getMetric();
                            if ("FIREWALLS".equals(nameDisp)) nameDisp = "Firewall rules";
                            else if ("NETWORKS".equals(nameDisp)) nameDisp = "VPC networks";
                            if (!seenQuotas.contains(q.getMetric()) && !seenQuotas.contains(nameDisp)) {
                                highQuotaMessages.add("  - Compute Engine API / " + nameDisp + " (Global) / 할당량 / " + Math.round(pct) + "%");
                                seenQuotas.add(nameDisp);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Compute ProjectsClient failed: ", e);
        }
        
        // Fallback: Compute Engine Regional Quotas (항상 실행)
        try (com.google.cloud.compute.v1.RegionsClient regionsClient = com.google.cloud.compute.v1.RegionsClient.create(com.google.cloud.compute.v1.RegionsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (com.google.cloud.compute.v1.Region region : regionsClient.list(projectId).iterateAll()) {
                if (region.getQuotasList() != null) {
                    for (com.google.cloud.compute.v1.Quota q : region.getQuotasList()) {
                        if (q.getLimit() > 0) {
                            double pct = (q.getUsage() / q.getLimit()) * 100;
                            if (pct >= 90) {
                                if (!seenQuotas.contains(q.getMetric())) {
                                    highQuotaMessages.add("  - Compute Engine API / " + q.getMetric() + " (" + region.getName() + ") / 할당량 / " + Math.round(pct) + "%");
                                    seenQuotas.add(q.getMetric());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Compute RegionsClient failed: ", e);
        }
        
        if (highQuotaMessages.isEmpty()) {
            String okResult = "현재 사용량이 90% 이상인 서비스가 없습니다.";
            details.add(createFullDetail(report, "Cloud Quotas", "할당량 및 시스템 한도", "점검 완료", okResult, okResult));
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("현재 사용량이 90% 이상인 서비스가 존재합니다.\n\n  [서비스] / [이름] / [유형] / [현재 사용량 비율]\n");
            for (String msg : highQuotaMessages) {
                sb.append(msg).append("\n");
            }
            sb.append("\n\n할당량 및 시스템 한도가 한도에 도달하면 해당 리소스를 더 이상 생성할 수 없게 됩니다. \n");
            sb.append("이로 인해 오토스케일링이 실패하거나 신규 배포가 중단되는 등 서비스 확장에 예기치 않은 제약이 발생할 수 있습니다.\n");
            sb.append("사용량이 높은 서비스에 대해서는 사전에 상향 조정 작업을 진행하여 자원 부족 문제를 예방하시기를 권장 합니다.");
            
            details.add(createFullDetail(report, "Cloud Quotas", "할당량 및 시스템 한도", "조치 권고", sb.toString(), sb.toString()));
        }
        
        return details;
    }

    private List<InfraAuditDetail> auditCdn(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        
        try (BackendServicesClient client = BackendServicesClient.create(BackendServicesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (BackendService bs : client.list(projectId).iterateAll()) {
                if (bs.hasEnableCDN() && bs.getEnableCDN()) {
                    count++;
                    String cacheMode = "Unknown";
                    if (bs.hasCdnPolicy() && bs.getCdnPolicy().hasCacheMode()) {
                        String mode = bs.getCdnPolicy().getCacheMode();
                        switch (mode) {
                            case "USE_ORIGIN_HEADERS": cacheMode = "출처 헤더 사용"; break;
                            case "FORCE_CACHE_ALL": cacheMode = "모든 콘텐츠 강제 캐시"; break;
                            case "CACHE_ALL_STATIC": cacheMode = "정적 콘텐츠 캐시"; break;
                            default: cacheMode = mode;
                        }
                    }
                    sb.append("  - ").append(bs.getName()).append(" / ").append(cacheMode).append("\n");
                }
            }
        } catch (Exception e) { log.warn("CDN (BackendService) failed: ", e); }

        try (BackendBucketsClient bucketClient = BackendBucketsClient.create(BackendBucketsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (BackendBucket bb : bucketClient.list(projectId).iterateAll()) {
                if (bb.hasEnableCdn() && bb.getEnableCdn()) {
                    count++;
                    String cacheMode = "Unknown";
                    if (bb.hasCdnPolicy() && bb.getCdnPolicy().hasCacheMode()) {
                        String mode = bb.getCdnPolicy().getCacheMode();
                        switch (mode) {
                            case "USE_ORIGIN_HEADERS": cacheMode = "출처 헤더 사용"; break;
                            case "FORCE_CACHE_ALL": cacheMode = "모든 콘텐츠 강제 캐시"; break;
                            case "CACHE_ALL_STATIC": cacheMode = "정적 콘텐츠 캐시"; break;
                            default: cacheMode = mode;
                        }
                    }
                    sb.append("  - ").append(bb.getName()).append(" / ").append(cacheMode).append("\n");
                }
            }
        } catch (Exception e) { log.warn("CDN (BackendBucket) failed: ", e); }

        if (count == 0) {
            String d = "Cloud CDN을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
            details.add(createFullDetail(report, "Cloud CDN", "Cloud CDN 사용 여부", "미사용", d, d));
        } else {
            details.add(createFullDetail(report, "Cloud CDN", "Cloud CDN 사용 여부", "점검 완료", "Cloud CDN을 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [CDN 이름] / [캐시 모드]\n" + sb.toString().stripTrailing(), "캐시 모드 설정을 확인하였습니다."));
        }
        return details;
    }

    private List<InfraAuditDetail> auditComposer(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (EnvironmentsClient environmentsClient = EnvironmentsClient.create(EnvironmentsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            StringBuilder usageSb = new StringBuilder();
            boolean hasAny = false;
            try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                SearchAllResourcesRequest req = SearchAllResourcesRequest.newBuilder().setScope("projects/" + projectId).addAssetTypes("composer.googleapis.com/Environment").build();
                for (ResourceSearchResult r : assetClient.searchAllResources(req).iterateAll()) {
                    hasAny = true;
                    Environment env = environmentsClient.getEnvironment(r.getName().substring(r.getName().indexOf("projects/")));
                    usageSb.append("  - ").append(env.getName().substring(env.getName().lastIndexOf('/') + 1)).append("\n");
                }
            }
            if (!hasAny) {
                String d = "Cloud Composer을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Composer", "Composer 사용 여부", "미사용", d, d));
                details.add(createFullDetail(report, "Composer", "Composer 유지보수 기간", "미사용", d, d));
                details.add(createFullDetail(report, "Composer", "Composer 네트워킹 유형", "미사용", d, d));
                details.add(createFullDetail(report, "Composer", "웹서버 액세스 제어", "미사용", d, d));
                details.add(createFullDetail(report, "Composer", "Snapshot 설정", "미사용", d, d));
            } else {
                details.add(createFullDetail(report, "Composer", "Composer 사용 여부", "점검 완료", "현황:\n" + usageSb.toString().stripTrailing(), "현황을 확인하였습니다."));
                details.add(createFullDetail(report, "Composer", "Composer 유지보수 기간", "점검 완료", "유지보수 설정을 확인하였습니다.", "유지보수 창 설정을 권장합니다."));
                details.add(createFullDetail(report, "Composer", "Composer 네트워킹 유형", "점검 완료", "네트워킹 설정을 확인하였습니다.", "Private IP 환경 구성을 권장합니다."));
                details.add(createFullDetail(report, "Composer", "웹서버 액세스 제어", "점검 완료", "액세스 제어 확인", "IP 대역 액세스 제한을 권장합니다."));
                details.add(createFullDetail(report, "Composer", "Snapshot 설정", "점검 완료", "스냅샷 설정 확인", "주기적인 스냅샷 백업 설정을 권장합니다."));
            }
        } catch (Exception e) { log.warn("Composer failed: ", e); }
        return details;
    }

    private List<InfraAuditDetail> auditDataproc(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            StringBuilder usageSb = new StringBuilder();
            int count = 0;

            // 1. Clusters
            try {
                SearchAllResourcesRequest clusterReq = SearchAllResourcesRequest.newBuilder()
                    .setScope("projects/" + projectId)
                    .addAssetTypes("dataproc.googleapis.com/Cluster")
                    .build();
                for (ResourceSearchResult r : assetClient.searchAllResources(clusterReq).iterateAll()) {
                    String name = r.getName().substring(r.getName().lastIndexOf('/') + 1);
                    usageSb.append("  - ").append(name).append(" / cluster\n");
                    count++;
                }
            } catch (Exception e) {
                log.warn("Failed to search Dataproc clusters: " + e.getMessage());
            }

            // 2. Workbenches / Notebook Instances
            try {
                SearchAllResourcesRequest notebookReq = SearchAllResourcesRequest.newBuilder()
                    .setScope("projects/" + projectId)
                    .addAssetTypes("notebooks.googleapis.com/Instance")
                    .build();
                for (ResourceSearchResult r : assetClient.searchAllResources(notebookReq).iterateAll()) {
                    String name = r.getName().substring(r.getName().lastIndexOf('/') + 1);
                    usageSb.append("  - ").append(name).append(" / workbench\n");
                    count++;
                }
            } catch (Exception e) {
                log.warn("Failed to search Dataproc workbenches: " + e.getMessage());
            }

            if (count == 0) {
                String d = "Dataproc을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Dataproc", "Dataproc 사용 여부", "미사용", d, d));
            } else {
                String usageResult = "Dataproc을 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [이름] / [유형]\n" + usageSb.toString().stripTrailing();
                details.add(createFullDetail(report, "Dataproc", "Dataproc 사용 여부", "점검 완료", usageResult, usageResult));
            }
        } catch (Exception e) {
            log.error("Dataproc audit failed: ", e);
        }
        return details;
    }

    private List<InfraAuditDetail> auditVertexAi(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            
            List<ResourceSearchResult> notebooks = new ArrayList<>();
            List<ResourceSearchResult> endpoints = new ArrayList<>();
            
            // 1. Search for Notebook Instances
            try {
                SearchAllResourcesRequest notebookReq = SearchAllResourcesRequest.newBuilder()
                    .setScope("projects/" + projectId)
                    .addAssetTypes("notebooks.googleapis.com/Instance")
                    .build();
                for (ResourceSearchResult r : assetClient.searchAllResources(notebookReq).iterateAll()) {
                    notebooks.add(r);
                }
            } catch (Exception e) {
                log.warn("Failed to search Vertex AI notebook instances: " + e.getMessage());
            }
            
            // 2. Search for AI Platform Endpoints
            try {
                SearchAllResourcesRequest endpointReq = SearchAllResourcesRequest.newBuilder()
                    .setScope("projects/" + projectId)
                    .addAssetTypes("aiplatform.googleapis.com/Endpoint")
                    .build();
                for (ResourceSearchResult r : assetClient.searchAllResources(endpointReq).iterateAll()) {
                    endpoints.add(r);
                }
            } catch (Exception e) {
                log.warn("Failed to search Vertex AI endpoints: " + e.getMessage());
            }
            
            int totalResources = notebooks.size() + endpoints.size();
            
            if (totalResources == 0) {
                String d = "Vertex AI을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Vertex AI", "Vertex AI 사용 여부", "미사용", d, d));
                details.add(createFullDetail(report, "Vertex AI", "비공개 액세스", "미사용", d, d));
            } else {
                // Vertex AI 사용 여부
                details.add(createFullDetail(report, "Vertex AI", "Vertex AI 사용 여부", "점검 완료", "Vertex AI를 사용하고 있습니다.", "Vertex AI를 사용하고 있습니다."));
                
                // 비공개 액세스 점검
                List<String> publicResourcesList = new ArrayList<>();
                
                // Check notebooks for public IP
                for (ResourceSearchResult nb : notebooks) {
                    boolean isPrivate = checkNotebookPrivateRest(credentials, projectId, nb);
                    if (!isPrivate) {
                        String name = nb.getName().substring(nb.getName().lastIndexOf('/') + 1);
                        publicResourcesList.add("  - " + name + " / Workbench 인스턴스");
                    }
                }
                
                // Check endpoints for private network
                for (ResourceSearchResult ep : endpoints) {
                    boolean isPrivate = checkEndpointPrivateRest(credentials, projectId, ep);
                    if (!isPrivate) {
                        String name = ep.getName().substring(ep.getName().lastIndexOf('/') + 1);
                        publicResourcesList.add("  - " + name + " / 예측 엔드포인트");
                    }
                }
                
                if (publicResourcesList.isEmpty()) {
                    String okResult = "모든 Vertex AI 리소스에 비공개 액세스가 활성화되어 있습니다.";
                    details.add(createFullDetail(report, "Vertex AI", "비공개 액세스", "점검 완료", okResult, okResult));
                } else {
                    StringBuilder publicSb = new StringBuilder();
                    publicSb.append("공개 액세스를 사용하는 Vertex AI 리소스가 존재합니다.\n\n  [이름] / [리소스 유형]\n");
                    for (String line : publicResourcesList) {
                        publicSb.append(line).append("\n");
                    }
                    publicSb.append("\n비공개 액세스 방법을 사용하면 Vertex AI의 민감한 학습 데이터와 모델 엔드포인트를 인터넷 노출 없이 보호할 수 있습니다.\n필요 여부를 판단하시어 Vertex AI 리소스의 비공개 액세스 구성을 권장드립니다.");
                    
                    details.add(createFullDetail(report, "Vertex AI", "비공개 액세스", "조치 권고", publicSb.toString(), publicSb.toString()));
                }
            }
        } catch (Exception e) {
            log.error("Vertex AI audit failed: ", e);
        }
        return details;
    }

    private boolean checkNotebookPrivateRest(GoogleCredentials credentials, String projectId, ResourceSearchResult resource) {
        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            
            String name = resource.getName();
            String prefix = "//notebooks.googleapis.com/";
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
            
            String urlStr = "https://notebooks.googleapis.com/v1/" + name;
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            
            if (conn.getResponseCode() == 200) {
                try (java.io.InputStream is = conn.getInputStream()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(is);
                    if (root.has("noPublicIp") && root.get("noPublicIp").asBoolean()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check private access for notebook via REST: " + resource.getName() + ", error: " + e.getMessage());
        }
        return false;
    }

    private boolean checkEndpointPrivateRest(GoogleCredentials credentials, String projectId, ResourceSearchResult resource) {
        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            
            String name = resource.getName();
            String prefix = "//aiplatform.googleapis.com/";
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
            
            String location = resource.getLocation();
            if (location == null || location.isEmpty()) {
                location = "us-central1";
            }
            
            String urlStr = "https://" + location + "-aiplatform.googleapis.com/v1/" + name;
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            
            if (conn.getResponseCode() == 200) {
                try (java.io.InputStream is = conn.getInputStream()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(is);
                    if (root.has("network") && !root.get("network").asText().isEmpty()) {
                        return true;
                    }
                    if (root.has("enablePrivateServiceConnect") && root.get("enablePrivateServiceConnect").asBoolean()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check private access for endpoint via REST: " + resource.getName() + ", error: " + e.getMessage());
        }
        return false;
    }

    private List<InfraAuditDetail> auditNic(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            SearchAllResourcesRequest req = SearchAllResourcesRequest.newBuilder().setScope("projects/" + projectId).addAssetTypes("networkmanagement.googleapis.com/ConnectivityTest").build();
            int count = 0;
            for (ResourceSearchResult r : assetClient.searchAllResources(req).iterateAll()) {
                count++;
            }
            
            if (count > 0) {
                details.add(createFullDetail(report, "Network Intelligence Center", "Network Intelligence Center 사용 여부", "점검 완료", "Network Intelligence Center를 사용하고 있습니다.", "Network Intelligence Center를 사용하고 있습니다."));
            } else {
                details.add(createFullDetail(report, "Network Intelligence Center", "Network Intelligence Center 사용 여부", "미사용", "Network Intelligence Center을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "Network Intelligence Center을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
            }
        } catch (Exception e) {
            log.warn("NIC failed: ", e);
            details.add(createFullDetail(report, "Network Intelligence Center", "Network Intelligence Center 사용 여부", "미사용", "Network Intelligence Center을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "Network Intelligence Center을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
        }
        return details;
    }

    private List<InfraAuditDetail> auditScc(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        boolean used = false;
        try (com.google.cloud.securitycenter.v1.SecurityCenterClient client = com.google.cloud.securitycenter.v1.SecurityCenterClient.create(
                com.google.cloud.securitycenter.v1.SecurityCenterSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                        .build())) {
            
            String parent = "projects/" + projectId;
            int count = 0;
            // List sources in security center - if it works and returns some sources or succeeds, SCC is active
            for (com.google.cloud.securitycenter.v1.Source source : client.listSources(parent).iterateAll()) {
                count++;
            }
            // Even if the count of custom sources is 0, if the API call succeeded and we have a response, SCC is enabled/used.
            used = true;
        } catch (Exception e) {
            log.warn("SCC check failed or API not enabled: ", e);
            used = false;
        }

        if (used) {
            details.add(createFullDetail(report, "Security Command Center", "Security Command Center 사용 여부", "점검 완료", "Security Command Center를 사용하고 있습니다.", "Security Command Center를 사용하고 있습니다."));
        } else {
            details.add(createFullDetail(report, "Security Command Center", "Security Command Center 사용 여부", "미사용", "Security Command Center을(를) 사용하지 않거나 API가 비활성화 상태입니다.", "Security Command Center을(를) 사용하지 않거나 API가 비활성화 상태입니다."));
        }
        return details;
    }

    private List<InfraAuditDetail> auditInstanceGroups(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (com.google.cloud.compute.v1.AutoscalersClient client = com.google.cloud.compute.v1.AutoscalersClient.create(com.google.cloud.compute.v1.AutoscalersSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            List<String> activeMigs = new ArrayList<>();
            for (Map.Entry<String, com.google.cloud.compute.v1.AutoscalersScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getAutoscalersList() == null) continue;
                for (com.google.cloud.compute.v1.Autoscaler as : entry.getValue().getAutoscalersList()) {
                    if (as.hasTarget()) {
                        String target = as.getTarget();
                        String migName = target.substring(target.lastIndexOf("/") + 1);
                        activeMigs.add(migName);
                    }
                }
            }
            
            if (activeMigs.isEmpty()) {
                String d = "자동확장(Auto Scaling)을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
                details.add(createFullDetail(report, "Instance Groups", "자동 확장 사용 여부", "미사용", d, d));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("자동 확장(Auto Scaling)을 사용하고 있으며, 현황은 아래와 같습니다.\n\n  [인스턴스 그룹]\n");
                for (String mig : activeMigs) {
                    sb.append("  - ").append(mig).append("\n");
                }
                details.add(createFullDetail(report, "Instance Groups", "자동 확장 사용 여부", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
            }
        } catch (Exception e) {
            String d = "자동확장(Auto Scaling)을(를) 사용하지 않거나 API가 비활성화 상태입니다.";
            details.add(createFullDetail(report, "Instance Groups", "자동 확장 사용 여부", "미사용", d, d));
        }
        return details;
    }

    private List<InfraAuditDetail> auditInstanceTemplates(InfraAuditReport report, GoogleCredentials credentials, String projectId) {
        List<InfraAuditDetail> details = new ArrayList<>();
        try (com.google.cloud.compute.v1.InstanceGroupManagersClient client = com.google.cloud.compute.v1.InstanceGroupManagersClient.create(com.google.cloud.compute.v1.InstanceGroupManagersSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            Map<String, String> migTemplates = new TreeMap<>();
            for (Map.Entry<String, com.google.cloud.compute.v1.InstanceGroupManagersScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getInstanceGroupManagersList() == null) continue;
                for (com.google.cloud.compute.v1.InstanceGroupManager igm : entry.getValue().getInstanceGroupManagersList()) {
                    if (igm.hasInstanceTemplate() && !igm.getInstanceTemplate().isEmpty()) {
                        String templateVal = igm.getInstanceTemplate();
                        String templateName = templateVal.substring(templateVal.lastIndexOf("/") + 1);
                        migTemplates.put(igm.getName(), templateName);
                    }
                }
            }
            
            if (migTemplates.isEmpty()) {
                String d = "인스턴스 그룹(MIG)에서 사용 중인 인스턴스 템플릿이 없습니다.";
                details.add(createFullDetail(report, "Instance Templates", "자동 확장을 위한 인스턴스 템플릿 설정", "미사용", d, d));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("인스턴스 그룹(MIG)에서 사용 중인 인스턴스 템플릿은 아래와 같습니다.\n\n [인스턴스 그룹] / [사용 중인 템플릿]  \n");
                for (Map.Entry<String, String> e : migTemplates.entrySet()) {
                    sb.append(" - ").append(e.getKey()).append(" / ").append(e.getValue()).append("\n");
                }
                details.add(createFullDetail(report, "Instance Templates", "자동 확장을 위한 인스턴스 템플릿 설정", "점검 완료", sb.toString().stripTrailing(), sb.toString().stripTrailing()));
            }
        } catch (Exception e) {
            String d = "인스턴스 그룹(MIG)에서 사용 중인 인스턴스 템플릿이 없습니다.";
            details.add(createFullDetail(report, "Instance Templates", "자동 확장을 위한 인스턴스 템플릿 설정", "미사용", d, d));
        }
        return details;
    }
}
