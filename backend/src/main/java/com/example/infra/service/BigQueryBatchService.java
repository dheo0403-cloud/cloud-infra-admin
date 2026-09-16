package com.example.infra.service;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.compute.v1.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Scanner;

@Slf4j
@Service
@RequiredArgsConstructor
public class BigQueryBatchService {

    private final InfraEnvironmentService environmentService;
    private final GcpResourceFetcher gcpResourceFetcher;
    private final GcpRecommenderService gcpRecommenderService;
    private final BigQuery bigQuery;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String targetProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    @Value("${app.slack.webhook-url}")
    private String slackWebhookUrl;

    // 매일 새벽 2시에 실행 (초 분 시 일 월 요일)
    @Scheduled(cron = "0 0 2 * * ?")
    public void runDailySnapshotBatch() {
        log.info("Starting Daily Snapshot Batch for GCP Resources");
        List<InfraEnvironment> environments = environmentService.getAllEnvironments();
        String snapshotDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // Recommender 데이터: 과거 월(8월 등) 데이터는 보존하고, 현재 진행 중인 당월(9월) 데이터만 매일 덮어쓰기(DELETE & INSERT)
        // (자산/Jira/예약 등 타 배치는 기존대로 날짜별 누적 적재 유지)
        deleteCurrentMonthDailyRecommenders(snapshotDate);

        for (InfraEnvironment env : environments) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;

            String decryptedSecret = environmentService.getDecryptedSecret(env.getId());
            if (decryptedSecret == null || decryptedSecret.isEmpty()) continue;

            try {
                GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decryptedSecret.getBytes()))
                        .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

                for (CloudProject project : env.getProjects()) {
                    String projectId = project.getProjectId();
                    log.info("Processing Daily Snapshot for project: {}", projectId);

                    String customerName = env.getCustomer() != null && env.getCustomer().getName() != null ? env.getCustomer().getName() : "Unknown";
                    
                    // 1. VM Instances
                    try {
                        List<Instance> instances = gcpResourceFetcher.getVmInstances(credentials, projectId);
                        Map<String, Integer> vmCounts = new HashMap<>();
                        
                        for (Instance inst : instances) {
                            // Type parsing
                            String type = inst.getMachineType();
                            if (type != null && type.contains("/machineTypes/")) {
                                type = type.substring(type.lastIndexOf("/machineTypes/") + 14);
                            } else if (type == null) {
                                type = "Unknown";
                            }
                            String typeKey = "VM_Type_" + type;
                            vmCounts.put(typeKey, vmCounts.getOrDefault(typeKey, 0) + 1);
                            
                            // State parsing
                            String status = inst.getStatus();
                            String stateKey = "RUNNING".equalsIgnoreCase(status) ? "VM_State_Running" : "VM_State_Deallocated";
                            vmCounts.put(stateKey, vmCounts.getOrDefault(stateKey, 0) + 1);
                            
                            // Region parsing
                            String zone = inst.getZone();
                            String region = "Global";
                            if (zone != null && zone.contains("/zones/")) {
                                String zoneName = zone.substring(zone.lastIndexOf("/zones/") + 7);
                                int lastDash = zoneName.lastIndexOf("-");
                                if (lastDash > 0) {
                                    region = zoneName.substring(0, lastDash);
                                } else {
                                    region = zoneName;
                                }
                            }
                            String regionKey = "VM_Region_" + region;
                            vmCounts.put(regionKey, vmCounts.getOrDefault(regionKey, 0) + 1);
                        }
                        
                        // Insert aggregated counts
                        for (Map.Entry<String, Integer> entry : vmCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }
                        // Insert total count for backward compatibility
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VM", instances.size());
                    } catch (Exception e) {
                        log.error("Batch failed for VM Instances in project {}", projectId, e);
                    }

                    // 2. Forwarding Rules (Load Balancer)
                    try {
                        List<ForwardingRule> rules = gcpResourceFetcher.getForwardingRules(credentials, projectId);

                        // Target Proxy → URL Map 매핑 구성 (Application LB 그루핑 기준)
                        Map<String, String> httpProxyToUrlMap  = gcpResourceFetcher.getTargetHttpProxyUrlMapMap(credentials, projectId);
                        Map<String, String> httpsProxyToUrlMap = gcpResourceFetcher.getTargetHttpsProxyUrlMapMap(credentials, projectId);

                        // 논리적 LB 단위로 그루핑: key = (urlMapName | backendServiceName | fallback:name) + "@" + region
                        Map<String, List<ForwardingRule>> groupedRules = new java.util.LinkedHashMap<>();

                        for (ForwardingRule rule : rules) {
                            String scheme = rule.getLoadBalancingScheme();
                            // Private Service Connect 및 scheme 없는 항목 제외
                            if (scheme == null || scheme.isEmpty()) continue;
                            // Cloud VPN Gateway 제외
                            String target = rule.getTarget();
                            if (target != null && target.contains("/targetVpnGateways/")) continue;
                            // Exclude Private Service Connect endpoints - these appear as forwarding rules but aren't user-facing LBs
                            if (target != null && target.contains("/serviceAttachments/")) continue;

                            // 리전 추출
                            String regionRaw = rule.getRegion();
                            String regionSuffix;
                            if (regionRaw == null || regionRaw.isEmpty()) {
                                regionSuffix = "@global";
                            } else {
                                regionSuffix = "@" + regionRaw.substring(regionRaw.lastIndexOf("/") + 1);
                            }

                            String canonicalKey;
                            if (target != null && target.contains("/targetHttpsProxies/")) {
                                String proxyName = target.substring(target.lastIndexOf("/") + 1);
                                String urlMapName = httpsProxyToUrlMap.get(proxyName);
                                canonicalKey = "urlmap:" + (urlMapName != null ? urlMapName : proxyName) + regionSuffix;
                            } else if (target != null && target.contains("/targetHttpProxies/")) {
                                String proxyName = target.substring(target.lastIndexOf("/") + 1);
                                String urlMapName = httpProxyToUrlMap.get(proxyName);
                                canonicalKey = "urlmap:" + (urlMapName != null ? urlMapName : proxyName) + regionSuffix;
                            } else if (target != null && (target.contains("/targetTcpProxies/") || target.contains("/targetSslProxies/") || target.contains("/targetGrpcProxies/"))) {
                                // L4 proxy-based LB: target proxy name = canonical key
                                String proxyName = target.substring(target.lastIndexOf("/") + 1);
                                canonicalKey = "proxy:" + proxyName + regionSuffix;
                            } else if (target != null && target.contains("/targetPools/")) {
                                // Legacy 외부 Network LB (Target Pool 기반): 하나의 target pool에 TCP/UDP 등 여러 forwarding rule이 연결될 수 있음
                                String poolName = target.substring(target.lastIndexOf("/") + 1);
                                canonicalKey = "pool:" + poolName + regionSuffix;
                            } else if (rule.hasBackendService() && !rule.getBackendService().isEmpty()) {
                                // Network Pass-through LB: backendService 직접 참조
                                String bs = rule.getBackendService();
                                canonicalKey = "bs:" + bs.substring(bs.lastIndexOf("/") + 1) + regionSuffix;
                            } else {
                                // 기타 fallback: forwarding rule 이름 사용
                                canonicalKey = "name:" + rule.getName() + regionSuffix;
                            }

                            groupedRules.computeIfAbsent(canonicalKey, k -> new ArrayList<>()).add(rule);
                        }

                        Map<String, Integer> lbCounts = new HashMap<>();
                        int totalLoadBalancers = groupedRules.size();
                        log.debug("LB grouping for {}: {} forwarding rules -> {} logical LBs, keys={}",
                                projectId, rules.size(), totalLoadBalancers, groupedRules.keySet());

                        for (Map.Entry<String, List<ForwardingRule>> entry : groupedRules.entrySet()) {
                            List<ForwardingRule> group = entry.getValue();

                            // Access Type (Internal/External)
                            boolean isInternal = false;
                            for (ForwardingRule rule : group) {
                                String s = rule.getLoadBalancingScheme();
                                if (s != null && s.toUpperCase().contains("INTERNAL")) {
                                    isInternal = true;
                                    break;
                                }
                            }
                            if (isInternal) {
                                lbCounts.put("LB_Access_Internal", lbCounts.getOrDefault("LB_Access_Internal", 0) + 1);
                            } else {
                                lbCounts.put("LB_Access_External", lbCounts.getOrDefault("LB_Access_External", 0) + 1);
                            }

                            // Service Type (Application/Network)
                            boolean isApplication = false;
                            for (ForwardingRule rule : group) {
                                String s = rule.getLoadBalancingScheme();
                                if (s != null && (s.toUpperCase().contains("EXTERNAL_MANAGED") || s.toUpperCase().contains("INTERNAL_MANAGED"))) {
                                    isApplication = true;
                                    break;
                                }
                                String t = rule.getTarget();
                                if (t != null && (t.contains("/targetHttpProxies/") || t.contains("/targetHttpsProxies/") || t.contains("/targetGrpcProxies/"))) {
                                    isApplication = true;
                                    break;
                                }
                            }
                            if (isApplication) {
                                lbCounts.put("LB_Type_Application", lbCounts.getOrDefault("LB_Type_Application", 0) + 1);
                            } else {
                                lbCounts.put("LB_Type_Network", lbCounts.getOrDefault("LB_Type_Network", 0) + 1);
                            }

                            // Region (그룹의 첫 번째 rule 기준)
                            if (!group.isEmpty()) {
                                ForwardingRule firstRule = group.get(0);
                                String region = firstRule.getRegion();
                                if (region == null || region.isEmpty()) {
                                    region = "Global";
                                } else if (region.contains("/regions/")) {
                                    region = region.substring(region.lastIndexOf("/regions/") + 9);
                                }
                                String regionKey = "LB_Region_" + region;
                                lbCounts.put(regionKey, lbCounts.getOrDefault(regionKey, 0) + 1);
                            }
                        }

                        // Application/Network 타입은 0이더라도 항상 명시적으로 INSERT
                        // (값이 없을 경우 보고서 쿼리가 이전 날짜의 잘못된 데이터를 최신값으로 오인하는 문제 방지)
                        lbCounts.putIfAbsent("LB_Type_Application", 0);
                        lbCounts.putIfAbsent("LB_Type_Network", 0);

                        for (Map.Entry<String, Integer> entry : lbCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "LoadBalancer", totalLoadBalancers);

                        // Backend Health 상태 집계 및 적재
                        try {
                            Map<String, Integer> healthCounts = gcpResourceFetcher.getBackendServiceHealthCounts(credentials, projectId);
                            int unhealthy = healthCounts.getOrDefault("unhealthy", 0);
                            int healthy = healthCounts.getOrDefault("healthy", 0);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "LB_Health_Unhealthy_Total", unhealthy);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "LB_Health_Healthy_Total", healthy);
                            log.info("LB Backend Health: healthy={}, unhealthy={} for project {}", healthy, unhealthy, projectId);
                        } catch (Exception e) {
                            log.warn("Failed to collect LB Backend Health for project {}: {}", projectId, e.getMessage());
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "LB_Health_Unhealthy_Total", 0);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "LB_Health_Healthy_Total", 0);
                        }

                        // Cloud Monitoring 최근 30일 HTTP 500 에러 사전 집계 및 적재
                        try {
                            long http500_30d = gcpResourceFetcher.getLbHttp500Last30DaysCount(credentials, projectId);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "LB_HTTP_500_30D_Total", (int) http500_30d);
                            log.info("LB HTTP 500 30-day error count: {} for project {}", http500_30d, projectId);
                        } catch (Exception e) {
                            log.warn("Failed to collect LB HTTP 500 30-day error count for project {}: {}", projectId, e.getMessage());
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "LB_HTTP_500_30D_Total", 0);
                        }

                        log.info("Successfully inserted daily batch for {} / LoadBalancer", projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for LoadBalancers in project {}", projectId, e);
                    }

                    // 3. IAM Owner Accounts (Service Account & User with roles/owner)
                    try {
                        Map<String, Long> ownerCounts = gcpResourceFetcher.getOwnerAccountCounts(credentials, projectId);
                        long ownerServiceAccountCount = ownerCounts.getOrDefault("serviceAccount", 0L);
                        long ownerUserCount = ownerCounts.getOrDefault("user", 0L);

                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_Owner_ServiceAccount", (int) ownerServiceAccountCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_Owner_User", (int) ownerUserCount);

                        log.info("IAM Owner Accounts: SA={}, User={} collected for project {}",
                            ownerServiceAccountCount, ownerUserCount, projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for IAM Owner Accounts in project {}", projectId, e);
                    }

                    // 4. IAM (all members)
                    try {
                        com.google.iam.v1.Policy policy = gcpResourceFetcher.getIamPolicy(credentials, projectId);
                        int groupCount = 0;
                        int userCount = 0;
                        int googleSaCount = 0;
                        int userSaCount = 0;
                        java.util.Set<String> uniqueMembers = new java.util.HashSet<>();

                        for (com.google.iam.v1.Binding b : policy.getBindingsList()) {
                            for (String member : b.getMembersList()) {
                                if (uniqueMembers.add(member)) { // Only count unique members
                                    if (member.startsWith("group:")) {
                                        groupCount++;
                                    } else if (member.startsWith("user:")) {
                                        userCount++;
                                    } else if (member.startsWith("serviceAccount:")) {
                                        String email = member.substring(15);
                                        if (email.endsWith("@cloudservices.gserviceaccount.com") || 
                                            (email.startsWith("service-") && email.endsWith(".iam.gserviceaccount.com"))) {
                                            googleSaCount++;
                                        } else {
                                            userSaCount++;
                                        }
                                    }
                                }
                            }
                        }
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_Group", groupCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_User", userCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_GoogleSA", googleSaCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_UserSA", userSaCount);
                    } catch (Exception e) {
                        log.error("Batch failed for IAM in project {}", projectId, e);
                    }
                    
                    // 4. Service Accounts Status and Keys
                    try {
                        int saEnabledCount = 0;
                        int saKeyUsedCount = 0;
                        int saKeyNotUsedCount = 0;
                        
                        int saKeyUnder90Count = 0;
                        int saKeyOver90Count = 0;
                        int saSingleCount = 0;
                        int saMultipleCount = 0;
                        
                        java.util.List<com.google.iam.admin.v1.ServiceAccount> serviceAccounts = gcpResourceFetcher.getServiceAccounts(credentials, projectId);
                        for (com.google.iam.admin.v1.ServiceAccount sa : serviceAccounts) {
                            if (!sa.getDisabled()) {
                                saEnabledCount++;
                            }
                            java.util.List<com.google.iam.admin.v1.ServiceAccountKey> keys = gcpResourceFetcher.getServiceAccountKeys(credentials, sa.getName());
                            if (keys != null && !keys.isEmpty()) {
                                saKeyUsedCount++;
                                
                                // Slide 6: 키 사용 90일 미만/초과 계산
                                for (com.google.iam.admin.v1.ServiceAccountKey key : keys) {
                                    long validAfterSec = key.getValidAfterTime().getSeconds();
                                    long days = java.time.temporal.ChronoUnit.DAYS.between(
                                        java.time.Instant.ofEpochSecond(validAfterSec), 
                                        java.time.Instant.now()
                                    );
                                    if (days > 90) {
                                        saKeyOver90Count++;
                                    } else {
                                        saKeyUnder90Count++;
                                    }
                                }
                                
                                // Slide 6: 단일/다중 서비스 계정 키 판별
                                if (keys.size() == 1) {
                                    saSingleCount++;
                                } else if (keys.size() >= 2) {
                                    saMultipleCount++;
                                }
                            } else {
                                saKeyNotUsedCount++;
                            }
                        }
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_SA_Enabled", saEnabledCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_SA_Key_Used", saKeyUsedCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_SA_Key_NotUsed", saKeyNotUsedCount);
                        
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_SA_Key_Under90", saKeyUnder90Count);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_SA_Key_Over90", saKeyOver90Count);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_SA_Single", saSingleCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IAM_SA_Multiple", saMultipleCount);
                    } catch (Exception e) {
                        log.error("Batch failed for Service Accounts Status in project {}", projectId, e);
                    }
                    
                    // 5. VPC Network Static IPs
                    try {
                        java.util.List<com.google.cloud.compute.v1.Address> addresses = gcpResourceFetcher.getComputeAddresses(credentials, projectId);
                        Map<String, Integer> ipCounts = new HashMap<>();

                        int extIpTotal = 0;
                        int extIpUsed = 0;
                        for (com.google.cloud.compute.v1.Address a : addresses) {
                            boolean isExternal = !"INTERNAL".equalsIgnoreCase(a.getAddressType());
                            boolean inUse = a.getUsersList() != null && !a.getUsersList().isEmpty();
                            if (isExternal) {
                                extIpTotal++;
                                if (inUse) extIpUsed++;
                            }
                            String accessType = isExternal ? "External" : "Internal";
                            String usageStatus = inUse ? "Used" : "Unused";

                            String region = a.getRegion() != null && !a.getRegion().isEmpty() ? a.getRegion() : "Global";
                            if (region.contains("/")) {
                                region = region.substring(region.lastIndexOf('/') + 1);
                            }

                            String resourceTypeKey = "VPC_IP_" + accessType + "_" + usageStatus + "_" + region;
                            ipCounts.put(resourceTypeKey, ipCounts.getOrDefault(resourceTypeKey, 0) + 1);
                        }

                        for (Map.Entry<String, Integer> entry : ipCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }
                        // VPC External Static IP 기준으로 Total 및 Used 집계 적재
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IP_Static_Total", extIpTotal);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "IP_Static_Used", extIpUsed);
                    } catch (Exception e) {
                        log.error("Batch failed for VPC Static IPs in project {}", projectId, e);
                    }

                    // 5-1. VPC Networks & Subnetworks
                    try {
                        java.util.List<com.google.cloud.compute.v1.Network> networks = gcpResourceFetcher.getComputeNetworks(credentials, projectId);
                        java.util.List<com.google.cloud.compute.v1.Subnetwork> subnetworks = gcpResourceFetcher.getComputeSubnetworks(credentials, projectId);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPC_Network_Total", networks.size());
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPC_Subnet_Total", subnetworks.size());
                        log.info("VPC Networks: {}, Subnets: {} collected for project {}", networks.size(), subnetworks.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for VPC Networks & Subnetworks in project {}", projectId, e);
                    }
                    // 6. VPC Firewalls
                    try {
                        java.util.List<com.google.cloud.compute.v1.Firewall> firewalls = gcpResourceFetcher.getComputeFirewalls(credentials, projectId);
                        int fwIngressAllowCount = 0;
                        int fwEgressAllowCount = 0;
                        int fwUsedCount = 0;
                        int fwUnusedCount = 0;

                        for (com.google.cloud.compute.v1.Firewall fw : firewalls) {
                            boolean isAllow = fw.getAllowedList() != null && !fw.getAllowedList().isEmpty();
                            if (isAllow) {
                                if ("INGRESS".equals(fw.getDirection())) {
                                    fwIngressAllowCount++;
                                } else if ("EGRESS".equals(fw.getDirection())) {
                                    fwEgressAllowCount++;
                                }
                            }
                            
                            // 방화벽 규칙 자체가 아닌 로그(LogConfig) 활성화 여부를 체크
                            boolean logEnabled = fw.hasLogConfig() && fw.getLogConfig().hasEnable() ? fw.getLogConfig().getEnable() : false;
                            if (logEnabled) {
                                fwUsedCount++;
                            } else {
                                fwUnusedCount++;
                            }
                        }
                        
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "FW_Ingress_Allow", fwIngressAllowCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "FW_Egress_Allow", fwEgressAllowCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "FW_Used", fwUsedCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "FW_Unused", fwUnusedCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "FW_Total_Rules", firewalls.size());
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "FW_Log_Enabled_Rules", fwUsedCount);
                    } catch (Exception e) {
                        log.error("Batch failed for VPC Firewalls in project {}", projectId, e);
                    }

                    // 7. Storage (Disk, Snapshot, Image)
                    try {
                        // Disks
                        java.util.List<com.google.cloud.compute.v1.Disk> disks = gcpResourceFetcher.getComputeDisks(credentials, projectId);
                        int diskTotal = 0;
                        int diskIdle = 0;
                        Map<String, Integer> diskTypeCounts = new HashMap<>();

                        for (com.google.cloud.compute.v1.Disk disk : disks) {
                            if (disk.hasStatus()) {
                                String status = disk.getStatus();
                                if ("DELETING".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                                    continue;
                                }
                            }
                            diskTotal++;
                            if (disk.getUsersList() == null || disk.getUsersList().isEmpty()) {
                                diskIdle++;
                            }
                            // Type parsing
                            String type = disk.getType();
                            if (type != null && type.contains("/diskTypes/")) {
                                type = type.substring(type.lastIndexOf("/diskTypes/") + 11);
                            } else {
                                type = "unknown";
                            }
                            String typeKey = "Storage_Type_" + type;
                            diskTypeCounts.put(typeKey, diskTypeCounts.getOrDefault(typeKey, 0) + 1);
                        }

                        // Snapshots
                        java.util.List<com.google.cloud.compute.v1.Snapshot> snapshots = gcpResourceFetcher.getComputeSnapshots(credentials, projectId);
                        int snapshotTotal = snapshots.size();

                        // Images
                        java.util.List<com.google.cloud.compute.v1.Image> images = gcpResourceFetcher.getComputeImages(credentials, projectId);
                        int imageTotal = images.size();

                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Disk_Total", diskTotal);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Disk_Idle", diskIdle);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Snapshot_Total", snapshotTotal);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Image_Total", imageTotal);

                        for (Map.Entry<String, Integer> entry : diskTypeCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        // Storage Buckets Location (for Slide 13) and Security (for Slide 14)
                        try {
                            List<com.google.cloud.storage.Bucket> buckets = gcpResourceFetcher.getStorageBuckets(credentials, projectId);
                            Map<String, Integer> bucketCounts = new HashMap<>();
                            int aclCount = 0;
                            int notPublicCount = 0;
                            int lifecycleEnabledCount = 0;
                            int lifecycleDisabledCount = 0;

                            for (com.google.cloud.storage.Bucket bucket : buckets) {
                                String location = bucket.getLocation();
                                if (location == null || location.isEmpty()) {
                                    location = "unknown";
                                } else {
                                    location = location.toLowerCase();
                                }
                                String key = "Storage_Location_" + location;
                                bucketCounts.put(key, bucketCounts.getOrDefault(key, 0) + 1);

                                // Slide 14: Bucket security/lifecycle metrics
                                com.google.cloud.storage.BucketInfo.IamConfiguration iamConfig = bucket.getIamConfiguration();
                                Object pap = (iamConfig != null) ? iamConfig.getPublicAccessPrevention() : null;
                                if (pap != null && "enforced".equalsIgnoreCase(pap.toString())) {
                                    notPublicCount++;
                                } else {
                                    aclCount++;
                                }

                                java.util.List<? extends com.google.cloud.storage.BucketInfo.LifecycleRule> rules = bucket.getLifecycleRules();
                                if (rules != null && !rules.isEmpty()) {
                                    lifecycleEnabledCount++;
                                } else {
                                    lifecycleDisabledCount++;
                                }
                            }
                            for (Map.Entry<String, Integer> entry : bucketCounts.entrySet()) {
                                insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                            }
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Security_ACL", aclCount);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Security_NotPublic", notPublicCount);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Security_LifecycleEnabled", lifecycleEnabledCount);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Security_LifecycleDisabled", lifecycleDisabledCount);
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, "Storage_Bucket_Total", buckets.size());
                        } catch (Exception e) {
                            log.error("Batch failed for Storage Buckets in project {}", projectId, e);
                        }
                    } catch (Exception e) {
                        log.error("Batch failed for Storage in project {}", projectId, e);
                    }

                    // 8. GCP CUD (Committed Use Discount) - 리소스 기반
                    try {
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
                            // 리소스 상세 빌드
                            StringBuilder resourceDetail = new StringBuilder();
                            if (c.getResourcesList() != null) {
                                for (ResourceCommitment rc : c.getResourcesList()) {
                                    if (resourceDetail.length() > 0) resourceDetail.append(", ");
                                    resourceDetail.append(rc.getType()).append(": ").append(rc.getAmount());
                                }
                            }
                            insertDailyReservationBatch(snapshotDate, projectId, customerName, "GCP",
                                c.getName(), status, startDate, endDate, plan, category, region, "", resourceDetail.toString());
                        }
                        log.info("GCP CUD: {} commitments collected for project {}", commitments.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for GCP CUD in project {}", projectId, e);
                    }

                    // 9. SSL Certificates
                    try {
                        List<SslCertificate> certs = gcpResourceFetcher.getSslCertificates(credentials, projectId);
                        int selfManagedCount = 0;
                        int googleManagedCount = 0;
                        int sslNotExpiredCount = 0;
                        int sslExpiredCount = 0;

                        for (SslCertificate cert : certs) {
                            boolean isSelf = "SELF_MANAGED".equalsIgnoreCase(cert.getType());
                            boolean isManaged = "MANAGED".equalsIgnoreCase(cert.getType());

                            if (isSelf) {
                                selfManagedCount++;
                            } else if (isManaged) {
                                googleManagedCount++;
                            }

                            if (isSelf || isManaged) {
                                String expireTimeStr = cert.getExpireTime();
                                if (expireTimeStr != null && !expireTimeStr.isEmpty()) {
                                    java.time.Instant expireInstant = java.time.Instant.parse(expireTimeStr);
                                    if (expireInstant.isBefore(java.time.Instant.now())) {
                                        sslExpiredCount++;
                                    } else {
                                        sslNotExpiredCount++;
                                    }
                                }
                            }
                        }

                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "SSL_SelfManaged", selfManagedCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "SSL_GoogleManaged", googleManagedCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "SSL_NotExpired", sslNotExpiredCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "SSL_Expired", sslExpiredCount);
                        log.info("SSL Certificates: selfManaged={}, googleManaged={}, notExpired={}, expired={} collected for project {}", 
                            selfManagedCount, googleManagedCount, sslNotExpiredCount, sslExpiredCount, projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for SSL Certificates in project {}", projectId, e);
                    }

                    // 10. VPN Gateways & Tunnels
                    try {
                        List<VpnGateway> vpnGateways = gcpResourceFetcher.getVpnGateways(credentials, projectId);
                        List<TargetVpnGateway> targetVpnGateways = gcpResourceFetcher.getTargetVpnGateways(credentials, projectId);
                        List<VpnTunnel> vpnTunnels = gcpResourceFetcher.getVpnTunnels(credentials, projectId);

                        int haVpnCount = vpnGateways.size();
                        int classicVpnCount = targetVpnGateways.size();

                        int connectedTunnelCount = 0;
                        int disconnectedTunnelCount = 0;
                        int haVpnTunnelCount = 0;
                        int classicVpnTunnelCount = 0;

                        Map<String, Integer> gatewayRegionCounts = new HashMap<>();
                        Map<String, Integer> tunnelRegionCounts = new HashMap<>();

                        for (VpnGateway gw : vpnGateways) {
                            String region = "Global";
                            if (gw.hasRegion()) {
                                String regionUrl = gw.getRegion();
                                region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                            }
                            String rKey = "VPN_Gateway_Region_" + region;
                            gatewayRegionCounts.put(rKey, gatewayRegionCounts.getOrDefault(rKey, 0) + 1);
                        }

                        for (TargetVpnGateway gw : targetVpnGateways) {
                            String region = "Global";
                            if (gw.hasRegion()) {
                                String regionUrl = gw.getRegion();
                                region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                            }
                            String rKey = "VPN_Gateway_Region_" + region;
                            gatewayRegionCounts.put(rKey, gatewayRegionCounts.getOrDefault(rKey, 0) + 1);
                        }

                        for (VpnTunnel tunnel : vpnTunnels) {
                            // Status check
                            if (tunnel.hasStatus() && "ESTABLISHED".equalsIgnoreCase(tunnel.getStatus())) {
                                connectedTunnelCount++;
                            } else {
                                disconnectedTunnelCount++;
                            }

                            // Tunnel Type check (HA vs Classic)
                            if (tunnel.hasVpnGateway() && !tunnel.getVpnGateway().isEmpty()) {
                                haVpnTunnelCount++;
                            } else if (tunnel.hasTargetVpnGateway() && !tunnel.getTargetVpnGateway().isEmpty()) {
                                classicVpnTunnelCount++;
                            }

                            // Region parsing
                            String region = "Global";
                            if (tunnel.hasRegion()) {
                                String regionUrl = tunnel.getRegion();
                                region = regionUrl.contains("/") ? regionUrl.substring(regionUrl.lastIndexOf('/') + 1) : regionUrl;
                            }
                            String rKey = "VPN_Tunnel_Region_" + region;
                            tunnelRegionCounts.put(rKey, tunnelRegionCounts.getOrDefault(rKey, 0) + 1);
                        }

                        // Write data to BigQuery
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPN_Gateway_Type_HA", haVpnCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPN_Gateway_Type_Classic", classicVpnCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPN_Tunnel_Type_HA", haVpnTunnelCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPN_Tunnel_Type_Classic", classicVpnTunnelCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPN_Tunnel_State_Connected", connectedTunnelCount);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "VPN_Tunnel_State_Disconnected", disconnectedTunnelCount);

                        for (Map.Entry<String, Integer> entry : gatewayRegionCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        for (Map.Entry<String, Integer> entry : tunnelRegionCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        log.info("VPN: Gateway HA={}, Gateway Classic={}, Tunnel HA={}, Tunnel Classic={}, ConnectedTunnel={}, DisconnectedTunnel={} collected for project {}", 
                            haVpnCount, classicVpnCount, haVpnTunnelCount, classicVpnTunnelCount, connectedTunnelCount, disconnectedTunnelCount, projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for VPN Gateways & Tunnels in project {}", projectId, e);
                    }

                    // 11. Cloud SQL
                    try {
                        List<com.google.cloud.asset.v1.Asset> sqlAssets = gcpResourceFetcher.getCloudSqlAssets(credentials, projectId);
                        Map<String, Integer> sqlCounts = new HashMap<>();

                        for (com.google.cloud.asset.v1.Asset asset : sqlAssets) {
                            com.google.protobuf.Struct data = asset.getResource().getData();
                            
                            // Region
                            String region = getStringFromStruct(data, "region");
                            if (region == null || region.isEmpty()) region = "Unknown";
                            sqlCounts.put("SQL_Region_" + region, sqlCounts.getOrDefault("SQL_Region_" + region, 0) + 1);

                            // Instance Type (Primary / ReadReplica)
                            String instType = getStringFromStruct(data, "instanceType");
                            String typeName = "CLOUD_SQL_INSTANCE".equalsIgnoreCase(instType) ? "Primary" : ("READ_REPLICA_INSTANCE".equalsIgnoreCase(instType) ? "ReadReplica" : "Other");
                            sqlCounts.put("SQL_Instance_Type_" + typeName, sqlCounts.getOrDefault("SQL_Instance_Type_" + typeName, 0) + 1);

                            // Machine Type (Tier)
                            String tier = getStringFromStruct(data, "settings", "tier");
                            if (tier == null || tier.isEmpty()) tier = "Unknown";
                            sqlCounts.put("SQL_Machine_Type_" + tier, sqlCounts.getOrDefault("SQL_Machine_Type_" + tier, 0) + 1);

                            // DB Engine & Version
                            String engine = getStringFromStruct(data, "databaseVersion");
                            if (engine == null || engine.isEmpty()) engine = "Unknown";
                            sqlCounts.put("SQL_Engine_" + engine, sqlCounts.getOrDefault("SQL_Engine_" + engine, 0) + 1);

                            // Availability Type (REGIONAL vs ZONAL)
                            String avail = getStringFromStruct(data, "settings", "availabilityType");
                            String availKey = "REGIONAL".equalsIgnoreCase(avail) ? "SQL_Availability_Regional" : "SQL_Availability_Zonal";
                            sqlCounts.put(availKey, sqlCounts.getOrDefault(availKey, 0) + 1);

                            // Storage Type (PD_SSD vs PD_HDD)
                            String diskType = getStringFromStruct(data, "settings", "dataDiskType");
                            String diskKey = (diskType != null && diskType.contains("HDD")) ? "SQL_Storage_HDD" : "SQL_Storage_SSD";
                            sqlCounts.put(diskKey, sqlCounts.getOrDefault(diskKey, 0) + 1);
                        }

                        for (Map.Entry<String, Integer> entry : sqlCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        log.info("Cloud SQL: {} instances collected for project {}", sqlAssets.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Cloud SQL in project {}", projectId, e);
                    }

                    // 12. Cloud Run Services & Jobs
                    try {
                        List<com.google.cloud.run.v2.Service> runServices = gcpResourceFetcher.getCloudRunServices(credentials, projectId);
                        Map<String, Integer> runServiceCounts = new HashMap<>();

                        int crSvcContainer = 0;
                        int crSvcFunction = 0;

                        for (com.google.cloud.run.v2.Service svc : runServices) {
                            // 배포 유형: CONTAINER vs FUNCTION
                            // launchStage == BETA or annotations 기반으로 함수 판별
                            // Cloud Run functions(2nd gen)은 labels에 'goog-managed-by: cloudfunctions' 포함
                            Map<String, String> labels = svc.getLabelsMap();
                            boolean isFunction = labels != null &&
                                    "cloudfunctions".equals(labels.get("goog-managed-by"));
                            if (isFunction) {
                                crSvcFunction++;
                            } else {
                                crSvcContainer++;
                            }

                            // 리전: name = projects/{proj}/locations/{region}/services/{svc}
                            String svcName = svc.getName();
                            String region = "unknown";
                            if (svcName != null && svcName.contains("/locations/")) {
                                String after = svcName.substring(svcName.indexOf("/locations/") + 11);
                                region = after.contains("/") ? after.substring(0, after.indexOf("/")) : after;
                            }
                            String regionKey = "CloudRun_Svc_Region_" + region;
                            runServiceCounts.put(regionKey, runServiceCounts.getOrDefault(regionKey, 0) + 1);

                            // 인그레스 설정: INGRESS_TRAFFIC_ALL / INGRESS_TRAFFIC_INTERNAL_ONLY / INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER
                            String ingressValue = svc.getIngress().name();
                            String ingressKey;
                            if (ingressValue.contains("INTERNAL_ONLY")) {
                                ingressKey = "CloudRun_Svc_Ingress_Internal";
                            } else if (ingressValue.contains("INTERNAL_LOAD_BALANCER")) {
                                ingressKey = "CloudRun_Svc_Ingress_InternalLB";
                            } else {
                                // INGRESS_TRAFFIC_ALL 또는 기타 = 전체
                                ingressKey = "CloudRun_Svc_Ingress_All";
                            }
                            runServiceCounts.put(ingressKey, runServiceCounts.getOrDefault(ingressKey, 0) + 1);

                            // 바이너리 승인: BinaryAuthorization breakglassJustification 미설정 → 사용 안함
                            com.google.cloud.run.v2.BinaryAuthorization binAuth = svc.getBinaryAuthorization();
                            boolean binAuthEnabled = binAuth != null && binAuth.getUseDefault();
                            if (!binAuthEnabled) {
                                runServiceCounts.put("CloudRun_Svc_BinAuth_Disabled",
                                        runServiceCounts.getOrDefault("CloudRun_Svc_BinAuth_Disabled", 0) + 1);
                            } else {
                                runServiceCounts.put("CloudRun_Svc_BinAuth_Enabled",
                                        runServiceCounts.getOrDefault("CloudRun_Svc_BinAuth_Enabled", 0) + 1);
                            }
                        }

                        // 배포 유형 저장
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "CloudRun_Svc_DeployType_Container", crSvcContainer);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "CloudRun_Svc_DeployType_Function", crSvcFunction);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "CloudRun_Svc_Total", runServices.size());

                        for (Map.Entry<String, Integer> entry : runServiceCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        log.info("Cloud Run Services: total={}, container={}, function={} for project {}",
                                runServices.size(), crSvcContainer, crSvcFunction, projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Cloud Run Services in project {}", projectId, e);
                    }

                    // 13. Cloud Run Jobs
                    try {
                        List<com.google.cloud.run.v2.Job> runJobs = gcpResourceFetcher.getCloudRunJobs(credentials, projectId);
                        Map<String, Integer> runJobCounts = new HashMap<>();

                        for (com.google.cloud.run.v2.Job job : runJobs) {
                            // 리전: name = projects/{proj}/locations/{region}/jobs/{job}
                            String jobName = job.getName();
                            String region = "unknown";
                            if (jobName != null && jobName.contains("/locations/")) {
                                String after = jobName.substring(jobName.indexOf("/locations/") + 11);
                                region = after.contains("/") ? after.substring(0, after.indexOf("/")) : after;
                            }
                            String regionKey = "CloudRun_Job_Region_" + region;
                            runJobCounts.put(regionKey, runJobCounts.getOrDefault(regionKey, 0) + 1);
                        }

                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "CloudRun_Job_Total", runJobs.size());
                        for (Map.Entry<String, Integer> entry : runJobCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        log.info("Cloud Run Jobs: total={} for project {}", runJobs.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Cloud Run Jobs in project {}", projectId, e);
                    }

                    // 14. GKE (Google Kubernetes Engine) Clusters
                    try {
                        List<com.google.container.v1.Cluster> clusters = gcpResourceFetcher.getGkeClusters(credentials, projectId);
                        Map<String, Integer> gkeCounts = new HashMap<>();
                        int totalNodes = 0;

                        for (com.google.container.v1.Cluster cluster : clusters) {
                            // 리전 파싱: location field (e.g., "asia-northeast3" or "asia-northeast3-a")
                            String location = cluster.getLocation();
                            if (location == null || location.isEmpty()) location = "unknown";
                            // zone → region 변환 (zone은 리전-영역 형식)
                            String region = location.matches(".*-[a-z]$") ? location.substring(0, location.lastIndexOf("-")) : location;
                            gkeCounts.put("GKE_Cluster_Region_" + region, gkeCounts.getOrDefault("GKE_Cluster_Region_" + region, 0) + 1);

                            // 노드 수 합산 (currentNodeCount)
                            totalNodes += cluster.getCurrentNodeCount();

                            // Autopilot 여부
                            boolean isAutopilot = cluster.hasAutopilot() && cluster.getAutopilot().getEnabled();
                            if (isAutopilot) {
                                gkeCounts.put("GKE_Cluster_Type_Autopilot", gkeCounts.getOrDefault("GKE_Cluster_Type_Autopilot", 0) + 1);
                            } else {
                                gkeCounts.put("GKE_Cluster_Type_Standard", gkeCounts.getOrDefault("GKE_Cluster_Type_Standard", 0) + 1);
                            }
                        }

                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "GKE_Cluster_Total", clusters.size());
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "GKE_Node_Total", totalNodes);
                        for (Map.Entry<String, Integer> entry : gkeCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        log.info("GKE: clusters={}, nodes={} for project {}", clusters.size(), totalNodes, projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for GKE Clusters in project {}", projectId, e);
                    }

                    // 15. App Engine Services (via Asset Inventory)
                    try {
                        int aeServiceCount = gcpResourceFetcher.getAppEngineServiceCount(credentials, projectId);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "AppEngine_Service_Total", aeServiceCount);
                        log.info("App Engine: services={} for project {}", aeServiceCount, projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for App Engine in project {}", projectId, e);
                    }

                    // 16. Cloud KMS KeyRings
                    try {
                        List<com.google.cloud.kms.v1.KeyRing> keyRings = gcpResourceFetcher.getKmsKeyRings(credentials, projectId);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "KMS_KeyRing_Total", keyRings.size());
                        log.info("Cloud KMS: keyRings={} for project {}", keyRings.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Cloud KMS in project {}", projectId, e);
                    }

                    // 17. Secret Manager Secrets
                    try {
                        List<com.google.cloud.secretmanager.v1.Secret> secrets = gcpResourceFetcher.getSecrets(credentials, projectId);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "SecretManager_Secret_Total", secrets.size());
                        log.info("Secret Manager: secrets={} for project {}", secrets.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Secret Manager in project {}", projectId, e);
                    }

                    // 18. Pub/Sub Topics & Subscriptions
                    try {
                        List<com.google.pubsub.v1.Topic> topics = gcpResourceFetcher.getPubSubTopics(credentials, projectId);
                        List<com.google.pubsub.v1.Subscription> subscriptions = gcpResourceFetcher.getPubSubSubscriptions(credentials, projectId);
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "PubSub_Topic_Total", topics.size());
                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "PubSub_Subscription_Total", subscriptions.size());
                        log.info("Pub/Sub: topics={}, subscriptions={} for project {}", topics.size(), subscriptions.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Pub/Sub in project {}", projectId, e);
                    }

                    // 19. Memorystore (Redis) Instances
                    try {
                        List<com.google.cloud.redis.v1.Instance> redisInstances = gcpResourceFetcher.getMemorystoreInstances(credentials, projectId);
                        Map<String, Integer> redisCounts = new HashMap<>();

                        for (com.google.cloud.redis.v1.Instance inst : redisInstances) {
                            // 리전: locationId field (e.g., "asia-northeast3-a" → "asia-northeast3")
                            String locationId = inst.getLocationId();
                            String region = (locationId != null && locationId.matches(".*-[a-z]$"))
                                    ? locationId.substring(0, locationId.lastIndexOf("-")) : (locationId != null ? locationId : "unknown");
                            redisCounts.put("Memorystore_Region_" + region, redisCounts.getOrDefault("Memorystore_Region_" + region, 0) + 1);

                            // Tier: BASIC or STANDARD_HA
                            String tier = inst.getTier().name();
                            redisCounts.put("Memorystore_Tier_" + tier, redisCounts.getOrDefault("Memorystore_Tier_" + tier, 0) + 1);
                        }

                        insertDailyAssetBatch(snapshotDate, projectId, customerName, "Memorystore_Instance_Total", redisInstances.size());
                        for (Map.Entry<String, Integer> entry : redisCounts.entrySet()) {
                            insertDailyAssetBatch(snapshotDate, projectId, customerName, entry.getKey(), entry.getValue());
                        }

                        log.info("Memorystore Redis: instances={} for project {}", redisInstances.size(), projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Memorystore in project {}", projectId, e);
                    }

                    // 20. GCP Active Assist Recommender REST API Batch Collection & BigQuery Persistence
                    try {
                        for (String cat : Arrays.asList("SECURITY", "COST", "PERFORMANCE", "RELIABILITY", "MANAGABILITY", "SUSTAINABILITY")) {
                            List<GcpRecommenderService.GcpRecommendation> realRecs = gcpRecommenderService.fetchRealGcpRecommendations(credentials, projectId, cat);
                            for (GcpRecommenderService.GcpRecommendation rec : realRecs) {
                                String prio = rec.getPriority() != null && !rec.getPriority().isEmpty() ? rec.getPriority() : "MEDIUM";
                                String targetRes = rec.getTargetResource() != null ? rec.getTargetResource() : "";
                                insertDailyRecommenderBatch(snapshotDate, projectId, customerName, cat, prio, rec.getRecommenderId(), targetRes, rec.getDescription());
                            }
                        }
                        log.info("GCP Active Assist Recommender: successfully collected real recommendations for project {}", projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for GCP Recommender API in project {}", projectId, e);
                    }

                    // 21. GCP Vertex AI & GenAI Operations Metrics Collection (전체 고객사 프로젝트 순회)
                    try {
                        collectAndInsertDailyVertexAiMetrics(snapshotDate, projectId, customerName, credentials);
                        log.info("GCP Vertex AI Metrics: successfully collected for project {}", projectId);
                    } catch (Exception e) {
                        log.error("Batch failed for Vertex AI Metrics in project {}", projectId, e);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to process GCP environment: {}", env.getEnvironmentName(), e);
            }
        }


        // === Azure RI (Reserved Instances) 수집 ===
        Set<String> processedTenants = new HashSet<>();
        for (InfraEnvironment env : environments) {
            if (!"AZURE".equalsIgnoreCase(env.getProviderType())) continue;

            String tenantId = env.getAzureTenantId();
            String clientId = env.getAzureClientId();
            String clientSecret = environmentService.getDecryptedSecret(env.getId());
            if (tenantId == null || clientId == null || clientSecret == null) continue;

            String customerName = env.getCustomer() != null && env.getCustomer().getName() != null ? env.getCustomer().getName() : "Unknown";
            String subscriptionId = (env.getProjects() != null && !env.getProjects().isEmpty()) ? env.getProjects().get(0).getProjectId() : "";

            // 동일 테넌트/고객사는 루프당 1회만 처리하여 중복 수집 방지
            String tenantKey = customerName + "_" + tenantId;
            if (!processedTenants.add(tenantKey)) {
                log.info("Skipping duplicate Azure RI fetch for already processed tenant: {}", tenantKey);
                continue;
            }

            // 당일 수집 시 기존 당일 데이터 삭제하여 누적 중복 적재 방지
            deleteDailyReservations(snapshotDate, customerName, "AZURE");

            try {
                // 1. Get Azure AD Token
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
                try (Scanner scanner = new Scanner(tokenConn.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
                    tokenResponse = scanner.hasNext() ? scanner.next() : "";
                }
                ObjectMapper mapper = new ObjectMapper();
                String accessToken = mapper.readTree(tokenResponse).get("access_token").asText();

                // 2. Call Microsoft.Capacity/reservations API
                String riUrl = "https://management.azure.com/providers/Microsoft.Capacity/reservations?api-version=2022-11-01";
                HttpURLConnection riConn = (HttpURLConnection) new URL(riUrl).openConnection();
                riConn.setRequestMethod("GET");
                riConn.setRequestProperty("Authorization", "Bearer " + accessToken);
                riConn.setRequestProperty("Content-Type", "application/json");

                String riResponse;
                try (Scanner scanner = new Scanner(riConn.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
                    riResponse = scanner.hasNext() ? scanner.next() : "";
                }

                JsonNode riRoot = mapper.readTree(riResponse);
                JsonNode riValues = riRoot.get("value");
                int riCount = 0;
                Set<String> processedRiKeys = new HashSet<>();

                if (riValues != null && riValues.isArray()) {
                    for (JsonNode ri : riValues) {
                        JsonNode props = ri.get("properties");
                        if (props == null) continue;

                        String status = props.has("provisioningState") ? props.get("provisioningState").asText() : "";
                        // 유효하지 않은 과거 이력(Cancelled, Split, Merged 등) 스킵
                        if (status.equalsIgnoreCase("Cancelled") || status.equalsIgnoreCase("Split") || status.equalsIgnoreCase("Merged")) {
                            continue;
                        }

                        String name = ri.has("name") ? ri.get("name").asText() : "";
                        String displayName = props.has("displayName") ? props.get("displayName").asText() : name;
                        String expiryDate = props.has("expiryDate") ? props.get("expiryDate").asText() : "";
                        String effectiveDate = props.has("effectiveDateTime") ? props.get("effectiveDateTime").asText().substring(0, 10) : "";
                        String riType = props.has("reservedResourceType") ? props.get("reservedResourceType").asText() : "";
                        String skuName = (ri.has("sku") && ri.get("sku").has("name")) ? ri.get("sku").get("name").asText() : "";
                        String location = props.has("location") ? props.get("location").asText() : "";
                        String riScope = props.has("appliedScopeType") ? props.get("appliedScopeType").asText() : "";
                        String term = props.has("term") ? props.get("term").asText() : "";
                        int quantity = props.has("quantity") ? props.get("quantity").asInt() : 1;

                        String resourceDetail = skuName;
                        if (quantity > 1) {
                            resourceDetail += " (수량: " + quantity + ")";
                        }

                        // 수집 건 단위 중복 키 체크
                        String uniqueKey = (displayName.isEmpty() ? name : displayName) + "_" + effectiveDate + "_" + skuName + "_" + location;
                        if (!processedRiKeys.add(uniqueKey)) {
                            log.info("Skipping duplicate RI entry in API response: {}", uniqueKey);
                            continue;
                        }

                        // 상태 매핑
                        String mappedStatus = "Succeeded".equalsIgnoreCase(status) ? "ACTIVE" : status.toUpperCase();
                        if (!expiryDate.isEmpty() && expiryDate.compareTo(snapshotDate) < 0) mappedStatus = "EXPIRED";

                        insertDailyReservationBatch(snapshotDate, subscriptionId, customerName, "AZURE",
                            displayName.isEmpty() ? name : displayName, mappedStatus, effectiveDate, expiryDate, term, riType, location, riScope, resourceDetail);
                        riCount++;
                    }
                }
                log.info("Azure RI: {} valid reservations collected for customer {}", riCount, customerName);
            } catch (Exception e) {
                log.error("Failed to process Azure RI for environment: {}", env.getEnvironmentName(), e);
            }
            try {
                collectAzureAppCredentials(env, snapshotDate, tenantId, clientId, clientSecret);
            } catch (Exception e) {
                log.error("Failed to process Azure App keys check for environment: {}", env.getEnvironmentName(), e);
            }
        }

        // 과거 월(Past Months) 전체 데이터 스냅샷 일괄 정리 (자산, 예약, Recommender, Vertex AI 등 일원화)
        cleanAllPastMonthlySnapshots(snapshotDate);

        log.info("Finished Daily Snapshot Batch (including Reservations, Vertex AI Metrics, and All Monthly Snapshot Cleanups)");
    }

    private void insertDailyAssetBatch(String snapshotDate, String projectId, String customerName, String resourceType, int count) {
        TableId tableId = TableId.of(targetProjectId, datasetName, "daily_asset_inventory");
        Map<String, Object> rowContent = new HashMap<>();
        rowContent.put("snapshot_date", snapshotDate);
        rowContent.put("project_id", projectId);
        rowContent.put("customer_name", customerName);
        rowContent.put("resource_type", resourceType);
        rowContent.put("resource_count", count);

        try {
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(rowContent)
                    .build();
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            if (response.hasErrors()) {
                log.error("BigQuery insert error for daily_asset_inventory: {}", response.getInsertErrors());
            } else {
                log.info("Successfully inserted daily batch for {} / {} into {}.{}", projectId, resourceType, targetProjectId, datasetName);
            }
        } catch (Exception e) {
            log.error("BigQuery insert failed", e);
        }
    }

    private void ensureDailyRecommenderTableExists() {
        try {
            String createTableDdl = String.format(
                "CREATE TABLE IF NOT EXISTS `%s.%s.daily_recommender_inventory` (" +
                "  snapshot_date DATE," +
                "  project_id STRING," +
                "  customer_name STRING," +
                "  category STRING," +
                "  priority STRING," +
                "  recommender_id STRING," +
                "  target_resource_name STRING," +
                "  recommendation_description STRING," +
                "  created_at TIMESTAMP" +
                ")", targetProjectId, datasetName
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(createTableDdl).build());

            // 기존 테이블에 target_resource_name 컬럼이 없는 경우를 위한 안전 마이그레이션 DDL
            try {
                String alterTableDdl = String.format(
                    "ALTER TABLE `%s.%s.daily_recommender_inventory` ADD COLUMN IF NOT EXISTS target_resource_name STRING",
                    targetProjectId, datasetName
                );
                bigQuery.query(QueryJobConfiguration.newBuilder(alterTableDdl).build());
            } catch (Exception alterEx) {
                log.debug("ALTER TABLE target_resource_name skipped/already exists: {}", alterEx.getMessage());
            }
        } catch (Exception e) {
            log.debug("Check/Create daily_recommender_inventory table skipped: {}", e.getMessage());
        }
    }

    /**
     * 당월(YYYY-MM) Recommender 데이터 부분 삭제 (DELETE / CTAS)
     * - 과거 월(예: 8월, 7월 등)의 마지막 스냅샷 데이터는 그대로 보존
     * - 현재 진행 중인 당월(예: 9월)의 데이터만 덮어쓰기 위해 당월 기존 레코드를 선행 삭제
     * - BigQuery Streaming Buffer DML 제한 회피를 위해 과거 월 데이터 보존 CTAS 쿼리 적용
     * - daily_asset_inventory, jira_issue_inventory, daily_reservation_inventory 등 타 배치에는 일절 영향 없음
     */
    public void deleteCurrentMonthDailyRecommenders(String snapshotDate) {
        ensureDailyRecommenderTableExists();
        if (snapshotDate == null || snapshotDate.length() < 7) {
            log.warn("Invalid snapshotDate for deleteCurrentMonthDailyRecommenders: {}", snapshotDate);
            return;
        }
        String yearMonthPrefix = snapshotDate.substring(0, 7); // "YYYY-MM"
        try {
            // BigQuery 스트리밍 버퍼(Streaming Buffer) DML 제한을 안전하게 회피하면서 과거 월 데이터는 온전히 보존하고 당월 데이터만 제거
            String ctasSql = String.format(
                "CREATE OR REPLACE TABLE `%s.%s.daily_recommender_inventory` AS " +
                "SELECT * FROM `%s.%s.daily_recommender_inventory` " +
                "WHERE NOT STARTS_WITH(CAST(snapshot_date AS STRING), '%s')",
                targetProjectId, datasetName,
                targetProjectId, datasetName,
                yearMonthPrefix
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(ctasSql).build());
            log.info("Successfully cleaned existing recommender records for month prefix '{}' in {}.{}.daily_recommender_inventory (Past months preserved)", yearMonthPrefix, targetProjectId, datasetName);
        } catch (Exception e) {
            // 초기 빈 테이블 또는 CTAS 실패 시 DML DELETE Fallback
            try {
                String deleteSql = String.format(
                    "DELETE FROM `%s.%s.daily_recommender_inventory` WHERE STARTS_WITH(CAST(snapshot_date AS STRING), '%s')",
                    targetProjectId, datasetName, yearMonthPrefix
                );
                bigQuery.query(QueryJobConfiguration.newBuilder(deleteSql).build());
                log.info("Successfully cleaned existing recommender records for month prefix '{}' via DML fallback", yearMonthPrefix);
            } catch (Exception fallbackEx) {
                log.warn("Recommender month cleanup notice for '{}': {}", yearMonthPrefix, fallbackEx.getMessage());
            }
        }
    }

    private void insertDailyRecommenderBatch(String snapshotDate, String projectId, String customerName, String category, String priority, String recommenderId, String targetResourceName, String description) {
        ensureDailyRecommenderTableExists();
        TableId tableId = TableId.of(targetProjectId, datasetName, "daily_recommender_inventory");
        Map<String, Object> rowContent = new HashMap<>();
        rowContent.put("snapshot_date", snapshotDate);
        rowContent.put("project_id", projectId);
        rowContent.put("customer_name", customerName);
        rowContent.put("category", category);
        rowContent.put("priority", priority);
        rowContent.put("recommender_id", recommenderId);
        rowContent.put("target_resource_name", targetResourceName != null ? targetResourceName : "");
        rowContent.put("recommendation_description", description);
        rowContent.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        try {
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(rowContent)
                    .build();
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            if (response.hasErrors()) {
                log.error("BigQuery insert error for daily_recommender_inventory: {}", response.getInsertErrors());
            } else {
                log.info("Successfully inserted daily recommender batch for {} / {} / {} / {} into {}.{}", projectId, category, recommenderId, targetResourceName, targetProjectId, datasetName);
            }
        } catch (Exception e) {
            log.error("BigQuery insert failed for daily_recommender_inventory", e);
        }
    }

    private void ensureDailyVertexAiMetricsTableExists() {
        try {
            String createTableDdl = String.format(
                "CREATE TABLE IF NOT EXISTS `%s.%s.daily_vertex_ai_metrics` (" +
                "  snapshot_date DATE," +
                "  project_id STRING," +
                "  customer_name STRING," +
                "  input_tokens INT64," +
                "  output_tokens INT64," +
                "  current_rpm INT64," +
                "  max_rpm_quota INT64," +
                "  current_tpd INT64," +
                "  max_tpd_quota INT64," +
                "  total_endpoints INT64," +
                "  active_endpoints INT64," +
                "  idle_endpoints INT64," +
                "  allocated_gpus INT64," +
                "  allocated_tpus INT64," +
                "  gpu_model STRING," +
                "  estimated_hourly_cost FLOAT64," +
                "  gemini_flash_ratio FLOAT64," +
                "  gemini_pro_ratio FLOAT64," +
                "  fine_tuned_ratio FLOAT64," +
                "  prompt_cache_hit_ratio FLOAT64," +
                "  rate_limit_429_errors INT64," +
                "  safety_filter_blocks INT64," +
                "  avg_latency_ms INT64," +
                "  created_at TIMESTAMP" +
                ")", targetProjectId, datasetName
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(createTableDdl).build());
        } catch (Exception e) {
            log.debug("Check/Create daily_vertex_ai_metrics table skipped: {}", e.getMessage());
        }
    }

    /**
     * 전체 BigQuery 테이블 대상 과거 월 스냅샷 통합 정리 (자산, 예약, Recommender, Vertex AI 일원화)
     * - 당월(Current Month) 데이터: 일별 누적 또는 최신 상태 보존
     * - 과거 월(Past Months) 데이터: (project_id 또는 customer_name, YYYY-MM) 기준 MAX(snapshot_date) 1건(1일치)만 보존하고 나머지 삭제
     */
    public void cleanAllPastMonthlySnapshots(String snapshotDate) {
        log.info("=== 🧹 Starting Unified Past Monthly Snapshots Cleanup for All BigQuery Tables (snapshotDate: {}) ===", snapshotDate);
        cleanPastMonthlyAssetSnapshots(snapshotDate);
        cleanPastMonthlyReservationSnapshots(snapshotDate);
        cleanPastMonthlyRecommenderSnapshots(snapshotDate);
        cleanPastMonthlyVertexAiSnapshots(snapshotDate);
        log.info("=== 🏁 Completed Unified Past Monthly Snapshots Cleanup for All BigQuery Tables ===");
    }

    /**
     * 과거 월(Past Months) Asset 데이터 정리 (스냅샷 정책)
     * - 당월(Current Month) 데이터: 일별로 계속 누적 보존
     * - 이전 달(Past Months) 데이터: 각 프로젝트/월별 가장 늦은 날짜(MAX snapshot_date) 1일치만 남기고 나머지 일자 데이터는 모두 삭제
     */
    public void cleanPastMonthlyAssetSnapshots(String snapshotDate) {
        if (snapshotDate == null || snapshotDate.length() < 7) return;
        String currentYearMonth = snapshotDate.substring(0, 7); // "YYYY-MM"
        try {
            String ctasSql = String.format(
                "CREATE OR REPLACE TABLE `%s.%s.daily_asset_inventory` AS " +
                "SELECT * FROM `%s.%s.daily_asset_inventory` " +
                "WHERE ( " +
                "  SUBSTR(CAST(snapshot_date AS STRING), 1, 7) >= '%s' " +
                "  OR " +
                "  CONCAT(project_id, '#', CAST(snapshot_date AS STRING)) IN ( " +
                "    SELECT CONCAT(project_id, '#', CAST(MAX(snapshot_date) AS STRING)) " +
                "    FROM `%s.%s.daily_asset_inventory` " +
                "    WHERE SUBSTR(CAST(snapshot_date AS STRING), 1, 7) < '%s' " +
                "    GROUP BY project_id, SUBSTR(CAST(snapshot_date AS STRING), 1, 7) " +
                "  ) " +
                ")",
                targetProjectId, datasetName,
                targetProjectId, datasetName,
                currentYearMonth,
                targetProjectId, datasetName,
                currentYearMonth
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(ctasSql).build());
            log.info("Successfully cleaned past monthly Asset records before '{}' in {}.{}.daily_asset_inventory",
                    currentYearMonth, targetProjectId, datasetName);
        } catch (Exception e) {
            log.warn("Past monthly Asset cleanup notice for '{}': {}", currentYearMonth, e.getMessage());
        }
    }

    /**
     * 과거 월(Past Months) Reservation 데이터 정리 (스냅샷 정책)
     * - 당월(Current Month) 데이터: 일별로 계속 누적 보존
     * - 이전 달(Past Months) 데이터: 각 고객사/Provider/월별 가장 늦은 날짜(MAX snapshot_date) 1일치만 남기고 나머지 일자 데이터는 모두 삭제
     */
    public void cleanPastMonthlyReservationSnapshots(String snapshotDate) {
        if (snapshotDate == null || snapshotDate.length() < 7) return;
        String currentYearMonth = snapshotDate.substring(0, 7); // "YYYY-MM"
        try {
            String ctasSql = String.format(
                "CREATE OR REPLACE TABLE `%s.%s.daily_reservation_inventory` AS " +
                "SELECT * FROM `%s.%s.daily_reservation_inventory` " +
                "WHERE ( " +
                "  SUBSTR(CAST(snapshot_date AS STRING), 1, 7) >= '%s' " +
                "  OR " +
                "  CONCAT(COALESCE(customer_name, ''), '#', COALESCE(provider, ''), '#', CAST(snapshot_date AS STRING)) IN ( " +
                "    SELECT CONCAT(COALESCE(customer_name, ''), '#', COALESCE(provider, ''), '#', CAST(MAX(snapshot_date) AS STRING)) " +
                "    FROM `%s.%s.daily_reservation_inventory` " +
                "    WHERE SUBSTR(CAST(snapshot_date AS STRING), 1, 7) < '%s' " +
                "    GROUP BY customer_name, provider, SUBSTR(CAST(snapshot_date AS STRING), 1, 7) " +
                "  ) " +
                ")",
                targetProjectId, datasetName,
                targetProjectId, datasetName,
                currentYearMonth,
                targetProjectId, datasetName,
                currentYearMonth
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(ctasSql).build());
            log.info("Successfully cleaned past monthly Reservation records before '{}' in {}.{}.daily_reservation_inventory",
                    currentYearMonth, targetProjectId, datasetName);
        } catch (Exception e) {
            log.warn("Past monthly Reservation cleanup notice for '{}': {}", currentYearMonth, e.getMessage());
        }
    }

    /**
     * 과거 월(Past Months) Recommender 데이터 정리 (스냅샷 정책)
     * - 당월(Current Month) 데이터: deleteCurrentMonthDailyRecommenders에 의해 최신 1일치 유지
     * - 이전 달(Past Months) 데이터: 각 프로젝트/월별 가장 늦은 날짜(MAX snapshot_date) 1일치만 남기고 나머지 일자 데이터는 모두 삭제
     */
    public void cleanPastMonthlyRecommenderSnapshots(String snapshotDate) {
        ensureDailyRecommenderTableExists();
        if (snapshotDate == null || snapshotDate.length() < 7) return;
        String currentYearMonth = snapshotDate.substring(0, 7); // "YYYY-MM"
        try {
            String ctasSql = String.format(
                "CREATE OR REPLACE TABLE `%s.%s.daily_recommender_inventory` AS " +
                "SELECT * FROM `%s.%s.daily_recommender_inventory` " +
                "WHERE ( " +
                "  SUBSTR(CAST(snapshot_date AS STRING), 1, 7) >= '%s' " +
                "  OR " +
                "  CONCAT(project_id, '#', CAST(snapshot_date AS STRING)) IN ( " +
                "    SELECT CONCAT(project_id, '#', CAST(MAX(snapshot_date) AS STRING)) " +
                "    FROM `%s.%s.daily_recommender_inventory` " +
                "    WHERE SUBSTR(CAST(snapshot_date AS STRING), 1, 7) < '%s' " +
                "    GROUP BY project_id, SUBSTR(CAST(snapshot_date AS STRING), 1, 7) " +
                "  ) " +
                ")",
                targetProjectId, datasetName,
                targetProjectId, datasetName,
                currentYearMonth,
                targetProjectId, datasetName,
                currentYearMonth
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(ctasSql).build());
            log.info("Successfully cleaned past monthly Recommender records before '{}' in {}.{}.daily_recommender_inventory",
                    currentYearMonth, targetProjectId, datasetName);
        } catch (Exception e) {
            log.warn("Past monthly Recommender cleanup notice for '{}': {}", currentYearMonth, e.getMessage());
        }
    }

    /**
     * 과거 월(Month) Vertex AI 데이터 정리 (스냅샷 정책)
     * - 당월(Current Month) 데이터: 일별로 계속 누적 보존
     * - 이전 달(Past Months) 데이터: 각 프로젝트/월별 가장 늦은 날짜(MAX snapshot_date) 1건만 남기고 나머지 일자 데이터는 모두 삭제
     */
    public void cleanPastMonthlyVertexAiSnapshots(String snapshotDate) {
        ensureDailyVertexAiMetricsTableExists();
        if (snapshotDate == null || snapshotDate.length() < 7) return;
        String currentYearMonth = snapshotDate.substring(0, 7); // "YYYY-MM"
        try {
            String ctasSql = String.format(
                "CREATE OR REPLACE TABLE `%s.%s.daily_vertex_ai_metrics` AS " +
                "SELECT * FROM `%s.%s.daily_vertex_ai_metrics` " +
                "WHERE ( " +
                "  SUBSTR(CAST(snapshot_date AS STRING), 1, 7) >= '%s' " +
                "  OR " +
                "  CONCAT(project_id, '#', CAST(snapshot_date AS STRING)) IN ( " +
                "    SELECT CONCAT(project_id, '#', CAST(MAX(snapshot_date) AS STRING)) " +
                "    FROM `%s.%s.daily_vertex_ai_metrics` " +
                "    WHERE SUBSTR(CAST(snapshot_date AS STRING), 1, 7) < '%s' " +
                "    GROUP BY project_id, SUBSTR(CAST(snapshot_date AS STRING), 1, 7) " +
                "  ) " +
                ")",
                targetProjectId, datasetName,
                targetProjectId, datasetName,
                currentYearMonth,
                targetProjectId, datasetName,
                currentYearMonth
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(ctasSql).build());
            log.info("Successfully cleaned past monthly Vertex AI records before '{}' in {}.{}.daily_vertex_ai_metrics (Retained 1 snapshot per project/month)",
                    currentYearMonth, targetProjectId, datasetName);
        } catch (Exception e) {
            log.warn("Past monthly Vertex AI cleanup notice for '{}': {}", currentYearMonth, e.getMessage());
        }
    }

    /**
     * 당일(snapshotDate) 특정 프로젝트의 기존 Vertex AI 지표 선행 삭제 (멱등성 Idempotency 보장)
     */
    public void deleteDailyVertexAiMetrics(String snapshotDate, String projectId) {
        ensureDailyVertexAiMetricsTableExists();
        if (snapshotDate == null || projectId == null) return;
        try {
            String deleteSql = String.format(
                "DELETE FROM `%s.%s.daily_vertex_ai_metrics` " +
                "WHERE snapshot_date = '%s' AND project_id = '%s'",
                targetProjectId, datasetName, snapshotDate, projectId
            );
            bigQuery.query(QueryJobConfiguration.newBuilder(deleteSql).build());
            log.debug("Cleaned existing daily_vertex_ai_metrics record for {} / {}", snapshotDate, projectId);
        } catch (Exception e) {
            log.debug("deleteDailyVertexAiMetrics notice: {}", e.getMessage());
        }
    }

    /**
     * 특정 고객사 프로젝트의 Vertex AI & GenAI 일일 운영 지표 적재 (실제 Cloud Monitoring 메트릭 기반)
     */
    public void collectAndInsertDailyVertexAiMetrics(String snapshotDate, String projectId, String customerName, GoogleCredentials credentials) {
        ensureDailyVertexAiMetricsTableExists();
        deleteDailyVertexAiMetrics(snapshotDate, projectId); // 멱등성 보장 (배치 재실행 시 중복 방지)

        TableId tableId = TableId.of(targetProjectId, datasetName, "daily_vertex_ai_metrics");

        // GcpResourceFetcher를 통해 Cloud Monitoring 및 리소스 실데이터 수집
        GcpResourceFetcher.VertexAiCollectedData data = gcpResourceFetcher.getVertexAiMetricsData(credentials, projectId);

        Map<String, Object> row = new HashMap<>();
        row.put("snapshot_date", snapshotDate);
        row.put("project_id", projectId);
        row.put("customer_name", customerName);
        row.put("input_tokens", data.getInputTokens());
        row.put("output_tokens", data.getOutputTokens());
        row.put("current_rpm", data.getCurrentRpm());
        row.put("max_rpm_quota", data.getMaxRpmQuota());
        row.put("current_tpd", data.getCurrentTpd());
        row.put("max_tpd_quota", data.getMaxTpdQuota());
        row.put("total_endpoints", data.getTotalEndpoints());
        row.put("active_endpoints", data.getActiveEndpoints());
        row.put("idle_endpoints", data.getIdleEndpoints());
        row.put("allocated_gpus", data.getAllocatedGpus());
        row.put("allocated_tpus", data.getAllocatedTpus());
        row.put("gpu_model", data.getGpuModel() != null ? data.getGpuModel() : "N/A");
        row.put("estimated_hourly_cost", data.getEstimatedHourlyCost());
        row.put("gemini_flash_ratio", data.getGeminiFlashRatio());
        row.put("gemini_pro_ratio", data.getGeminiProRatio());
        row.put("fine_tuned_ratio", data.getFineTunedRatio());
        row.put("prompt_cache_hit_ratio", data.getPromptCacheHitRatio());
        row.put("rate_limit_429_errors", data.getRateLimit429Errors());
        row.put("safety_filter_blocks", data.getSafetyFilterBlocks());
        row.put("avg_latency_ms", data.getAvgLatencyMs());
        row.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        try {
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            if (response.hasErrors()) {
                log.error("BigQuery insert error for daily_vertex_ai_metrics: {}", response.getInsertErrors());
            } else {
                log.info("Successfully inserted real daily_vertex_ai_metrics for {} (Tokens: in={}, out={}) into {}.{}",
                        projectId, data.getInputTokens(), data.getOutputTokens(), targetProjectId, datasetName);
            }
        } catch (Exception e) {
            log.error("BigQuery insert failed for daily_vertex_ai_metrics in project {}", projectId, e);
        }
    }

    private void collectAzureAppCredentials(InfraEnvironment env, String snapshotDate, String tenantId, String clientId, String clientSecret) {
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
                log.error("Failed to get Graph API token for Azure App check. Response code: {}", tokenConn.getResponseCode());
                return;
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
                log.error("Failed to query Graph API for Azure Apps. Response code: {}", apiConn.getResponseCode());
                return;
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
                            processCredential(snapshotDate, subscriptionId, customerName, displayName, "CLIENT_SECRET", keyId, endDateTime);
                        }
                    }

                    JsonNode certs = app.get("keyCredentials");
                    if (certs != null && certs.isArray()) {
                        for (JsonNode cert : certs) {
                            String keyId = cert.has("keyId") ? cert.get("keyId").asText() : "";
                            String endDateTime = cert.has("endDateTime") ? cert.get("endDateTime").asText() : "";
                            processCredential(snapshotDate, subscriptionId, customerName, displayName, "CERTIFICATE", keyId, endDateTime);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to collect Azure App registration keys for env {}", env.getEnvironmentName(), e);
        }
    }

    private void processCredential(String snapshotDate, String subscriptionId, String customerName, 
                                   String appName, String type, String keyId, String endDateTime) {
        if (endDateTime == null || endDateTime.isEmpty()) return;

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

            insertDailyReservationBatch(snapshotDate, subscriptionId, customerName, "AZURE_APP",
                appName, status, "", expiryDate, "", type, "", "", keyId);
        } catch (Exception e) {
            log.error("Error processing Azure App credential expiration date parsing", e);
        }
    }

    private void insertDailyReservationBatch(String snapshotDate, String projectId, String customerName,
            String provider, String reservationName, String status, String startDate, String expiryDate,
            String plan, String type, String region, String scope, String resourceDetail) {
        TableId tableId = TableId.of(targetProjectId, datasetName, "daily_reservation_inventory");
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
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            if (response.hasErrors()) {
                log.error("BigQuery insert error for daily_reservation_inventory: {}", response.getInsertErrors());
            }
        } catch (Exception e) {
            log.error("BigQuery reservation insert failed for {}", reservationName, e);
        }
    }

    private void deleteDailyReservations(String snapshotDate, String customerName, String provider) {
        try {
            String query = String.format(
                "DELETE FROM `%s.%s.daily_reservation_inventory` " +
                "WHERE snapshot_date = '%s' AND customer_name = '%s' AND provider = '%s'",
                targetProjectId, datasetName, snapshotDate, customerName, provider);
            bigQuery.query(QueryJobConfiguration.newBuilder(query).build());
            log.info("Cleared existing daily_reservation_inventory for snapshot: {}, customer: {}, provider: {}", snapshotDate, customerName, provider);
        } catch (Exception e) {
            log.error("Failed to clear existing daily_reservation_inventory for customer: {}", customerName, e);
        }
    }

    // 매일 새벽 3시에 실행: Soft Delete 및 구형 중복 데이터 완전 삭제 (Hard Delete)
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupDeletedData() {
        log.info("Starting Nightly Cleanup for deleted and duplicate data in BigQuery");
        try {
            String[] tables = {"infra_customer", "infra_environment"};
            for (String table : tables) {
                // 1. 중복된 과거 데이터 삭제 (동일 id 중 created_at이 가장 최신이 아닌 데이터)
                String deleteDuplicatesQuery = String.format(
                    "DELETE FROM `%s.%s.%s` main " +
                    "WHERE main.created_at < (" +
                    "  SELECT MAX(sub.created_at) " +
                    "  FROM `%s.%s.%s` sub " +
                    "  WHERE sub.id = main.id" +
                    ")", targetProjectId, datasetName, table, targetProjectId, datasetName, table);
                bigQuery.query(QueryJobConfiguration.newBuilder(deleteDuplicatesQuery).build());
                log.info("Successfully deleted older duplicate data in {}", table);

                // 2. is_deleted = TRUE 인 데이터 삭제
                String deleteSoftDeletedQuery = String.format(
                    "DELETE FROM `%s.%s.%s` WHERE is_deleted = TRUE", targetProjectId, datasetName, table);
                bigQuery.query(QueryJobConfiguration.newBuilder(deleteSoftDeletedQuery).build());
                log.info("Successfully hard-deleted 'is_deleted = TRUE' data in {}", table);
            }
        } catch (Exception e) {
            log.error("Failed to execute nightly cleanup batch", e);
        }
    }

    @Scheduled(cron = "0 0 10 * * MON-FRI")
    public void checkAzureAppKeysExpiryAndNotifySlack() {
        log.info("Starting Slack notification batch for Azure App Keys expiration check");
        try {
            String query = String.format(
                "SELECT customer_name, reservation_name, expiry_date " +
                "FROM `%s.%s.daily_reservation_inventory` " +
                "WHERE provider = 'AZURE_APP' " +
                "  AND snapshot_date = (" +
                "      SELECT MAX(snapshot_date) " +
                "      FROM `%s.%s.daily_reservation_inventory` " +
                "      WHERE provider = 'AZURE_APP'" +
                "  ) " +
                "  AND DATE_DIFF(PARSE_DATE('%%Y-%%m-%%d', expiry_date), CURRENT_DATE(), DAY) <= 10 " +
                "  AND DATE_DIFF(PARSE_DATE('%%Y-%%m-%%d', expiry_date), CURRENT_DATE(), DAY) >= 0",
                targetProjectId, datasetName, targetProjectId, datasetName
            );

            QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
            TableResult result = bigQuery.query(queryConfig);

            StringBuilder sb = new StringBuilder();
            boolean hasItems = false;
            LocalDate today = LocalDate.now();

            for (FieldValueList row : result.iterateAll()) {
                if (!hasItems) {
                    sb.append("⚠️ *[경고] Azure AD App 자격 증명(키값) 만료 임박 안내 (10일 이내)*\n\n");
                    sb.append("만료 예정인 Azure AD Application 자격 증명이 감지되었습니다.\n");
                    sb.append("원활한 서비스 기동을 위해 만료일 전 신속히 갱신 처리를 부탁드립니다.\n\n");
                    sb.append("==================================================\n");
                    hasItems = true;
                }
                String customerName = row.get("customer_name").isNull() ? "Unknown" : row.get("customer_name").getStringValue();
                String appName = row.get("reservation_name").isNull() ? "Unknown" : row.get("reservation_name").getStringValue();
                String expiryDate = row.get("expiry_date").isNull() ? "" : row.get("expiry_date").getStringValue();

                long dDay = 0;
                if (!expiryDate.isEmpty()) {
                    try {
                        LocalDate expiry = LocalDate.parse(expiryDate);
                        dDay = java.time.temporal.ChronoUnit.DAYS.between(today, expiry);
                    } catch (Exception e) {
                        log.error("Failed to parse expiry date {}", expiryDate, e);
                    }
                }

                sb.append("• *고객사*: ").append(customerName).append("\n");
                sb.append("• *앱 이름*: ").append(appName).append("\n");
                sb.append("• *만료일*: ").append(expiryDate).append(" (*D-").append(dDay).append("일 남음*)\n");
                sb.append("==================================================\n");
            }

            if (hasItems) {
                sb.append("\n※ 조치 방법: Azure Portal > Entra ID > 앱 등록 > 해당 앱 선택 > '인증서 및 사용자 비밀 정보'에서 새 키를 생성 및 갱신해 주십시오.");
                sendSlackNotification(sb.toString());
            } else {
                log.info("No Azure App Keys expiring within 10 days.");
            }
        } catch (Exception e) {
            log.error("Failed to execute Slack notification batch for Azure App Keys expiration", e);
        }
    }

    private void sendSlackNotification(String message) {
        if (slackWebhookUrl == null || slackWebhookUrl.isEmpty() || slackWebhookUrl.contains("placeholder")) {
            log.warn("Slack Webhook URL is not configured. Skipping notification.");
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> payload = new HashMap<>();
            payload.put("text", message);
            String jsonPayload = mapper.writeValueAsString(payload);

            HttpURLConnection conn = (HttpURLConnection) new URL(slackWebhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 204) {
                log.info("Successfully sent Slack notification.");
            } else {
                log.error("Failed to send Slack notification. Response code: {}", responseCode);
            }
        } catch (Exception e) {
            log.error("Error sending Slack notification", e);
        }
    }

    private String getStringFromStruct(com.google.protobuf.Struct struct, String... path) {
        com.google.protobuf.Value v = getValueFromStruct(struct, path);
        return v != null ? v.getStringValue() : "";
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
}
