package com.example.infra.service;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.compute.v1.*;
import com.google.cloud.storage.*;
import com.google.cloud.bigquery.*;
import com.google.cloud.resourcemanager.v3.*;
import com.google.iam.v1.Policy;
import com.google.iam.v1.Binding;
import com.google.cloud.asset.v1.*;
import com.google.cloud.container.v1.*;
import com.google.cloud.iam.admin.v1.IAMClient;
import com.google.cloud.iam.admin.v1.IAMSettings;
import com.google.iam.admin.v1.ListServiceAccountsRequest;
import com.google.iam.admin.v1.ServiceAccount;
import com.google.iam.admin.v1.ListServiceAccountKeysRequest;
import com.google.iam.admin.v1.ServiceAccountKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.cloud.compute.v1.UrlMap;
import com.google.cloud.compute.v1.UrlMapsClient;
import com.google.cloud.compute.v1.UrlMapsSettings;
import com.google.cloud.compute.v1.UrlMapsScopedList;

/**
 * GCP 리소스 데이터 조회 서비스
 * GCP Java SDK를 사용하여 VM, DB, Storage, Network 등 점검 대상 리소스를 가져오는 역할을 담당합니다.
 */
@Slf4j
@Service
public class GcpResourceFetcher {

    /**
     * Compute Engine - VM 인스턴스 목록 조회
     * GCP Compute Engine API를 사용하여 프로젝트 내 모든 리전/영역(Zone)의 VM 인스턴스 목록을 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 프로젝트 내 모든 VM 인스턴스 리스트
     */
    public List<Instance> getVmInstances(GoogleCredentials credentials, String projectId) {
        List<Instance> instances = new ArrayList<>();
        try (InstancesClient client = InstancesClient.create(InstancesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, InstancesScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getInstancesList() == null) continue;
                instances.addAll(entry.getValue().getInstancesList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch VM instances for project: {}", projectId, e);
            throw new RuntimeException("Failed to list VM instances", e);
        }
        return instances;
    }

    /**
     * Compute Engine - 부하분산기(Forwarding Rules) 목록 조회
     * GCP Compute Engine API를 사용하여 프로젝트 내 등록된 포워딩 룰(부하분산기 프론트엔드 설정) 목록을 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 부하분산기 포워딩 룰 리스트
     */
    public List<ForwardingRule> getForwardingRules(GoogleCredentials credentials, String projectId) {
        List<ForwardingRule> forwardingRules = new ArrayList<>();
        try (ForwardingRulesClient client = ForwardingRulesClient.create(ForwardingRulesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, ForwardingRulesScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getForwardingRulesList() == null) continue;
                forwardingRules.addAll(entry.getValue().getForwardingRulesList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch Forwarding Rules for project: {}", projectId, e);
            throw new RuntimeException("Failed to list forwarding rules", e);
        }
        return forwardingRules;
    }

    /**
     * Compute Engine - 부하분산기(UrlMaps) 목록 조회
     * HTTP(S) 부하분산기의 백엔드 서비스 라우팅 규칙 정보를 담고 있는 UrlMap 목록을 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return HTTP(S) 부하분산 라우팅 규칙(UrlMap) 리스트
     */
    public List<UrlMap> getUrlMaps(GoogleCredentials credentials, String projectId) {
        List<UrlMap> urlMaps = new ArrayList<>();
        try (UrlMapsClient client = UrlMapsClient.create(UrlMapsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, UrlMapsScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getUrlMapsList() == null) continue;
                urlMaps.addAll(entry.getValue().getUrlMapsList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch UrlMaps for project: {}", projectId, e);
            throw new RuntimeException("Failed to list UrlMaps", e);
        }
        return urlMaps;
    }

    /**
     * Cloud Storage - 버킷 목록 조회
     * Google Cloud Storage(GCS) API를 사용하여 해당 프로젝트에 존재하는 전체 스토리지 버킷 목록을 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 스토리지 버킷 리스트
     */
    public List<Bucket> getStorageBuckets(GoogleCredentials credentials, String projectId) {
        Storage storage = StorageOptions.newBuilder().setCredentials(credentials).setProjectId(projectId).build().getService();
        List<Bucket> buckets = new ArrayList<>();
        for (Bucket bucket : storage.list().iterateAll()) {
            buckets.add(bucket);
        }
        return buckets;
    }

    /**
     * BigQuery - 데이터 세트 목록 조회
     * BigQuery API를 사용하여 해당 프로젝트에 생성된 모든 데이터 세트(Dataset)를 조회합니다. (시스템 내부 데이터세트는 제외)
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return BigQuery 데이터 세트 리스트
     */
    public List<Dataset> getBigQueryDatasets(GoogleCredentials credentials, String projectId) {
        BigQuery bq = BigQueryOptions.newBuilder().setCredentials(credentials).setProjectId(projectId).build().getService();
        List<Dataset> datasets = new ArrayList<>();
        for (Dataset ds : bq.listDatasets(projectId, BigQuery.DatasetListOption.all()).iterateAll()) {
            if (ds.getDatasetId().getDataset().startsWith("_")) continue;
            Dataset fullDs = bq.getDataset(ds.getDatasetId());
            if (fullDs != null) datasets.add(fullDs);
        }
        return datasets;
    }

    /**
     * BigQuery - 특정 데이터 세트 내 테이블 목록 조회
     * BigQuery API를 사용하여 지정된 데이터 세트(Dataset) 내에 존재하는 모든 테이블 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @param datasetId   BigQuery 데이터 세트 ID
     * @return 데이터 세트 내 테이블 리스트
     */
    public List<Table> getBigQueryTables(GoogleCredentials credentials, String projectId, DatasetId datasetId) {
        BigQuery bq = BigQueryOptions.newBuilder().setCredentials(credentials).setProjectId(projectId).build().getService();
        List<Table> tables = new ArrayList<>();
        for (Table table : bq.listTables(datasetId).iterateAll()) {
            Table fullTable = bq.getTable(table.getTableId());
            if (fullTable != null) tables.add(fullTable);
        }
        return tables;
    }

    /**
     * IAM - 프로젝트 IAM 정책(Policy) 조회
     * Resource Manager API를 사용하여 프로젝트에 정의된 IAM 역할 바인딩 및 사용자/서비스 계정 권한 정보를 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 프로젝트 IAM 정책 객체
     */
    public Policy getIamPolicy(GoogleCredentials credentials, String projectId) {
        try (com.google.cloud.resourcemanager.v3.ProjectsClient client = com.google.cloud.resourcemanager.v3.ProjectsClient.create(com.google.cloud.resourcemanager.v3.ProjectsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            return client.getIamPolicy("projects/" + projectId);
        } catch (Exception e) {
            log.error("Failed to fetch IAM Policy for project: {}", projectId, e);
            throw new RuntimeException("Failed to fetch IAM Policy", e);
        }
    }

    /**
     * IAM - 프로젝트 소유자(Owner) 권한을 가진 서비스 계정/사용자 계정 수 조회
     *
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return Map<계정타입, 개수> - "serviceAccount": 서비스계정수, "user": 사용자계정수
     */
    public Map<String, Long> getOwnerAccountCounts(GoogleCredentials credentials, String projectId) {
        Map<String, Long> counts = new HashMap<>();
        counts.put("serviceAccount", 0L);
        counts.put("user", 0L);

        try {
            Policy policy = getIamPolicy(credentials, projectId);
            if (policy == null || policy.getBindingsList() == null) {
                return counts;
            }

            for (Binding binding : policy.getBindingsList()) {
                if ("roles/owner".equals(binding.getRole())) {
                    for (String member : binding.getMembersList()) {
                        if (member.startsWith("serviceAccount:")) {
                            counts.put("serviceAccount", counts.get("serviceAccount") + 1);
                        } else if (member.startsWith("user:")) {
                            counts.put("user", counts.get("user") + 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Owner account counts for project: {}", projectId, e);
        }

        return counts;
    }

    /**
     * Cloud Asset Inventory - Cloud SQL 인스턴스 자산 조회
     * Cloud Asset Inventory API를 사용하여 프로젝트 내에 배포된 Cloud SQL DB 인스턴스 자산 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return Cloud SQL 자산 리스트
     */
    public List<Asset> getCloudSqlAssets(GoogleCredentials credentials, String projectId) {
        List<Asset> assets = new ArrayList<>();
        try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            ListAssetsRequest sqlReq = ListAssetsRequest.newBuilder()
                .setParent("projects/" + projectId)
                .addAssetTypes("sqladmin.googleapis.com/Instance")
                .setContentType(ContentType.RESOURCE)
                .build();
            for (Asset asset : assetClient.listAssets(sqlReq).iterateAll()) {
                assets.add(asset);
            }
        } catch (Exception e) {
            log.error("Failed to fetch Cloud SQL Assets for project: {}", projectId, e);
            throw new RuntimeException("Asset API failed for Cloud SQL", e);
        }
        return assets;
    }

    /**
     * Cloud Asset Inventory - AlloyDB 인스턴스 자산 조회
     * Cloud Asset Inventory API를 사용하여 프로젝트 내에 배포된 AlloyDB 인스턴스 자산 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return AlloyDB 자산 리스트
     */
    public List<Asset> getAlloyDbAssets(GoogleCredentials credentials, String projectId) {
        List<Asset> assets = new ArrayList<>();
        try (AssetServiceClient assetClient = AssetServiceClient.create(AssetServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            ListAssetsRequest alloyReq = ListAssetsRequest.newBuilder()
                .setParent("projects/" + projectId)
                .addAssetTypes("alloydb.googleapis.com/Instance")
                .setContentType(ContentType.RESOURCE)
                .build();
            for (Asset asset : assetClient.listAssets(alloyReq).iterateAll()) {
                assets.add(asset);
            }
        } catch (Exception e) {
            log.error("Failed to fetch AlloyDB Assets for project: {}", projectId, e);
            throw new RuntimeException("Asset API failed for AlloyDB", e);
        }
        return assets;
    }

    /**
     * GKE - GKE 클러스터 목록 조회
     * Kubernetes Engine API를 사용하여 프로젝트 내에 배포된 GKE 클러스터 목록 정보를 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return GKE 클러스터 리스트
     */
    public List<com.google.container.v1.Cluster> getGkeClusters(GoogleCredentials credentials, String projectId) {
        try (ClusterManagerClient client = ClusterManagerClient.create(ClusterManagerSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            return client.listClusters(projectId, "-").getClustersList();
        } catch (Exception e) {
            log.error("Failed to fetch GKE Clusters for project: {}", projectId, e);
            throw new RuntimeException("Failed to fetch GKE Clusters", e);
        }
    }

    /**
     * Compute Engine - 영구 디스크(Disk) 목록 조회
     * Compute Engine API를 사용하여 프로젝트 내 모든 영역(Zone)에 생성된 영구 디스크 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 프로젝트 내 전체 디스크 리스트
     */
    public List<Disk> getComputeDisks(GoogleCredentials credentials, String projectId) {
        List<Disk> disks = new ArrayList<>();
        try (DisksClient client = DisksClient.create(DisksSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, DisksScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getDisksList() == null) continue;
                for (Disk disk : entry.getValue().getDisksList()) {
                    // 삭제 중(DELETING)이거나 실패(FAILED) 상태인 비정상 디스크는 집계에서 제외하고 정상 활성(READY) 디스크만 수집
                    if (disk.hasStatus()) {
                        String status = disk.getStatus();
                        if ("DELETING".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                            continue;
                        }
                    }
                    disks.add(disk);
                }
            }
        } catch (Exception e) { log.error("Failed to fetch Disks for project: {}", projectId, e); }
        return disks;
    }

    /**
     * Compute Engine - 영구 디스크 스냅샷 목록 조회
     * Compute Engine API를 사용하여 프로젝트 내에 저장된 디스크 스냅샷 목록을 조회합니다.
     * (표준 스냅샷 및 자동 백업/스케줄 정책 기반 스냅샷 포함)
     *
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 디스크 스냅샷 리스트
     */
    public List<Snapshot> getComputeSnapshots(GoogleCredentials credentials, String projectId) {
        List<Snapshot> snapshots = new ArrayList<>();
        java.util.Set<String> snapshotNames = new java.util.HashSet<>();

        // 1. Standard Snapshots (전역 표준 스냅샷)
        try (SnapshotsClient client = SnapshotsClient.create(SnapshotsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Snapshot s : client.list(projectId).iterateAll()) {
                if (s.hasName() && snapshotNames.add(s.getName())) {
                    snapshots.add(s);
                }
            }
        } catch (Exception e) { log.error("Failed to fetch Snapshots for project: {}", projectId, e); }

        // 2. Instant Snapshots (영역/리전별 인스턴트 및 자동 백업 스냅샷 보강)
        try (InstantSnapshotsClient instantClient = InstantSnapshotsClient.create(InstantSnapshotsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, InstantSnapshotsScopedList> entry : instantClient.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getInstantSnapshotsList() == null) continue;
                for (InstantSnapshot is : entry.getValue().getInstantSnapshotsList()) {
                    if (is.hasName() && snapshotNames.add(is.getName())) {
                        Snapshot converted = Snapshot.newBuilder()
                                .setName(is.getName())
                                .setSelfLink(is.getSelfLink())
                                .setDiskSizeGb(is.getDiskSizeGb())
                                .setStatus(is.getStatus())
                                .setSourceDisk(is.getSourceDisk())
                                .build();
                        snapshots.add(converted);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Instant Snapshots not found or unsupported for project: {}", projectId);
        }

        return snapshots;
    }

    /**
     * Compute Engine - 가상 머신 이미지 목록 조회
     * Compute Engine API를 사용하여 프로젝트 내에 생성된 머신 이미지 목록을 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return VM 이미지 리스트
     */
    public List<Image> getComputeImages(GoogleCredentials credentials, String projectId) {
        List<Image> images = new ArrayList<>();
        try (ImagesClient client = ImagesClient.create(ImagesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Image i : client.list(projectId).iterateAll()) {
                images.add(i);
            }
        } catch (Exception e) { log.error("Failed to fetch Images for project: {}", projectId, e); }
        return images;
    }

    /**
     * Compute Engine - 방화벽 규칙 목록 조회
     * Compute Engine VPC 방화벽 API를 사용하여 프로젝트에 정의된 방화벽 인바운드/아웃바운드 규칙 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 방화벽 규칙 리스트
     */
    public List<Firewall> getComputeFirewalls(GoogleCredentials credentials, String projectId) {
        List<Firewall> firewalls = new ArrayList<>();
        try (FirewallsClient client = FirewallsClient.create(FirewallsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Firewall fw : client.list(projectId).iterateAll()) {
                firewalls.add(fw);
            }
        } catch (Exception e) { log.error("Failed to fetch Firewalls for project: {}", projectId, e); }
        return firewalls;
    }

    /**
     * Compute Engine - 네트워크 주소(외부 IP) 목록 조회
     * Compute Engine API를 사용하여 예약된 외부 IP 주소(고정 및 임시 IP) 할당 현황을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 주소(IP) 정보 리스트
     */
    public List<Address> getComputeAddresses(GoogleCredentials credentials, String projectId) {
        List<Address> addresses = new ArrayList<>();
        try (AddressesClient client = AddressesClient.create(AddressesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, AddressesScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getAddressesList() == null) continue;
                addresses.addAll(entry.getValue().getAddressesList());
            }
        } catch (Exception e) { log.error("Failed to fetch Addresses for project: {}", projectId, e); }
        return addresses;
    }

    /**
     * Compute Engine - VPC 네트워크 목록 조회
     * Compute Engine API를 사용하여 프로젝트 내에 생성된 가상 사설망(VPC) 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return VPC 네트워크 리스트
     */
    public List<Network> getComputeNetworks(GoogleCredentials credentials, String projectId) {
        List<Network> networks = new ArrayList<>();
        try (NetworksClient client = NetworksClient.create(NetworksSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Network n : client.list(projectId).iterateAll()) {
                networks.add(n);
            }
        } catch (Exception e) { log.error("Failed to fetch Networks for project: {}", projectId, e); }
        return networks;
    }

    /**
     * Compute Engine - 서브네트워크(Subnet) 목록 조회
     * Compute Engine API를 사용하여 프로젝트 내 전체 리전에 걸쳐 생성된 서브넷 목록 및 대역 정보를 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 서브넷 리스트
     */
    public List<Subnetwork> getComputeSubnetworks(GoogleCredentials credentials, String projectId) {
        List<Subnetwork> subnetworks = new ArrayList<>();
        try (SubnetworksClient client = SubnetworksClient.create(SubnetworksSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, SubnetworksScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getSubnetworksList() == null) continue;
                subnetworks.addAll(entry.getValue().getSubnetworksList());
            }
        } catch (Exception e) { log.error("Failed to fetch Subnetworks for project: {}", projectId, e); }
        return subnetworks;
    }

    /**
     * Compute Engine - 네트워크 라우팅 경로(Route) 목록 조회
     * Compute Engine 라우팅 API를 사용하여 정의된 네트워크 패킷 라우팅 경로 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 네트워크 라우트 리스트
     */
    public List<Route> getComputeRoutes(GoogleCredentials credentials, String projectId) {
        List<Route> routes = new ArrayList<>();
        try (RoutesClient client = RoutesClient.create(RoutesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Route r : client.list(projectId).iterateAll()) {
                routes.add(r);
            }
        } catch (Exception e) { log.error("Failed to fetch Routes for project: {}", projectId, e); }
        return routes;
    }

    /**
     * Compute Engine - Cloud Router 목록 조회
     * Compute Engine API를 사용하여 VPC 네트워크와 외부 간 동적 라우팅을 지원하는 Cloud Router 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return Cloud Router 리스트
     */
    public List<Router> getComputeRouters(GoogleCredentials credentials, String projectId) {
        List<Router> routers = new ArrayList<>();
        try (RoutersClient client = RoutersClient.create(RoutersSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, RoutersScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getRoutersList() == null) continue;
                routers.addAll(entry.getValue().getRoutersList());
            }
        } catch (Exception e) { log.error("Failed to fetch Routers for project: {}", projectId, e); }
        return routers;
    }

    /**
     * IAM - 서비스 계정(Service Accounts) 목록 조회
     * IAM Admin API를 사용하여 프로젝트 내에 생성되어 있는 서비스 계정 목록 정보를 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 서비스 계정 리스트
     */
    public List<ServiceAccount> getServiceAccounts(GoogleCredentials credentials, String projectId) {
        List<ServiceAccount> accounts = new ArrayList<>();
        try {
            IAMSettings settings = IAMSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();
            try (IAMClient client = IAMClient.create(settings)) {
                ListServiceAccountsRequest request = ListServiceAccountsRequest.newBuilder()
                    .setName("projects/" + projectId)
                    .build();
                for (ServiceAccount sa : client.listServiceAccounts(request).iterateAll()) {
                    accounts.add(sa);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch ServiceAccounts for project: {}", projectId, e);
        }
        return accounts;
    }

    /**
     * IAM - 특정 서비스 계정의 사용자 관리 키 목록 조회
     * IAM Admin API를 사용하여 특정 서비스 계정에 발급되어 활성화된 키 목록(특히 만료 기간 체크용)을 수집합니다.
     * 
     * @param credentials        GCP 서비스 계정 인증 정보
     * @param serviceAccountName 서비스 계정 리소스 이름 (예: projects/PROJ/serviceAccounts/EMAIL)
     * @return 사용자 생성 서비스 계정 키 리스트
     */
    public List<ServiceAccountKey> getServiceAccountKeys(GoogleCredentials credentials, String serviceAccountName) {
        List<ServiceAccountKey> keys = new ArrayList<>();
        try {
            IAMSettings settings = IAMSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();
            try (IAMClient client = IAMClient.create(settings)) {
                ListServiceAccountKeysRequest request = ListServiceAccountKeysRequest.newBuilder()
                    .setName(serviceAccountName)
                    .addKeyTypes(ListServiceAccountKeysRequest.KeyType.USER_MANAGED)
                    .build();
                keys.addAll(client.listServiceAccountKeys(request).getKeysList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch Keys for SA: {}", serviceAccountName, e);
        }
        return keys;
    }

    /**
     * Compute Engine - 약정 사용 할인(CUD, Committed Use Discount) 목록 조회
     * Compute Engine CUD API를 사용하여 프로젝트 내에 적용되어 실행 중인 약정 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 약정 사용 할인 리스트
     */
    public List<Commitment> getCommitments(GoogleCredentials credentials, String projectId) {
        List<Commitment> commitments = new ArrayList<>();
        try (RegionCommitmentsClient client = RegionCommitmentsClient.create(RegionCommitmentsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, CommitmentsScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getCommitmentsList() == null) continue;
                commitments.addAll(entry.getValue().getCommitmentsList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch Commitments(CUD) for project: {}", projectId, e);
        }
        return commitments;
    }

    /**
     * Compute Engine - SSL 인증서 목록 조회
     * Compute Engine API를 사용하여 부하분산기 등에 바인딩하기 위해 등록된 SSL 인증서 목록을 수집합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return SSL 인증서 리스트
     */
    public List<SslCertificate> getSslCertificates(GoogleCredentials credentials, String projectId) {
        List<SslCertificate> certificates = new ArrayList<>();
        try (SslCertificatesClient client = SslCertificatesClient.create(
                SslCertificatesSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                        .build())) {
            // aggregatedList: 글로벌 + 모든 리전 인증서 수집
            for (Map.Entry<String, com.google.cloud.compute.v1.SslCertificatesScopedList> entry :
                    client.aggregatedList(projectId).iterateAll()) {
                com.google.cloud.compute.v1.SslCertificatesScopedList scopedList = entry.getValue();
                if (scopedList.getSslCertificatesList() == null) continue;
                certificates.addAll(scopedList.getSslCertificatesList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch SSL certificates for project: {}", projectId, e);
        }
        return certificates;
    }

    /**
     * Compute Engine - Target HTTP Proxy 목록 조회 (글로벌 + 리전)
     * proxyName -> urlMapName 매핑 구성에 사용됩니다.
     */
    public Map<String, String> getTargetHttpProxyUrlMapMap(GoogleCredentials credentials, String projectId) {
        Map<String, String> proxyToUrlMap = new java.util.HashMap<>();
        try (com.google.cloud.compute.v1.TargetHttpProxiesClient client =
                com.google.cloud.compute.v1.TargetHttpProxiesClient.create(
                    com.google.cloud.compute.v1.TargetHttpProxiesSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, com.google.cloud.compute.v1.TargetHttpProxiesScopedList> entry :
                    client.aggregatedList(projectId).iterateAll()) {
                com.google.cloud.compute.v1.TargetHttpProxiesScopedList scopedList = entry.getValue();
                if (scopedList.getTargetHttpProxiesList() == null) continue;
                for (com.google.cloud.compute.v1.TargetHttpProxy proxy : scopedList.getTargetHttpProxiesList()) {
                    String urlMap = proxy.getUrlMap();
                    if (urlMap != null && !urlMap.isEmpty()) {
                        String urlMapName = urlMap.substring(urlMap.lastIndexOf("/") + 1);
                        proxyToUrlMap.put(proxy.getName(), urlMapName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch TargetHttpProxies for project: {}", projectId, e);
        }
        return proxyToUrlMap;
    }

    /**
     * Compute Engine - Target HTTPS Proxy 목록 조회 (글로벌 + 리전)
     * proxyName -> urlMapName 매핑 구성에 사용됩니다.
     */
    public Map<String, String> getTargetHttpsProxyUrlMapMap(GoogleCredentials credentials, String projectId) {
        Map<String, String> proxyToUrlMap = new java.util.HashMap<>();
        try (com.google.cloud.compute.v1.TargetHttpsProxiesClient client =
                com.google.cloud.compute.v1.TargetHttpsProxiesClient.create(
                    com.google.cloud.compute.v1.TargetHttpsProxiesSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, com.google.cloud.compute.v1.TargetHttpsProxiesScopedList> entry :
                    client.aggregatedList(projectId).iterateAll()) {
                com.google.cloud.compute.v1.TargetHttpsProxiesScopedList scopedList = entry.getValue();
                if (scopedList.getTargetHttpsProxiesList() == null) continue;
                for (com.google.cloud.compute.v1.TargetHttpsProxy proxy : scopedList.getTargetHttpsProxiesList()) {
                    String urlMap = proxy.getUrlMap();
                    if (urlMap != null && !urlMap.isEmpty()) {
                        String urlMapName = urlMap.substring(urlMap.lastIndexOf("/") + 1);
                        proxyToUrlMap.put(proxy.getName(), urlMapName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch TargetHttpsProxies for project: {}", projectId, e);
        }
        return proxyToUrlMap;
    }

    /**
     * Compute Engine - 고가용성 VPN Gateway (HA VPN) 목록 조회
     */
    public List<VpnGateway> getVpnGateways(GoogleCredentials credentials, String projectId) {
        List<VpnGateway> vpnGateways = new ArrayList<>();
        try (VpnGatewaysClient client = VpnGatewaysClient.create(VpnGatewaysSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, VpnGatewaysScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getVpnGatewaysList() == null) continue;
                vpnGateways.addAll(entry.getValue().getVpnGatewaysList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch VPN Gateways for project: {}", projectId, e);
        }
        return vpnGateways;
    }

    /**
     * Compute Engine - 기본 VPN Gateway (Classic VPN) 목록 조회
     */
    public List<TargetVpnGateway> getTargetVpnGateways(GoogleCredentials credentials, String projectId) {
        List<TargetVpnGateway> targetVpnGateways = new ArrayList<>();
        try (TargetVpnGatewaysClient client = TargetVpnGatewaysClient.create(TargetVpnGatewaysSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, TargetVpnGatewaysScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getTargetVpnGatewaysList() == null) continue;
                targetVpnGateways.addAll(entry.getValue().getTargetVpnGatewaysList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch Target VPN Gateways for project: {}", projectId, e);
        }
        return targetVpnGateways;
    }

    /**
     * Compute Engine - VPN Tunnel 목록 조회
     */
    public List<VpnTunnel> getVpnTunnels(GoogleCredentials credentials, String projectId) {
        List<VpnTunnel> vpnTunnels = new ArrayList<>();
        try (VpnTunnelsClient client = VpnTunnelsClient.create(VpnTunnelsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Map.Entry<String, VpnTunnelsScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getVpnTunnelsList() == null) continue;
                vpnTunnels.addAll(entry.getValue().getVpnTunnelsList());
            }
        } catch (Exception e) {
            log.error("Failed to fetch VPN Tunnels for project: {}", projectId, e);
        }
        return vpnTunnels;
    }

    /**
     * Cloud Run - Services 목록 조회
     * 프로젝트 내 모든 Cloud Run Services를 수집합니다.
     *
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return Cloud Run Service 리스트
     */
    public List<com.google.cloud.run.v2.Service> getCloudRunServices(GoogleCredentials credentials, String projectId) {
        List<com.google.cloud.run.v2.Service> services = new ArrayList<>();
        try {
            com.google.cloud.run.v2.ServicesSettings settings = com.google.cloud.run.v2.ServicesSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.run.v2.ServicesClient client = com.google.cloud.run.v2.ServicesClient.create(settings)) {
                String parent = "projects/" + projectId + "/locations/-";
                com.google.cloud.run.v2.ListServicesRequest request =
                        com.google.cloud.run.v2.ListServicesRequest.newBuilder().setParent(parent).build();
                for (com.google.cloud.run.v2.Service service : client.listServices(request).iterateAll()) {
                    services.add(service);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Cloud Run Services for project: {}", projectId, e);
        }
        return services;
    }

    /**
     * Cloud Run - Jobs 목록 조회
     * 프로젝트 내 모든 Cloud Run Jobs를 수집합니다.
     *
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return Cloud Run Job 리스트
     */
    public List<com.google.cloud.run.v2.Job> getCloudRunJobs(GoogleCredentials credentials, String projectId) {
        List<com.google.cloud.run.v2.Job> jobs = new ArrayList<>();
        try {
            com.google.cloud.run.v2.JobsSettings settings = com.google.cloud.run.v2.JobsSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.run.v2.JobsClient client = com.google.cloud.run.v2.JobsClient.create(settings)) {
                String parent = "projects/" + projectId + "/locations/-";
                com.google.cloud.run.v2.ListJobsRequest request =
                        com.google.cloud.run.v2.ListJobsRequest.newBuilder().setParent(parent).build();
                for (com.google.cloud.run.v2.Job job : client.listJobs(request).iterateAll()) {
                    jobs.add(job);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Cloud Run Jobs for project: {}", projectId, e);
        }
        return jobs;
    }



    /**
     * App Engine - Service 수 조회 (Asset Inventory 방식)
     * google-cloud-asset 라이브러리를 사용하여 App Engine Service 리소스를 수집합니다.
     */
    public int getAppEngineServiceCount(GoogleCredentials credentials, String projectId) {
        int count = 0;
        try {
            AssetServiceSettings settings = AssetServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (AssetServiceClient client = AssetServiceClient.create(settings)) {
                String scope = "projects/" + projectId;
                ListAssetsRequest request = ListAssetsRequest.newBuilder()
                        .setParent(scope)
                        .addAssetTypes("appengine.googleapis.com/Service")
                        .build();
                for (Asset ignored : client.listAssets(request).iterateAll()) {
                    count++;
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch App Engine Service count for project: {}", projectId, e);
        }
        return count;
    }

    /**
     * Cloud KMS - KeyRing 및 CryptoKey 목록 조회
     * 전체 리전(-) 대상으로 KeyRing을 조회합니다.
     */
    public List<com.google.cloud.kms.v1.KeyRing> getKmsKeyRings(GoogleCredentials credentials, String projectId) {
        List<com.google.cloud.kms.v1.KeyRing> keyRings = new ArrayList<>();
        try {
            com.google.cloud.kms.v1.KeyManagementServiceSettings settings = com.google.cloud.kms.v1.KeyManagementServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.kms.v1.KeyManagementServiceClient client = com.google.cloud.kms.v1.KeyManagementServiceClient.create(settings)) {
                String parent = "projects/" + projectId + "/locations/-";
                com.google.cloud.kms.v1.ListKeyRingsRequest request = com.google.cloud.kms.v1.ListKeyRingsRequest.newBuilder()
                        .setParent(parent).build();
                for (com.google.cloud.kms.v1.KeyRing kr : client.listKeyRings(request).iterateAll()) {
                    keyRings.add(kr);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch KMS KeyRings for project: {}", projectId, e);
        }
        return keyRings;
    }

    /**
     * Secret Manager - Secret 목록 조회
     */
    public List<com.google.cloud.secretmanager.v1.Secret> getSecrets(GoogleCredentials credentials, String projectId) {
        List<com.google.cloud.secretmanager.v1.Secret> secrets = new ArrayList<>();
        try {
            com.google.cloud.secretmanager.v1.SecretManagerServiceSettings settings = com.google.cloud.secretmanager.v1.SecretManagerServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.secretmanager.v1.SecretManagerServiceClient client = com.google.cloud.secretmanager.v1.SecretManagerServiceClient.create(settings)) {
                String parent = "projects/" + projectId;
                com.google.cloud.secretmanager.v1.ListSecretsRequest request = com.google.cloud.secretmanager.v1.ListSecretsRequest.newBuilder()
                        .setParent(parent).build();
                for (com.google.cloud.secretmanager.v1.Secret secret : client.listSecrets(request).iterateAll()) {
                    secrets.add(secret);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Secrets for project: {}", projectId, e);
        }
        return secrets;
    }

    /**
     * Pub/Sub - Topic 목록 조회
     */
    public List<com.google.pubsub.v1.Topic> getPubSubTopics(GoogleCredentials credentials, String projectId) {
        List<com.google.pubsub.v1.Topic> topics = new ArrayList<>();
        try {
            com.google.cloud.pubsub.v1.TopicAdminSettings settings = com.google.cloud.pubsub.v1.TopicAdminSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.pubsub.v1.TopicAdminClient client = com.google.cloud.pubsub.v1.TopicAdminClient.create(settings)) {
                String parent = "projects/" + projectId;
                com.google.cloud.pubsub.v1.TopicAdminClient.ListTopicsPagedResponse response = client.listTopics(parent);
                for (com.google.pubsub.v1.Topic topic : response.iterateAll()) {
                    topics.add(topic);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Pub/Sub Topics for project: {}", projectId, e);
        }
        return topics;
    }

    /**
     * Pub/Sub - Subscription 목록 조회
     */
    public List<com.google.pubsub.v1.Subscription> getPubSubSubscriptions(GoogleCredentials credentials, String projectId) {
        List<com.google.pubsub.v1.Subscription> subscriptions = new ArrayList<>();
        try {
            com.google.cloud.pubsub.v1.SubscriptionAdminSettings settings = com.google.cloud.pubsub.v1.SubscriptionAdminSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.pubsub.v1.SubscriptionAdminClient client = com.google.cloud.pubsub.v1.SubscriptionAdminClient.create(settings)) {
                String parent = "projects/" + projectId;
                for (com.google.pubsub.v1.Subscription sub : client.listSubscriptions(parent).iterateAll()) {
                    subscriptions.add(sub);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Pub/Sub Subscriptions for project: {}", projectId, e);
        }
        return subscriptions;
    }

    /**
     * Memorystore (Redis) - Instance 목록 조회
     */
    public List<com.google.cloud.redis.v1.Instance> getMemorystoreInstances(GoogleCredentials credentials, String projectId) {
        List<com.google.cloud.redis.v1.Instance> instances = new ArrayList<>();
        try {
            com.google.cloud.redis.v1.CloudRedisSettings settings = com.google.cloud.redis.v1.CloudRedisSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.redis.v1.CloudRedisClient client = com.google.cloud.redis.v1.CloudRedisClient.create(settings)) {
                String parent = "projects/" + projectId + "/locations/-";
                for (com.google.cloud.redis.v1.Instance inst : client.listInstances(parent).iterateAll()) {
                    instances.add(inst);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Memorystore Redis instances for project: {}", projectId, e);
        }
        return instances;
    }

    /**
     * Compute Engine - Load Balancer Backend Health 상태 집계
     * 글로벌 및 리전 Backend Service의 백엔드 타겟 헬스체크 상태(HEALTHY, UNHEALTHY)를 수집하여 반환합니다.
     *
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return Map (healthy: 정상 개수, unhealthy: 비정상 개수, total: 전체 인스턴스 개수)
     */
    public Map<String, Integer> getBackendServiceHealthCounts(GoogleCredentials credentials, String projectId) {
        Map<String, Integer> counts = new HashMap<>();
        int healthy = 0;
        int unhealthy = 0;

        try (BackendServicesClient client = BackendServicesClient.create(
                BackendServicesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {

            for (Map.Entry<String, BackendServicesScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                if (entry.getValue().getBackendServicesList() == null) continue;
                String scopeKey = entry.getKey(); // "global" or "regions/asia-northeast3"

                for (BackendService bs : entry.getValue().getBackendServicesList()) {
                    if (bs.getBackendsList() == null) continue;

                    for (Backend backend : bs.getBackendsList()) {
                        String group = backend.getGroup();
                        if (group == null || group.isEmpty()) continue;

                        try {
                            ResourceGroupReference groupRef = ResourceGroupReference.newBuilder().setGroup(group).build();
                            BackendServiceGroupHealth groupHealth = null;

                            if (scopeKey.equals("global") || !bs.hasRegion()) {
                                GetHealthBackendServiceRequest req = GetHealthBackendServiceRequest.newBuilder()
                                        .setProject(projectId)
                                        .setBackendService(bs.getName())
                                        .setResourceGroupReferenceResource(groupRef)
                                        .build();
                                groupHealth = client.getHealth(req);
                            } else {
                                String region = bs.getRegion();
                                if (region != null && region.contains("/regions/")) {
                                    region = region.substring(region.lastIndexOf("/regions/") + 9);
                                } else if (scopeKey.startsWith("regions/")) {
                                    region = scopeKey.substring("regions/".length());
                                }
                                if (region != null && !region.isEmpty()) {
                                    try (RegionBackendServicesClient regionClient = RegionBackendServicesClient.create(
                                            RegionBackendServicesSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
                                        GetHealthRegionBackendServiceRequest req = GetHealthRegionBackendServiceRequest.newBuilder()
                                                .setProject(projectId)
                                                .setRegion(region)
                                                .setBackendService(bs.getName())
                                                .setResourceGroupReferenceResource(groupRef)
                                                .build();
                                        groupHealth = regionClient.getHealth(req);
                                    }
                                }
                            }

                            if (groupHealth != null && groupHealth.getHealthStatusList() != null) {
                                for (HealthStatus hs : groupHealth.getHealthStatusList()) {
                                    String state = hs.getHealthState();
                                    if ("HEALTHY".equalsIgnoreCase(state)) {
                                        healthy++;
                                    } else if ("UNHEALTHY".equalsIgnoreCase(state)) {
                                        unhealthy++;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to get health for backend service {} group {}: {}", bs.getName(), group, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list backend services for health check in project {}: {}", projectId, e.getMessage());
        }

        counts.put("healthy", healthy);
        counts.put("unhealthy", unhealthy);
        counts.put("total", healthy + unhealthy);
        return counts;
    }

    /**
     * Load Balancer HTTP 5XX 에러 수 - 지정 구간 [startSeconds, endSeconds) (Cloud Monitoring)
     * 콘솔 측정항목 탐색기(https/request_count, response_code_class=500, 그룹화 합계·by 없음)와 같은 값.
     * HTTP(80)·HTTPS(443) 포워딩 규칙은 서로 다른 요청이므로 모두 합산하고,
     * 규칙이 연결된 프록시 종류(httpsByRule)로 HTTP/HTTPS를 나눈다. 맵에 없는 규칙(삭제됨 등)은 합계에만 포함.
     *
     * @return {합계, HTTP, HTTPS}. 조회 실패 시 예외 (0으로 저장하지 않도록)
     */
    public long[] getLbHttp5xxCounts(GoogleCredentials credentials, String projectId, long startSeconds, long endSeconds,
                                     Map<String, Boolean> httpsByRule) throws java.io.IOException {
        com.google.cloud.monitoring.v3.MetricServiceSettings settings = com.google.cloud.monitoring.v3.MetricServiceSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();
        try (com.google.cloud.monitoring.v3.MetricServiceClient client = com.google.cloud.monitoring.v3.MetricServiceClient.create(settings)) {
            com.google.monitoring.v3.ListTimeSeriesRequest request = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                    .setName(com.google.monitoring.v3.ProjectName.of(projectId).toString())
                    .setFilter("metric.type = \"loadbalancing.googleapis.com/https/request_count\" AND metric.label.response_code_class = \"500\"")
                    .setInterval(com.google.monitoring.v3.TimeInterval.newBuilder()
                            .setStartTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(startSeconds))
                            .setEndTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(endSeconds)))
                    // 구간 전체를 한 칸으로 합산
                    .setAggregation(com.google.monitoring.v3.Aggregation.newBuilder()
                            .setAlignmentPeriod(com.google.protobuf.Duration.newBuilder().setSeconds(endSeconds - startSeconds))
                            .setPerSeriesAligner(com.google.monitoring.v3.Aggregation.Aligner.ALIGN_SUM)
                            .setCrossSeriesReducer(com.google.monitoring.v3.Aggregation.Reducer.REDUCE_SUM)
                            .addGroupByFields("resource.label.forwarding_rule_name"))
                    .build();

            long total = 0, http = 0, https = 0;
            for (com.google.monitoring.v3.TimeSeries ts : client.listTimeSeries(request).iterateAll()) {
                long sum = 0;
                for (com.google.monitoring.v3.Point p : ts.getPointsList()) {
                    sum += p.getValue().hasInt64Value() ? p.getValue().getInt64Value() : (long) p.getValue().getDoubleValue();
                }
                total += sum;
                Boolean isHttps = httpsByRule.get(ts.getResource().getLabelsOrDefault("forwarding_rule_name", ""));
                if (Boolean.TRUE.equals(isHttps)) https += sum;
                else if (Boolean.FALSE.equals(isHttps)) http += sum;
            }
            log.info("LB HTTP 5XX for project {}: total={}, http={}, https={}", projectId, total, http, https);
            return new long[]{total, http, https};
        }
    }

    /**
     * AI 서비스 직접 사용 (Direct AI Usage) 관제 지표 수집 결과 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DirectAiCollectedData {
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private long pretrainedApiCalls;
        private long visionApiCalls;
        private long speechApiCalls;
        private long translationApiCalls;
        private long nlpApiCalls;
        private double trainingNodeHours;
        private int pipelineRunsCount;
        private double workbenchUptimeHours;
        private int activeWorkbenchCount;
        private int currentRpm;
        private int maxRpmQuota;
        private double geminiFlashRatio;
        private double geminiProRatio;
        private double claudeRatio;
        private double customModelRatio;
        private double estimatedApiCost;
        private double estimatedTrainingCost;
        private double totalEstimatedDailyCost;
        private List<AiModelUsage> modelUsages;
    }

    /**
     * Vertex AI 모델 호출(API 호출형) 모델별 사용량 (PublisherModel 리소스 기준)
     */
    @lombok.Data
    public static class AiModelUsage {
        private final String publisher;
        private final String model;
        private long invocations;
        private long inputTokens;
        private long outputTokens;
    }

    /**
     * AI 엔드포인트 서빙 (Endpoint Serving) 수집 결과 항목 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EndpointServingItemCollectedData {
        private String endpointId;
        private String endpointName;
        private String deployedModelId;
        private String deployedModelName;
        private String machineType;
        private String acceleratorType;
        private int acceleratorCount;
        private int minReplicas;
        private int maxReplicas;
        private int currentReplicas;
        private long totalRequests;
        private double qps;
        private int avgLatencyMs;
        private int p95LatencyMs;
        private int p99LatencyMs;
        private long errorCount4xx;
        private long errorCount5xx;
        private double errorRate4xxPercent;
        private double errorRate5xxPercent;
        private double successRatePercent;
        private long vectorSearchQueries;
        private long vectorSearchUpdates;
        private double gpuUtilizationPercent;
        private double cpuUtilizationPercent;
        private double nodeUptimeHours;
        private double endpointNodeHours;
        private double hourlyCost;
        private double monthlyCost;
        private String status;
    }

    private static final String PUBLISHER_TOKEN_COUNT_METRIC = "aiplatform.googleapis.com/publisher/online_serving/token_count";
    private static final String PUBLISHER_INVOCATION_COUNT_METRIC = "aiplatform.googleapis.com/publisher/online_serving/model_invocation_count";

    // 시계열 포인트 합계 (정수/실수 값)
    private static long sumPoints(com.google.monitoring.v3.TimeSeries ts) {
        long sum = 0L;
        for (com.google.monitoring.v3.Point p : ts.getPointsList()) {
            if (p.getValue().hasInt64Value()) sum += p.getValue().getInt64Value();
            else if (p.getValue().hasDoubleValue()) sum += (long) p.getValue().getDoubleValue();
        }
        return sum;
    }

    /**
     * GCP Cloud Monitoring 기반 특정 프로젝트의 AI API 호출형 사용량 수집
     * - 모델별 토큰/호출: publisher/online_serving/token_count, model_invocation_count (Gemini, Claude 등 PublisherModel)
     * - Pretrained API: serviceruntime.googleapis.com/api/request_count (Vision, Speech, Translate, NLP)
     * - 학습/파이프라인/Workbench, 비용, Quota는 수집 근거가 없어 0으로 둔다 (추정값을 만들지 않음)
     */
    public DirectAiCollectedData getDirectAiMetricsData(GoogleCredentials credentials, String projectId) {
        long nowSeconds = java.time.Instant.now().getEpochSecond();
        return getDirectAiMetricsData(credentials, projectId, nowSeconds - 24L * 3600, nowSeconds);
    }

    /**
     * 지정 구간 [startSeconds, endSeconds)의 Direct AI 실측 지표 수집 (과거 일자 백필용, Monitoring 보존 6주 이내만 가능)
     */
    public DirectAiCollectedData getDirectAiMetricsData(GoogleCredentials credentials, String projectId, long startSeconds, long endSeconds) {
        log.info("Collecting real Direct AI Usage Cloud Monitoring metrics for project `{}`...", projectId);

        long visionCalls = 0L;
        long speechCalls = 0L;
        long translationCalls = 0L;
        long nlpCalls = 0L;
        Map<String, AiModelUsage> models = new java.util.TreeMap<>();

        try {
            com.google.cloud.monitoring.v3.MetricServiceSettings settings = com.google.cloud.monitoring.v3.MetricServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();

            try (com.google.cloud.monitoring.v3.MetricServiceClient client = com.google.cloud.monitoring.v3.MetricServiceClient.create(settings)) {
                String projectName = com.google.monitoring.v3.ProjectName.of(projectId).toString();
                com.google.monitoring.v3.TimeInterval dailyInterval = com.google.monitoring.v3.TimeInterval.newBuilder()
                        .setStartTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(startSeconds).build())
                        .setEndTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(endSeconds).build())
                        .build();
                // 집계 단위는 구간 길이를 넘지 않게 한다. 86400 고정이면 짧은 구간도 끝 시각 기준 24시간이 합산된다.
                long alignmentSeconds = Math.min(86400L, endSeconds - startSeconds);

                // 1. 모델별 토큰(type=input/output)과 호출 수 (PublisherModel 리소스 라벨: publisher, model_user_id)
                for (String metricType : new String[]{PUBLISHER_TOKEN_COUNT_METRIC, PUBLISHER_INVOCATION_COUNT_METRIC}) {
                    try {
                        com.google.monitoring.v3.ListTimeSeriesRequest req = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                                .setName(projectName)
                                .setFilter("metric.type = \"" + metricType + "\"")
                                .setInterval(dailyInterval)
                                .setAggregation(com.google.monitoring.v3.Aggregation.newBuilder()
                                        .setAlignmentPeriod(com.google.protobuf.Duration.newBuilder().setSeconds(alignmentSeconds).build())
                                        .setPerSeriesAligner(com.google.monitoring.v3.Aggregation.Aligner.ALIGN_SUM)
                                        .build())
                                .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.FULL)
                                .build();

                        for (com.google.monitoring.v3.TimeSeries ts : client.listTimeSeries(req).iterateAll()) {
                            String publisher = ts.getResource().getLabelsOrDefault("publisher", "");
                            String model = ts.getResource().getLabelsOrDefault("model_user_id", "");
                            AiModelUsage usage = models.computeIfAbsent(publisher + "/" + model, k -> new AiModelUsage(publisher, model));
                            long sum = sumPoints(ts);
                            if (metricType.equals(PUBLISHER_INVOCATION_COUNT_METRIC)) {
                                usage.setInvocations(usage.getInvocations() + sum);
                            } else {
                                // 캐시 토큰(cache_read_input 등)은 input/output과 별도 유형이라 합산하지 않음
                                String tokenType = ts.getMetric().getLabelsOrDefault("type", "");
                                if ("input".equals(tokenType)) usage.setInputTokens(usage.getInputTokens() + sum);
                                else if ("output".equals(tokenType)) usage.setOutputTokens(usage.getOutputTokens() + sum);
                            }
                        }
                    } catch (Exception ex) {
                        // 권한 부족과 사용량 0을 구분할 수 있도록 WARN으로 남김
                        log.warn("AI metric [{}] query failed for project {}: {}", metricType, projectId, ex.getMessage());
                    }
                }

                // 2. Pretrained APIs (Vision, Speech, Translation, NLP) 메트릭 조회
                String[] pretrainedServices = {
                        "vision.googleapis.com",
                        "speech.googleapis.com",
                        "translate.googleapis.com",
                        "language.googleapis.com"
                };

                for (String srv : pretrainedServices) {
                    try {
                        String pretrainedFilter = "metric.type = \"serviceruntime.googleapis.com/api/request_count\" AND " +
                                "resource.labels.service = \"" + srv + "\"";

                        com.google.monitoring.v3.ListTimeSeriesRequest preReq = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                                .setName(projectName)
                                .setFilter(pretrainedFilter)
                                .setInterval(dailyInterval)
                                .setAggregation(com.google.monitoring.v3.Aggregation.newBuilder()
                                        .setAlignmentPeriod(com.google.protobuf.Duration.newBuilder().setSeconds(alignmentSeconds).build())
                                        .setPerSeriesAligner(com.google.monitoring.v3.Aggregation.Aligner.ALIGN_SUM)
                                        .build())
                                .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.FULL)
                                .build();

                        for (com.google.monitoring.v3.TimeSeries ts : client.listTimeSeries(preReq).iterateAll()) {
                            long sum = 0L;
                            for (com.google.monitoring.v3.Point p : ts.getPointsList()) {
                                if (p.getValue().hasInt64Value()) sum += p.getValue().getInt64Value();
                                else if (p.getValue().hasDoubleValue()) sum += (long) p.getValue().getDoubleValue();
                            }
                            if (srv.contains("vision")) visionCalls += sum;
                            else if (srv.contains("speech")) speechCalls += sum;
                            else if (srv.contains("translate")) translationCalls += sum;
                            else if (srv.contains("language")) nlpCalls += sum;
                        }
                    } catch (Exception ex) {
                        log.warn("Pretrained API [{}] query failed for project {}: {}", srv, projectId, ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Cloud Monitoring client init or query notice for Direct AI in project {}: {}", projectId, e.getMessage());
        }

        long inputTokens = 0L;
        long outputTokens = 0L;
        double flashTokens = 0.0, proTokens = 0.0, claudeTokens = 0.0, otherTokens = 0.0;
        for (AiModelUsage u : models.values()) {
            inputTokens += u.getInputTokens();
            outputTokens += u.getOutputTokens();
            long tok = u.getInputTokens() + u.getOutputTokens();
            String m = u.getModel().toLowerCase();
            if ("anthropic".equals(u.getPublisher())) claudeTokens += tok;
            else if (m.contains("flash")) flashTokens += tok;
            else if (m.contains("pro")) proTokens += tok;
            else otherTokens += tok;
        }
        long totalTokens = inputTokens + outputTokens;
        long totalPretrainedCalls = visionCalls + speechCalls + translationCalls + nlpCalls;

        // 모델 계열별 토큰 비율 (실측 토큰이 없으면 0)
        double totalModelTokens = flashTokens + proTokens + claudeTokens + otherTokens;
        java.util.function.DoubleUnaryOperator ratio = v -> totalModelTokens > 0 ? Math.round(v / totalModelTokens * 1000.0) / 10.0 : 0.0;

        log.info("Project `{}` Direct AI collected: models={}, tokens={}, pretrainedCalls={}",
                projectId, models.size(), totalTokens, totalPretrainedCalls);

        return DirectAiCollectedData.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .pretrainedApiCalls(totalPretrainedCalls)
                .visionApiCalls(visionCalls)
                .speechApiCalls(speechCalls)
                .translationApiCalls(translationCalls)
                .nlpApiCalls(nlpCalls)
                .geminiFlashRatio(ratio.applyAsDouble(flashTokens))
                .geminiProRatio(ratio.applyAsDouble(proTokens))
                .claudeRatio(ratio.applyAsDouble(claudeTokens))
                .customModelRatio(ratio.applyAsDouble(otherTokens))
                .modelUsages(new ArrayList<>(models.values()))
                .build();
    }

    /**
     * 고객사가 직접 배포한 Vertex AI 엔드포인트(Online Prediction) 수집
     * - 배포 여부와 사양: Vertex AI endpoints.list API 응답 (머신타입, 가속기, 복제본 수)
     * - 트래픽: prediction/online/prediction_count, response_count(응답 코드), prediction_latencies(total 평균)
     * - Gemini/Claude 등 Google 관리형 모델 호출도 같은 메트릭에 잡히지만 endpoints.list에 없으므로 제외 (API 호출형으로 별도 집계)
     * - P95/P99, GPU/CPU 사용률, 비용은 근거 데이터가 없어 0으로 둔다
     */
    public List<EndpointServingItemCollectedData> getEndpointServingMetricsData(GoogleCredentials credentials, String projectId) {
        long nowSeconds = java.time.Instant.now().getEpochSecond();
        return getEndpointServingMetricsData(credentials, projectId, nowSeconds - 24L * 3600, nowSeconds, null);
    }

    /**
     * 지정 구간 [startSeconds, endSeconds)의 엔드포인트 트래픽 수집 (과거 일자 백필용).
     * 사양(머신타입·복제본)은 endpoints.list 현재값이라 과거 일자에도 현재 사양이 기록된다.
     * endpointCache(리전 → endpoints.list 결과)를 넘기면 같은 프로젝트의 여러 날짜 수집에서 목록 조회를 한 번만 한다. null이면 매번 조회.
     */
    public List<EndpointServingItemCollectedData> getEndpointServingMetricsData(GoogleCredentials credentials, String projectId, long startSeconds, long endSeconds,
                                                                                Map<String, List<com.fasterxml.jackson.databind.JsonNode>> endpointCache) {
        log.info("Collecting real Endpoint Serving metrics for project `{}`...", projectId);
        List<EndpointServingItemCollectedData> endpointList = new ArrayList<>();

        Map<String, Long> endpointRequests = new HashMap<>();
        Map<String, Long> endpointErrors4xx = new HashMap<>();
        Map<String, Long> endpointErrors5xx = new HashMap<>();
        Map<String, double[]> endpointLatency = new HashMap<>(); // [평균×건수 합, 건수 합]
        java.util.Set<String> locations = new java.util.TreeSet<>();

        try {
            com.google.cloud.monitoring.v3.MetricServiceSettings settings = com.google.cloud.monitoring.v3.MetricServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();

            try (com.google.cloud.monitoring.v3.MetricServiceClient client = com.google.cloud.monitoring.v3.MetricServiceClient.create(settings)) {
                String projectName = com.google.monitoring.v3.ProjectName.of(projectId).toString();
                com.google.monitoring.v3.TimeInterval dailyInterval = com.google.monitoring.v3.TimeInterval.newBuilder()
                        .setStartTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(startSeconds).build())
                        .setEndTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(endSeconds).build())
                        .build();

                String[] metricTypes = {
                        "aiplatform.googleapis.com/prediction/online/prediction_count",
                        "aiplatform.googleapis.com/prediction/online/response_count",
                        "aiplatform.googleapis.com/prediction/online/prediction_latencies"
                };
                for (String metricType : metricTypes) {
                    try {
                        com.google.monitoring.v3.ListTimeSeriesRequest req = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                                .setName(projectName)
                                .setFilter("metric.type = \"" + metricType + "\" AND resource.type = \"aiplatform.googleapis.com/Endpoint\"")
                                .setInterval(dailyInterval)
                                .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.FULL)
                                .build();

                        for (com.google.monitoring.v3.TimeSeries ts : client.listTimeSeries(req).iterateAll()) {
                            String endpointId = ts.getResource().getLabelsOrDefault("endpoint_id", "");
                            locations.add(ts.getResource().getLabelsOrDefault("location", ""));
                            if (metricType.endsWith("prediction_count")) {
                                endpointRequests.merge(endpointId, sumPoints(ts), Long::sum);
                            } else if (metricType.endsWith("response_count")) {
                                String code = ts.getMetric().getLabelsOrDefault("response_code", "");
                                if (code.startsWith("4")) endpointErrors4xx.merge(endpointId, sumPoints(ts), Long::sum);
                                else if (code.startsWith("5")) endpointErrors5xx.merge(endpointId, sumPoints(ts), Long::sum);
                            } else if ("total".equals(ts.getMetric().getLabelsOrDefault("latency_type", ""))) {
                                double[] acc = endpointLatency.computeIfAbsent(endpointId, k -> new double[2]);
                                for (com.google.monitoring.v3.Point p : ts.getPointsList()) {
                                    if (p.getValue().hasDistributionValue()) {
                                        com.google.api.Distribution d = p.getValue().getDistributionValue();
                                        acc[0] += d.getMean() * d.getCount();
                                        acc[1] += d.getCount();
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Endpoint metric [{}] query failed for project {}: {}", metricType, projectId, ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Cloud Monitoring client init or query notice for endpoint serving in project {}: {}", projectId, e.getMessage());
        }

        // 트래픽이 관측된 리전에서 실제 배포 엔드포인트 목록 조회 (global은 관리형 모델 전용이라 제외)
        locations.remove("");
        locations.remove("global");
        for (String location : locations) {
            List<com.fasterxml.jackson.databind.JsonNode> endpoints = endpointCache != null ? endpointCache.get(location) : null;
            if (endpoints == null) {
                endpoints = listVertexEndpoints(credentials, projectId, location);
                // 조회 실패(null)는 캐시하지 않아 다음 날짜에서 다시 시도한다
                if (endpoints != null && endpointCache != null) endpointCache.put(location, endpoints);
            }
            if (endpoints == null) continue;
            for (com.fasterxml.jackson.databind.JsonNode ep : endpoints) {
                String name = ep.path("name").asText();
                String epId = name.substring(name.lastIndexOf('/') + 1);
                com.fasterxml.jackson.databind.JsonNode dm = ep.path("deployedModels").path(0);
                com.fasterxml.jackson.databind.JsonNode res0 = dm.path("dedicatedResources");
                com.fasterxml.jackson.databind.JsonNode spec = res0.path("machineSpec");
                long totalReqs = endpointRequests.getOrDefault(epId, 0L);
                long err4xx = endpointErrors4xx.getOrDefault(epId, 0L);
                long err5xx = endpointErrors5xx.getOrDefault(epId, 0L);
                double[] lat = endpointLatency.getOrDefault(epId, new double[2]);
                double err4xxRate = totalReqs > 0 ? Math.round((double) err4xx / totalReqs * 1000.0) / 10.0 : 0.0;
                double err5xxRate = totalReqs > 0 ? Math.round((double) err5xx / totalReqs * 1000.0) / 10.0 : 0.0;

                endpointList.add(EndpointServingItemCollectedData.builder()
                        .endpointId(epId)
                        .endpointName(ep.path("displayName").asText(epId))
                        .deployedModelId(dm.path("id").asText(""))
                        .deployedModelName(dm.path("displayName").asText(""))
                        .machineType(spec.path("machineType").asText(""))
                        .acceleratorType(spec.path("acceleratorType").asText(""))
                        .acceleratorCount(spec.path("acceleratorCount").asInt(0))
                        .minReplicas(res0.path("minReplicaCount").asInt(0))
                        .maxReplicas(res0.path("maxReplicaCount").asInt(0))
                        .totalRequests(totalReqs)
                        .qps(Math.round((double) totalReqs / (endSeconds - startSeconds) * 100.0) / 100.0)
                        .avgLatencyMs(lat[1] > 0 ? (int) Math.round(lat[0] / lat[1]) : 0)
                        .errorCount4xx(err4xx)
                        .errorCount5xx(err5xx)
                        .errorRate4xxPercent(err4xxRate)
                        .errorRate5xxPercent(err5xxRate)
                        .successRatePercent(totalReqs > 0 ? Math.max(0.0, Math.round((100.0 - err4xxRate - err5xxRate) * 10.0) / 10.0) : 0.0)
                        .status(dm.isMissingNode() ? "NO_MODEL" : "ACTIVE")
                        .build());
            }
        }

        log.info("Collected {} deployed Vertex AI endpoints for project `{}` (checked locations: {})", endpointList.size(), projectId, locations);
        return endpointList;
    }

    /**
     * Vertex AI endpoints.list 전체 페이지 조회. 실패하면 null (빈 목록과 구분)
     */
    private List<com.fasterxml.jackson.databind.JsonNode> listVertexEndpoints(GoogleCredentials credentials, String projectId, String location) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        List<com.fasterxml.jackson.databind.JsonNode> endpoints = new ArrayList<>();
        String pageToken = "";
        do {
            try {
                credentials.refreshIfExpired();
                String url = "https://" + location + "-aiplatform.googleapis.com/v1/projects/" + projectId
                        + "/locations/" + location + "/endpoints?pageSize=100"
                        + (pageToken.isEmpty() ? "" : "&pageToken=" + pageToken);
                java.net.http.HttpResponse<String> res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                                .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue()).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    log.warn("Vertex AI endpoints.list failed for project {} / {}: HTTP {}", projectId, location, res.statusCode());
                    return null;
                }
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(res.body());
                root.path("endpoints").forEach(endpoints::add);
                pageToken = root.path("nextPageToken").asText("");
            } catch (Exception ex) {
                log.warn("Vertex AI endpoints.list failed for project {} / {}: {}", projectId, location, ex.getMessage());
                return null;
            }
        } while (!pageToken.isEmpty());
        return endpoints;
    }
}
