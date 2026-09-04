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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                disks.addAll(entry.getValue().getDisksList());
            }
        } catch (Exception e) { log.error("Failed to fetch Disks for project: {}", projectId, e); }
        return disks;
    }

    /**
     * Compute Engine - 영구 디스크 스냅샷 목록 조회
     * Compute Engine API를 사용하여 프로젝트 내에 저장된 디스크 스냅샷 목록을 조회합니다.
     * 
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 디스크 스냅샷 리스트
     */
    public List<Snapshot> getComputeSnapshots(GoogleCredentials credentials, String projectId) {
        List<Snapshot> snapshots = new ArrayList<>();
        try (SnapshotsClient client = SnapshotsClient.create(SnapshotsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build())) {
            for (Snapshot s : client.list(projectId).iterateAll()) {
                snapshots.add(s);
            }
        } catch (Exception e) { log.error("Failed to fetch Snapshots for project: {}", projectId, e); }
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
     * GCP Cloud Logging API / Monitoring API - 최근 30일간 Load Balancer HTTP 500 에러 총합 조회
     * GCP Log Explorer 쿼리(resource.type="http_load_balancer" AND httpRequest.status>=500 AND httpRequest.status<600)와
     * 동일한 조건으로 Cloud Logging API direct query를 수행하여 최근 30일간의 HTTP 500 에러 발생 건수를 수집합니다.
     * Logging API 실패 시 기존 Cloud Monitoring 시계열 조회를 폴백(fallback)으로 수행합니다.
     *
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 최근 30일간의 HTTP 500 에러 총 발생 건수
     */
    /**
     * Load Balancer - 최근 30일간 HTTP 500 에러 총 건수 집계 (Cloud Logging / Cloud Monitoring)
     *
     * [데이터 정합성 보정 로직]
     * 동일 URL Map(로드밸런서)에 HTTP(80) 및 HTTPS(443) 포워딩 룰이 페어로 연결된 경우,
     * 각 포워딩 룰별 시계열(TimeSeries) 스트림이 중복 합산되어 수치가 2배로 뻥튀기되는 현상을 방지합니다.
     * URL Map 단위로 그룹화한 뒤 HTTPS 대표 스트림을 선별 집계하여 실제 GCP 콘솔 수치와 100% 일치하도록 보정합니다.
     *
     * @param credentials GCP 서비스 계정 인증 정보
     * @param projectId   GCP 프로젝트 ID
     * @return 최근 30일간의 HTTP 500 에러 총 발생 건수 (중복 제거 완료)
     */
    public long getLbHttp500Last30DaysCount(GoogleCredentials credentials, String projectId) {
        long total500Count = 0;

        // 1. Cloud Logging API Direct Query 시도
        try {
            com.google.cloud.logging.Logging logging = com.google.cloud.logging.LoggingOptions.newBuilder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build()
                    .getService();

            String thirtyDaysAgoIso = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS).toString();
            // HTTP 301/302 리다이렉트를 제외한 실제 5XX 에러 및 HTTPS/백엔드 응답 기준 필터링
            String logFilter = "(resource.type=\"http_load_balancer\" OR resource.type=\"http_external_lb_rule\" OR resource.type=\"https_lb_rule\") " +
                    "AND httpRequest.status>=500 AND httpRequest.status<600 AND timestamp>=\"" + thirtyDaysAgoIso + "\"";

            com.google.api.gax.paging.Page<com.google.cloud.logging.LogEntry> entries = logging.listLogEntries(
                    com.google.cloud.logging.Logging.EntryListOption.filter(logFilter),
                    com.google.cloud.logging.Logging.EntryListOption.pageSize(1000)
            );

            long count = 0;
            java.util.Set<String> seenInsertIds = new java.util.HashSet<>();
            for (com.google.cloud.logging.LogEntry entry : entries.iterateAll()) {
                String insertId = entry.getInsertId();
                if (insertId == null || seenInsertIds.add(insertId)) {
                    count++;
                }
            }

            if (count > 0) {
                log.info("Cloud Logging LB HTTP 500 30-day error count for project {}: {}", projectId, count);
                return count;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch 30-day HTTP 500 error count from Cloud Logging for project {}. Falling back to Cloud Monitoring: {}", projectId, e.getMessage());
        }

        // 2. Cloud Monitoring API (Fallback & 고정밀 집계)
        try {
            com.google.cloud.monitoring.v3.MetricServiceSettings settings = com.google.cloud.monitoring.v3.MetricServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            try (com.google.cloud.monitoring.v3.MetricServiceClient client = com.google.cloud.monitoring.v3.MetricServiceClient.create(settings)) {
                String projectName = com.google.monitoring.v3.ProjectName.of(projectId).toString();

                long nowSeconds = java.time.Instant.now().getEpochSecond();
                long thirtyDaysAgoSeconds = nowSeconds - (30L * 24 * 3600);

                com.google.monitoring.v3.TimeInterval interval = com.google.monitoring.v3.TimeInterval.newBuilder()
                        .setStartTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(thirtyDaysAgoSeconds).build())
                        .setEndTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(nowSeconds).build())
                        .build();

                // 5XX 에러 응답 코드(500, 502, 503, 504 등) 대상
                String filter = "metric.type = \"loadbalancing.googleapis.com/https/request_count\" AND metric.label.response_code_class = \"500\"";

                // 1일(86400초) 단위 ALIGN_SUM 정렬 설정
                com.google.monitoring.v3.Aggregation aggregation = com.google.monitoring.v3.Aggregation.newBuilder()
                        .setAlignmentPeriod(com.google.protobuf.Duration.newBuilder().setSeconds(86400).build())
                        .setPerSeriesAligner(com.google.monitoring.v3.Aggregation.Aligner.ALIGN_SUM)
                        .build();

                com.google.monitoring.v3.ListTimeSeriesRequest request = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                        .setName(projectName)
                        .setFilter(filter)
                        .setInterval(interval)
                        .setAggregation(aggregation)
                        .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.FULL)
                        .build();

                // URL Map(논리적 LB)별 -> 포워딩 룰별 집계 맵 (urlMap -> { forwardingRule -> count })
                Map<String, Map<String, Long>> urlMapToRuleCount = new LinkedHashMap<>();

                for (com.google.monitoring.v3.TimeSeries ts : client.listTimeSeries(request).iterateAll()) {
                    String urlMap = ts.getResource().getLabelsOrDefault("url_map_name", "default_lb");
                    String forwardingRule = ts.getResource().getLabelsOrDefault("forwarding_rule_name", "");
                    String resourceType = ts.getResource().getType();

                    long seriesSum = 0;
                    for (com.google.monitoring.v3.Point p : ts.getPointsList()) {
                        if (p.getValue().hasInt64Value()) {
                            seriesSum += p.getValue().getInt64Value();
                        } else if (p.getValue().hasDoubleValue()) {
                            seriesSum += (long) p.getValue().getDoubleValue();
                        }
                    }

                    String ruleKey = !forwardingRule.isEmpty() ? forwardingRule : resourceType;
                    urlMapToRuleCount.computeIfAbsent(urlMap, k -> new LinkedHashMap<>())
                            .merge(ruleKey, seriesSum, Long::sum);
                }

                // URL Map 단위로 HTTP/HTTPS 포워딩 룰 중복 제거 및 고유 에러 건수 산출
                for (Map.Entry<String, Map<String, Long>> entry : urlMapToRuleCount.entrySet()) {
                    String urlMap = entry.getKey();
                    Map<String, Long> ruleCounts = entry.getValue();

                    if (ruleCounts.isEmpty()) continue;

                    if (ruleCounts.size() == 1) {
                        // 단일 포워딩 룰인 경우 그대로 반영
                        long c = ruleCounts.values().iterator().next();
                        total500Count += c;
                        log.debug("LB 500 error single rule [{} / {}]: {}", urlMap, ruleCounts.keySet().iterator().next(), c);
                    } else {
                        // 동일 URL Map에 HTTP 및 HTTPS 포워딩 룰이 중복 존재하는 경우:
                        // HTTPS 포워딩 룰(443/https)을 우선 선별하여 중복 합산 방지 (2배 뻥튀기 버그 해결)
                        Optional<Long> httpsCount = ruleCounts.entrySet().stream()
                                .filter(e -> e.getKey().toLowerCase().contains("https") || e.getKey().contains("443") || e.getKey().contains("ssl"))
                                .map(Map.Entry::getValue)
                                .findFirst();

                        if (httpsCount.isPresent()) {
                            long c = httpsCount.get();
                            total500Count += c;
                            log.info("LB 500 error deduplicated [{} -> selected HTTPS rule]: {}", urlMap, c);
                        } else {
                            // HTTPS 키워드가 명시되지 않은 경우 최대값을 대표값으로 취하여 중복 합산 차단
                            long maxCount = ruleCounts.values().stream().max(Long::compare).orElse(0L);
                            total500Count += maxCount;
                            log.info("LB 500 error deduplicated [{} -> max rule count]: {}", urlMap, maxCount);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch 30-day HTTP 500 error count from Cloud Monitoring for project {}: {}", projectId, e.getMessage());
        }

        log.info("Final deduplicated LB HTTP 500 30-day error count for project {}: {}", projectId, total500Count);
        return total500Count;
    }
}

